package com.peijian.pda

import com.peijian.pda.backup.BackupArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 备份归档的 JVM 回归测试：验证打包、校验和、篡改检测、还原一致性。
 * 不依赖 Android，纯文件/zip 操作。
 */
class BackupArchiveTest {

    private fun tempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "peijian-backup-test-$tag-${System.nanoTime()}").apply { mkdirs() }

    private fun fixture(dir: File): Triple<File, File, BackupArchive.Manifest> {
        val db = File(dir, "peijian.db").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val prefs = File(dir, "peijian.xml").apply { writeText("<map><string name=\"last_rev\">12</string></map>") }
        val manifest = BackupArchive.Manifest(
            format = BackupArchive.FORMAT,
            formatVersion = BackupArchive.FORMAT_VERSION,
            createdAt = 1730000000000L,
            appVersion = "0.1.0",
            dbVersion = 2,
            dbSha256 = BackupArchive.sha256(db),
            dbBytes = db.length(),
            boxCount = 20929,
            unsyncedShipments = 3,
            unsyncedChecks = 1,
            hasPrefs = true
        )
        return Triple(db, prefs, manifest)
    }

    @Test
    fun write_then_verify_ok_and_manifest_roundtrip() {
        val dir = tempDir("ok")
        val (db, prefs, manifest) = fixture(dir)
        val zip = File(dir, "backup.zip")

        BackupArchive.write(zip, db, prefs, manifest)

        assertTrue(BackupArchive.verify(zip) is BackupArchive.VerifyResult.Ok)
        val read = BackupArchive.readManifest(zip)
        assertEquals(manifest.dbSha256, read.dbSha256)
        assertEquals(manifest.dbBytes, read.dbBytes)
        assertEquals(20929, read.boxCount)
        assertEquals(3, read.unsyncedShipments)
        assertEquals(2, read.dbVersion)
        assertTrue(read.hasPrefs)
    }

    @Test
    fun extract_restores_byte_identical_db_and_prefs() {
        val dir = tempDir("extract")
        val (db, prefs, manifest) = fixture(dir)
        val zip = File(dir, "backup.zip")
        BackupArchive.write(zip, db, prefs, manifest)

        val outDb = File(dir, "out/peijian.db")
        val outPrefs = File(dir, "out/peijian.xml")
        BackupArchive.extractDb(zip, outDb)
        assertTrue(BackupArchive.extractPrefs(zip, outPrefs))

        assertTrue("db 字节应完全一致", db.readBytes().contentEquals(outDb.readBytes()))
        assertTrue("prefs 内容应一致", prefs.readText() == outPrefs.readText())
    }

    @Test
    fun tampered_db_is_rejected() {
        val dir = tempDir("tamper")
        val (db, prefs, manifest) = fixture(dir)
        val zip = File(dir, "backup.zip")
        BackupArchive.write(zip, db, prefs, manifest)

        // 直接改写 zip 内数据库条目的一个字节（长度不变，大小校验仍过，但 sha256 必失败）
        ZipFile(zip).use { z ->
            val entry = z.getEntry(BackupArchive.ENTRY_DB)
            val raw = z.getInputStream(entry).readBytes()
            raw[0] = (raw[0].toInt() xor 0xFF).toByte()
            val patched = File(dir, "patched.zip")
            java.util.zip.ZipOutputStream(patched.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry(BackupArchive.ENTRY_MANIFEST))
                zos.write(z.getInputStream(z.getEntry(BackupArchive.ENTRY_MANIFEST)).readBytes())
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry(BackupArchive.ENTRY_DB))
                zos.write(raw)
                zos.closeEntry()
            }
            val v = BackupArchive.verify(patched)
            assertTrue("篡改后应为 Invalid，实际=$v", v is BackupArchive.VerifyResult.Invalid)
            assertTrue((v as BackupArchive.VerifyResult.Invalid).reason.contains("校验和"))
        }
    }

    @Test
    fun missing_manifest_is_rejected() {
        val dir = tempDir("nomani")
        val zip = File(dir, "backup.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry(BackupArchive.ENTRY_DB))
            zos.write(ByteArray(16))
            zos.closeEntry()
        }
        val v = BackupArchive.verify(zip)
        assertTrue(v is BackupArchive.VerifyResult.Invalid)
        assertTrue((v as BackupArchive.VerifyResult.Invalid).reason.contains("manifest"))
    }

    @Test
    fun wrong_format_and_truncated_zip_are_rejected() {
        val dir = tempDir("wrong")
        val (db, prefs, _) = fixture(dir)
        val wrongManifest = BackupArchive.Manifest(
            format = "other-app", formatVersion = 1, createdAt = 0, appVersion = "x",
            dbVersion = 2, dbSha256 = BackupArchive.sha256(db), dbBytes = db.length(),
            boxCount = 0, unsyncedShipments = 0, unsyncedChecks = 0, hasPrefs = true
        )
        val zip = File(dir, "backup.zip")
        BackupArchive.write(zip, db, prefs, wrongManifest)
        val v = BackupArchive.verify(zip)
        assertTrue(v is BackupArchive.VerifyResult.Invalid)
        assertTrue((v as BackupArchive.VerifyResult.Invalid).reason.contains("不是本应用"))

        val empty = File(dir, "empty.zip").apply { writeBytes(byteArrayOf()) }
        assertFalse(BackupArchive.verify(empty) is BackupArchive.VerifyResult.Ok)
    }
}
