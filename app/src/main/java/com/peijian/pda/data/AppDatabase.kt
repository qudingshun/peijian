package com.peijian.pda.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.peijian.pda.data.dao.MiscDao
import com.peijian.pda.data.dao.PackingDao
import com.peijian.pda.data.dao.ShipmentDao
import com.peijian.pda.data.dao.StockCheckDao
import com.peijian.pda.data.entity.OpLogEntry
import com.peijian.pda.data.entity.PackingBox
import com.peijian.pda.data.entity.PackingItem
import com.peijian.pda.data.entity.Shipment
import com.peijian.pda.data.entity.ShipmentBox
import com.peijian.pda.data.entity.ShipmentItem
import com.peijian.pda.data.entity.StockCheck
import com.peijian.pda.data.entity.StockCheckItem
import com.peijian.pda.data.entity.StockCheckScan
import com.peijian.pda.data.entity.SyncState

@Database(
    entities = [
        PackingBox::class, PackingItem::class,
        Shipment::class, ShipmentBox::class, ShipmentItem::class,
        StockCheck::class, StockCheckScan::class, StockCheckItem::class,
        SyncState::class, OpLogEntry::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun packingDao(): PackingDao
    abstract fun shipmentDao(): ShipmentDao
    abstract fun stockCheckDao(): StockCheckDao
    abstract fun miscDao(): MiscDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2：新增 stock_check_item 快照表。
         *
         * 历史盘点没有快照，只能用当前 packing_item 回填一份近似值（源表若已改动，
         * 历史报表仍可能不准，但这是唯一可行的兜底）；新扫描从 v2 起写入真实快照。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stock_check_item` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`scanId` INTEGER NOT NULL, " +
                        "`checkId` INTEGER NOT NULL, " +
                        "`boxId` INTEGER, " +
                        "`partCode` TEXT NOT NULL, " +
                        "`englishName` TEXT NOT NULL DEFAULT '', " +
                        "`partName` TEXT NOT NULL DEFAULT '', " +
                        "`quantity` INTEGER NOT NULL DEFAULT 0, " +
                        "`machineModel` TEXT NOT NULL DEFAULT '', " +
                        "`remark` TEXT NOT NULL DEFAULT ''" +
                        ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_check_item_scanId` ON `stock_check_item` (`scanId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_check_item_checkId` ON `stock_check_item` (`checkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_check_item_boxId` ON `stock_check_item` (`boxId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_check_item_partCode` ON `stock_check_item` (`partCode`)")
                // 用当前明细回填历史扫描（best-effort 近似）
                db.execSQL(
                    "INSERT INTO `stock_check_item` " +
                        "(`scanId`,`checkId`,`boxId`,`partCode`,`englishName`,`partName`,`quantity`,`machineModel`,`remark`) " +
                        "SELECT s.id, s.checkId, i.boxId, i.partCode, i.englishName, i.partName, " +
                        "i.quantity, i.machineModel, i.remark " +
                        "FROM `stock_check_scan` s JOIN `packing_item` i ON i.boxId = s.boxId " +
                        "WHERE s.boxId IS NOT NULL"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peijian.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
