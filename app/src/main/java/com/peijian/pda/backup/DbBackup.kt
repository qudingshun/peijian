package com.peijian.pda.backup

import android.content.Context
import android.net.Uri
import com.peijian.pda.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据库备份/恢复的 Android 层。
 *
 * 一致性关键：Room 默认 WAL 模式，备份前必须 wal_checkpoint(TRUNCATE) 把已提交事务
 * 归并进主库文件，否则只复制 .db 会丢最近写入（未上传单据正是最近写入）。
 * 恢复必须连同 SharedPreferences 一起覆盖，否则 lastRev 与库内容错位会导致增量同步漏数据。
 */
object DbBackup {

    const val DB_NAME = "peijian.db"
    const val PREFS_NAME = "peijian"          // getSharedPreferences("peijian", ...)
    const val BACKUP_SUBDIR = "备份"
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    data class ExportResult(val file: File, val manifest: BackupArchive.Manifest, val note: String = "")
    data class Prepared(val file: File, val manifest: BackupArchive.Manifest)
    data class RestoreResult(val preBackup: File?, val manifest: BackupArchive.Manifest)

    fun backupDir(context: Context): File {
        val primary = File(android.os.Environment.getExternalStorageDirectory(), "Peijian/$BACKUP_SUBDIR")
        if (runCatching { primary.mkdirs() || primary.isDirectory }.getOrDefault(false)) return primary
        val fallback = File(context.getExternalFilesDir(null), BACKUP_SUBDIR)
        runCatching { fallback.mkdirs() }
        return fallback
    }

    fun dbFile(context: Context): File = context.getDatabasePath(DB_NAME)

    fun prefsFile(context: Context): File = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")

    /** 导出备份。count 由调用方传入（DAO 查询），避免这里耦合 DAO。 */
    suspend fun export(
        context: Context,
        db: AppDatabase,
        appVersion: String,
        boxCount: Int,
        unsyncedShipments: Int,
        unsyncedChecks: Int,
        tag: String = ""
    ): ExportResult = withContext(Dispatchers.IO) {
        // 1) WAL 归并：busy!=0 说明有其他连接占用，直接失败好过产出不一致的备份
        val busy = db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
        if (busy != 0) throw IllegalStateException("数据库正忙（wal_checkpoint busy=$busy），请稍后再备份")

        val srcDb = dbFile(context)
        if (!srcDb.exists()) throw IllegalStateException("数据库文件不存在：${srcDb.absolutePath}")

        // 2) 先复制主库到缓存，计算校验和（避免打包过程中库文件被改动）
        val work = File(context.cacheDir, "backup_work_${System.currentTimeMillis()}")
        work.mkdirs()
        val dbCopy = File(work, DB_NAME)
        srcDb.copyTo(dbCopy, overwrite = true)

        val prefs = prefsFile(context)
        val hasPrefs = prefs.exists()
        val suffix = if (tag.isBlank()) "" else "_${tag}"
        val name = "peijian_backup_${stamp.format(Date())}${suffix}.zip"
        val out = File(backupDir(context), name)

        val manifest = BackupArchive.Manifest(
            format = BackupArchive.FORMAT,
            formatVersion = BackupArchive.FORMAT_VERSION,
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            dbVersion = AppDatabase.VERSION,
            dbSha256 = BackupArchive.sha256(dbCopy),
            dbBytes = dbCopy.length(),
            boxCount = boxCount,
            unsyncedShipments = unsyncedShipments,
            unsyncedChecks = unsyncedChecks,
            hasPrefs = hasPrefs
        )
        BackupArchive.write(out, dbCopy, if (hasPrefs) prefs else null, manifest)
        work.deleteRecursively()

        val note = if (out.parentFile?.absolutePath == File(context.getExternalFilesDir(null), BACKUP_SUBDIR).absolutePath) {
            "已写入 App 专属目录（外置存储不可写）"
        } else ""
        ExportResult(out, manifest, note)
    }

    /** 把用户选择的备份文件复制到缓存并校验；返回准备好的文件供确认后恢复。 */
    suspend fun prepare(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "restore_work_${System.currentTimeMillis()}")
        work.mkdirs()
        val f = File(work, "backup.zip")
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IllegalStateException("无法读取所选备份")
            File(f.absolutePath + ".tmp").outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        val tmp = File(f.absolutePath + ".tmp")
        if (!tmp.renameTo(f)) throw IllegalStateException("无法保存备份文件")
        when (val v = BackupArchive.verify(f)) {
            BackupArchive.VerifyResult.Ok -> Prepared(f, BackupArchive.readManifest(f))
            is BackupArchive.VerifyResult.Invalid -> throw IllegalArgumentException(v.reason)
        }
    }

    suspend fun discard(context: Context, prepared: Prepared) = withContext(Dispatchers.IO) {
        prepared.file.parentFile?.deleteRecursively()
    }

    /**
     * 执行恢复：先自动备份当前库 → 关闭 Room → 覆盖 DB + prefs。
     * 调用方需在返回后重启进程（Room 单例与 ViewModel 已缓存旧实例）。
     */
    suspend fun restore(
        context: Context,
        db: AppDatabase,
        prepared: Prepared,
        appVersion: String,
        boxCount: Int,
        unsyncedShipments: Int,
        unsyncedChecks: Int
    ): RestoreResult = withContext(Dispatchers.IO) {
        val verified = BackupArchive.verify(prepared.file)
        if (verified !is BackupArchive.VerifyResult.Ok) {
            throw IllegalArgumentException((verified as BackupArchive.VerifyResult.Invalid).reason)
        }
        if (prepared.manifest.dbVersion > AppDatabase.VERSION) {
            throw IllegalArgumentException("备份来自更高数据库版本（${prepared.manifest.dbVersion}），当前应用无法读取，请先升级")
        }

        // 恢复前先给当前库留一份，避免误恢复后无法回退
        val preBackup = try {
            export(context, db, appVersion, boxCount, unsyncedShipments, unsyncedChecks, tag = "恢复前").file
        } catch (e: Exception) {
            null // 自动备份失败不应阻断恢复（用户已手动确认）
        }

        // 关闭 Room：清 WAL/SHM，避免旧 WAL 污染新库
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        db.close()
        AppDatabase.reset()

        val target = dbFile(context)
        BackupArchive.extractDb(prepared.file, target)
        // 删除残留 WAL/SHM
        listOf(File(target.absolutePath + "-wal"), File(target.absolutePath + "-shm")).forEach {
            runCatching { if (it.exists()) it.delete() }
        }
        if (prepared.manifest.hasPrefs) {
            BackupArchive.extractPrefs(prepared.file, prefsFile(context))
        }

        RestoreResult(preBackup, prepared.manifest)
    }
}
