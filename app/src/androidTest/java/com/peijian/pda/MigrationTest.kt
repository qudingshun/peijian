package com.peijian.pda

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peijian.pda.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v1 → v2 真机迁移测试：验证 `MIGRATION_1_2` 建表、索引与历史盘点回填。
 *
 * 运行：连接设备后 `.\gradlew.bat connectedDebugAndroidTest`
 * （`app/schemas` 已作为 androidTest assets，供 MigrationTestHelper 校验 v1/v2 结构）
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_createsSnapshotAndBackfillsKnownBoxes() {
        // 构造 v1 数据：两个已知箱的明细 + 两次扫描（其中一次箱不存在，boxId=NULL）
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO packing_item(id,boxId,partCode,englishName,partName,quantity,machineModel,remark) " +
                    "VALUES(1,10,'PART-A','en-A','配件A',5,'M1','')"
            )
            db.execSQL(
                "INSERT INTO packing_item(id,boxId,partCode,englishName,partName,quantity,machineModel,remark) " +
                    "VALUES(2,20,'PART-B','en-B','配件B',7,'M2','')"
            )
            db.execSQL(
                "INSERT INTO stock_check(id,batchNo,operator,note,status,startedAt,finishedAt,uid,syncedAt) " +
                    "VALUES(1,'B1','','','OPEN',1000,NULL,'u1',NULL)"
            )
            db.execSQL(
                "INSERT INTO stock_check_scan(id,checkId,boxId,barcode,barcodeNorm,orderNo,boxNo,expected,scannedAt,operator) " +
                    "VALUES(1,1,10,'W-P-1','WP1','P1','1',1,1100,'')"
            )
            db.execSQL(
                "INSERT INTO stock_check_scan(id,checkId,boxId,barcode,barcodeNorm,orderNo,boxNo,expected,scannedAt,operator) " +
                    "VALUES(2,1,20,'W-P-2','WP2','P2','2',1,1200,'')"
            )
            db.execSQL(
                "INSERT INTO stock_check_scan(id,checkId,boxId,barcode,barcodeNorm,orderNo,boxNo,expected,scannedAt,operator) " +
                    "VALUES(3,1,NULL,'UNKNOWN','UNKNOWN','','',0,1300,'')"
            )
        }

        // runMigrationsAndValidate 同时校验 v2 的表结构/索引与 Room 期望完全一致
        val db = helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT scanId, checkId, boxId, partCode, quantity FROM stock_check_item ORDER BY scanId").use { c ->
            assertEquals("未知箱不应回填", 2, c.count)
            c.moveToFirst()
            assertEquals(1, c.getLong(0))
            assertEquals(1, c.getLong(1))
            assertEquals(10L, c.getLong(2))
            assertEquals("PART-A", c.getString(3))
            assertEquals(5, c.getInt(4))
            c.moveToNext()
            assertEquals(2, c.getLong(0))
            assertEquals("PART-B", c.getString(3))
            assertEquals(7, c.getInt(4))
        }
        db.query("SELECT COUNT(*) FROM stock_check WHERE id=1").use { c ->
            c.moveToFirst()
            assertEquals("迁移不应影响原盘点单", 1, c.getInt(0))
        }
        db.query("SELECT boxId FROM stock_check_scan WHERE id=3").use { c ->
            c.moveToFirst()
            assertEquals("boxId 为空的历史扫描保持为空", true, c.isNull(0))
        }
        db.close()
    }

    @Test
    fun migrate1To2_handlesEmptyDatabase() {
        helper.createDatabase(DB, 1).close()
        val db = helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM stock_check_item").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3_createsPlanBoxTable() {
        helper.createDatabase(DB, 1).close()
        // 先落到 v2，再走 v2→v3；runMigrationsAndValidate 会按 schema 校验最终结构
        helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2).close()
        val db = helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        db.query("SELECT COUNT(*) FROM shipment_plan_box").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.execSQL(
            "INSERT INTO shipment_plan_box(shipmentId,boxId,barcode,orderNo,boxNo,customerCode,customerName,partSummary,totalQty,addedAt) " +
                "VALUES(1,10,'BAR','P1','1','W1','客户','PART 配件',5,1000)"
        )
        db.query("SELECT barcode, totalQty FROM shipment_plan_box").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("BAR", c.getString(0))
            assertEquals(5, c.getInt(1))
        }
        db.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
