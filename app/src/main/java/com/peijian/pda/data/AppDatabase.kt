package com.peijian.pda.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
import com.peijian.pda.data.entity.StockCheckScan
import com.peijian.pda.data.entity.SyncState

@Database(
    entities = [
        PackingBox::class, PackingItem::class,
        Shipment::class, ShipmentBox::class, ShipmentItem::class,
        StockCheck::class, StockCheckScan::class,
        SyncState::class, OpLogEntry::class
    ],
    version = 1,
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

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peijian.db"
                ).build().also { INSTANCE = it }
            }
    }
}
