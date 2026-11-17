package com.peijian.pda.backup

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 数据库备份归档的纯逻辑（不依赖 Android，可在 JVM 单测）。
 *
 * 备份文件是一个 zip：
 *  - manifest.json      元信息 + 校验和
 *  - database/peijian.db  SQLite 主库（导出前已做 wal_checkpoint(TRUNCATE)）
 *  - config/peijian.xml   SharedPreferences（含 lastRev 等同步游标）
 *
 * 把 prefs 一起打进去很关键：只恢复 DB 不恢复 lastRev 会让增量同步漏数据。
 */
object BackupArchive {

    const val FORMAT = "peijian-db-backup"
    const val FORMAT_VERSION = 1

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_DB = "database/peijian.db"
    const val ENTRY_PREFS = "config/peijian.xml"

    data class Manifest(
        val format: String,
        val formatVersion: Int,
        val createdAt: Long,
        val appVersion: String,
        val dbVersion: Int,
        val dbSha256: String,
        val dbBytes: Long,
        val boxCount: Int,
        val unsyncedShipments: Int,
        val unsyncedChecks: Int,
        val hasPrefs: Boolean
    ) {
        fun toJson(): String = JSONObject().apply {
            put("format", format)
            put("formatVersion", formatVersion)
            put("createdAt", createdAt)
            put("appVersion", appVersion)
            put("dbVersion", dbVersion)
            put("dbSha256", dbSha256)
            put("dbBytes", dbBytes)
            put("boxCount", boxCount)
            put("unsyncedShipments", unsyncedShipments)
            put("unsyncedChecks", unsyncedChecks)
            put("hasPrefs", hasPrefs)
        }.toString()

        companion object {
            fun fromJson(json: String): Manifest {
                val o = JSONObject(json)
                return Manifest(
                    format = o.optString("format", ""),
                    formatVersion = o.optInt("formatVersion", 0),
                    createdAt = o.optLong("createdAt", 0),
                    appVersion = o.optString("appVersion", ""),
                    dbVersion = o.optInt("dbVersion", 0),
                    dbSha256 = o.optString("dbSha256", ""),
                    dbBytes = o.optLong("dbBytes", 0),
                    boxCount = o.optInt("boxCount", 0),
                    unsyncedShipments = o.optInt("unsyncedShipments", 0),
                    unsyncedChecks = o.optInt("unsyncedChecks", 0),
                    hasPrefs = o.optBoolean("hasPrefs", false)
                )
            }
        }
    }

    sealed class VerifyResult {
        object Ok : VerifyResult()
        data class Invalid(val reason: String) : VerifyResult()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 打包：把 db 与可选 prefs 写入 zip，并写入带校验和的 manifest。 */
    fun write(
        outZip: File,
        dbFile: File,
        prefsFile: File?,
        manifest: Manifest
    ) {
        outZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zos.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(ENTRY_DB))
            FileInputStream(dbFile).use { it.copyTo(zos, 64 * 1024) }
            zos.closeEntry()

            if (prefsFile != null && prefsFile.exists()) {
                zos.putNextEntry(ZipEntry(ENTRY_PREFS))
                FileInputStream(prefsFile).use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            }
        }
    }

    fun readManifest(zip: File): Manifest = ZipFile(zip).use { z ->
        val e = z.getEntry(ENTRY_MANIFEST) ?: throw IllegalArgumentException("备份缺少 manifest.json")
        z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }.let { Manifest.fromJson(it) }
    }

    /** 完整校验：格式/版本、必需条目、DB 大小与 SHA-256。 */
    fun verify(zip: File): VerifyResult = runCatching {
        if (!zip.exists() || zip.length() == 0L) return VerifyResult.Invalid("备份文件为空")

        val m = readManifest(zip)
        if (m.format != FORMAT) return VerifyResult.Invalid("不是本应用的备份文件（format=${m.format}）")
        if (m.formatVersion != FORMAT_VERSION) {
            return VerifyResult.Invalid("备份格式版本不支持（${m.formatVersion}），请升级应用")
        }

        ZipFile(zip).use { z ->
            val dbEntry = z.getEntry(ENTRY_DB) ?: return VerifyResult.Invalid("备份缺少数据库文件")
            if (dbEntry.size != m.dbBytes) return VerifyResult.Invalid("数据库大小不符（备份可能被截断）")

            val md = MessageDigest.getInstance("SHA-256")
            z.getInputStream(dbEntry).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(m.dbSha256, ignoreCase = true)) {
                return VerifyResult.Invalid("数据库校验和不一致，备份已损坏")
            }
            if (m.hasPrefs && z.getEntry(ENTRY_PREFS) == null) {
                return VerifyResult.Invalid("备份缺少配置文件")
            }
        }
        VerifyResult.Ok
    }.getOrElse { VerifyResult.Invalid("备份解析失败：${it.message ?: it.javaClass.simpleName}") }

    /** 解出 DB 到目标文件（覆盖写，调用方负责先关闭数据库）。 */
    fun extractDb(zip: File, dest: File) = extract(zip, ENTRY_DB, dest)

    /** 解出 prefs；不存在时返回 false。 */
    fun extractPrefs(zip: File, dest: File): Boolean {
        val z = ZipFile(zip)
        return try {
            if (z.getEntry(ENTRY_PREFS) == null) return false
            extract(zip, ENTRY_PREFS, dest)
            true
        } finally {
            z.close()
        }
    }

    private fun extract(zip: File, entry: String, dest: File) {
        dest.parentFile?.mkdirs()
        ZipFile(zip).use { z ->
            val e = z.getEntry(entry) ?: throw IllegalArgumentException("备份缺少 $entry")
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            FileOutputStream(tmp).use { out -> z.getInputStream(e).use { it.copyTo(out, 64 * 1024) } }
            if (dest.exists() && !dest.delete()) throw IllegalStateException("无法覆盖 ${dest.name}")
            if (!tmp.renameTo(dest)) throw IllegalStateException("无法写入 ${dest.name}")
        }
    }
}
