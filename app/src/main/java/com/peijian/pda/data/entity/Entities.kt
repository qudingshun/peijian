package com.peijian.pda.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** 箱状态 */
object BoxStatus {
    const val IN_STOCK = "IN_STOCK"   // 在库
    const val SHIPPED = "SHIPPED"     // 已发出
    const val VOID = "VOID"           // 作废（源表删除等，暂不使用）
}

/**
 * 一个箱子（生产单号 + 箱号 + 客户编码唯一）。
 * 箱子下通常 1 行配件，合并单元格导致的多行配件放在 [PackingItem]。
 */
@Entity(
    tableName = "packing_box",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index("barcodeNorm"),
        Index("status"),
        Index("orderNo"),
        Index("rev")
    ]
)
data class PackingBox(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 客户编码-生产单号-箱号，唯一键 */
    val sourceKey: String,
    val customerCode: String,
    val customerName: String,
    val salesperson: String,
    val country: String,
    val orderNo: String,
    val boxNo: String,
    /** 打印在箱子上的条码内容 */
    val barcode: String,
    /** 去分隔符大写，用于扫码匹配 */
    val barcodeNorm: String,
    val packDate: Long? = null,
    val dimension: String = "",
    val volume: Double? = null,
    val grossWeight: Double? = null,
    val packingUnit: String = "",
    val remark: String = "",
    val status: String = BoxStatus.IN_STOCK,
    val shipmentId: Long? = null,
    val shippedAt: Long? = null,
    /** 电脑端批次版本号，增量同步用 */
    val rev: Long = 0,
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceRow: Int = 0
)

@Entity(
    tableName = "packing_item",
    indices = [Index("boxId"), Index("partCode")]
)
data class PackingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val boxId: Long,
    val partCode: String,
    val englishName: String = "",
    val partName: String = "",
    val quantity: Int = 0,
    val machineModel: String = "",
    val remark: String = ""
)

/**
 * 发货单 / 出库单。扫箱登记前先建单，扫完结束。
 */
@Entity(tableName = "shipment", indices = [Index("status"), Index("uid", unique = true)])
data class Shipment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docNo: String,
    val customerCode: String = "",
    val customerName: String = "",
    val receiver: String = "",
    val operator: String = "",
    val note: String = "",
    val status: String = ShipmentStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val uid: String,
    val syncedAt: Long? = null
)

object ShipmentStatus {
    const val OPEN = "OPEN"
    const val FINISHED = "FINISHED"
    const val VOID = "VOID"
}

/** 发货单里扫过的箱（防重复、保留箱级轨迹） */
@Entity(
    tableName = "shipment_box",
    indices = [
        Index(value = ["shipmentId", "boxId"], unique = true),
        Index("shipmentId"),
        Index("barcodeNorm")
    ]
)
data class ShipmentBox(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shipmentId: Long,
    val boxId: Long,
    val barcode: String,
    val barcodeNorm: String,
    val customerCode: String,
    val customerName: String,
    val orderNo: String,
    val boxNo: String,
    val scannedAt: Long = System.currentTimeMillis(),
    val operator: String = ""
)

/** 发货时的配件快照：即使以后源表改了，历史出库数量不变 */
@Entity(tableName = "shipment_item", indices = [Index("shipmentId"), Index("partCode")])
data class ShipmentItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shipmentId: Long,
    val shipmentBoxId: Long,
    val partCode: String,
    val partName: String,
    val englishName: String,
    val machineModel: String,
    val quantity: Int
)

/** 盘点批次 */
@Entity(tableName = "stock_check", indices = [Index("status"), Index("uid", unique = true)])
data class StockCheck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchNo: String,
    val operator: String = "",
    val note: String = "",
    val status: String = StockCheckStatus.OPEN,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val uid: String,
    val syncedAt: Long? = null
)

object StockCheckStatus {
    const val OPEN = "OPEN"
    const val FINISHED = "FINISHED"
}

/** 盘点扫到的箱 */
@Entity(
    tableName = "stock_check_scan",
    indices = [
        Index(value = ["checkId", "boxId"], unique = true),
        Index("checkId"),
        Index("barcodeNorm")
    ]
)
data class StockCheckScan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val checkId: Long,
    /** null = 系统里没这个箱码（异常/盘盈） */
    val boxId: Long? = null,
    val barcode: String,
    val barcodeNorm: String,
    val orderNo: String = "",
    val boxNo: String = "",
    /** true = 系统应存且扫到；false = 系统无此箱或已发出 */
    val expected: Boolean = true,
    val scannedAt: Long = System.currentTimeMillis(),
    val operator: String = ""
)

/** 简单键值配置（与 SharedPreferences 双写，便于导出/迁移） */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val k: String,
    val v: String
)

/** 操作日志（只用于排查问题，不参与业务） */
@Entity(tableName = "op_log", indices = [Index("at")])
data class OpLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val tag: String,
    val message: String
)

/** 箱 + 箱内配件 */
data class BoxWithItems(
    @Embedded val box: PackingBox,
    @Relation(parentColumn = "id", entityColumn = "boxId")
    val items: List<PackingItem>
)
