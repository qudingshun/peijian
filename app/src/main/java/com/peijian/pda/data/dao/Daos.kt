package com.peijian.pda.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.peijian.pda.data.entity.BoxWithItems
import com.peijian.pda.data.entity.OpLogEntry
import com.peijian.pda.data.entity.PackingBox
import com.peijian.pda.data.entity.PackingItem
import com.peijian.pda.data.entity.Shipment
import com.peijian.pda.data.entity.ShipmentBox
import com.peijian.pda.data.entity.ShipmentItem
import com.peijian.pda.data.entity.StockCheck
import com.peijian.pda.data.entity.StockCheckScan
import com.peijian.pda.data.entity.SyncState
import kotlinx.coroutines.flow.Flow

/* ---------------- 查询结果行 ---------------- */

data class InventoryRow(
    val boxId: Long,
    val packDate: Long?,
    val customerCode: String,
    val customerName: String,
    val salesperson: String,
    val country: String,
    val orderNo: String,
    val boxNo: String,
    val dimension: String,
    val volume: Double?,
    val grossWeight: Double?,
    val packingUnit: String,
    val boxRemark: String,
    val partCode: String,
    val englishName: String,
    val partName: String,
    val quantity: Int,
    val machineModel: String,
    val itemRemark: String
)

data class SummaryRow(
    val partCode: String,
    val partName: String,
    val englishName: String,
    val machineModel: String,
    val totalQty: Long,
    val boxCount: Long,
    val itemLines: Long
)

data class BoxListRow(
    val id: Long,
    val barcode: String,
    val customerCode: String,
    val customerName: String,
    val orderNo: String,
    val boxNo: String,
    val status: String,
    val packDate: Long?,
    val partSummary: String,
    val totalQty: Long
)

/** 对账上报用的轻量箱摘要：只取归一化条码、版本、状态 */
data class BoxDigest(
    val barcodeNorm: String,
    val rev: Long,
    val status: String
)

data class ShipmentListRow(
    val id: Long,
    val docNo: String,
    val customerCode: String,
    val customerName: String,
    val receiver: String,
    val operator: String,
    val status: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val boxCount: Long,
    val totalQty: Long,
    val syncedAt: Long?
)

data class ShipmentRow(
    val shipmentBoxId: Long,
    val barcode: String,
    val orderNo: String,
    val boxNo: String,
    val customerCode: String,
    val customerName: String,
    val scannedAt: Long,
    val itemSummary: String,
    val totalQty: Long
)

data class ShipmentDetailRow(
    val docNo: String,
    val createdAt: Long,
    val customerCode: String,
    val customerName: String,
    val receiver: String,
    val orderNo: String,
    val boxNo: String,
    val barcode: String,
    val partCode: String,
    val partName: String,
    val englishName: String,
    val machineModel: String,
    val quantity: Int,
    val operator: String
)

data class StockCheckListRow(
    val id: Long,
    val batchNo: String,
    val operator: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val scanCount: Long,
    val syncedAt: Long?
)

data class StockScanRow(
    val id: Long,
    val barcode: String,
    val orderNo: String,
    val boxNo: String,
    val expected: Boolean,
    val scannedAt: Long,
    val partSummary: String,
    val totalQty: Long
)

data class PartQtyRow(
    val partCode: String,
    val partName: String,
    val totalQty: Long
)

data class ScanMatch(
    val box: PackingBox,
    val items: List<PackingItem>
)

/* ---------------- 打包数据 ---------------- */

@Dao
interface PackingDao {

    /* 统计 */
    @Query("SELECT COUNT(*) FROM packing_box WHERE status='IN_STOCK'")
    fun observeStockBoxCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM packing_box WHERE status='SHIPPED'")
    fun observeShippedBoxCount(): Flow<Int>

    @Query(
        "SELECT COUNT(DISTINCT i.partCode) FROM packing_item i " +
            "JOIN packing_box b ON b.id=i.boxId WHERE b.status='IN_STOCK'"
    )
    fun observeStockPartCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(i.quantity),0) FROM packing_item i " +
            "JOIN packing_box b ON b.id=i.boxId WHERE b.status='IN_STOCK'"
    )
    fun observeStockQty(): Flow<Long>

    @Query("SELECT COUNT(*) FROM packing_box")
    fun observeBoxCount(): Flow<Int>

    @Query("SELECT MAX(rev) FROM packing_box")
    suspend fun maxRev(): Long?

    @Query("SELECT COUNT(*) FROM packing_box WHERE rev > :rev")
    suspend fun countNewerThan(rev: Long): Int

    /* 单箱 */
    @Insert
    suspend fun insertBox(box: PackingBox): Long

    @Update
    suspend fun updateBox(box: PackingBox)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PackingItem>)

    @Query("DELETE FROM packing_item WHERE boxId=:boxId")
    suspend fun deleteItems(boxId: Long)

    @Query("SELECT * FROM packing_box WHERE sourceKey=:key LIMIT 1")
    suspend fun findBySourceKey(key: String): PackingBox?

    @Query("SELECT * FROM packing_box WHERE id=:id")
    suspend fun findById(id: Long): PackingBox?

    @Transaction
    @Query("SELECT * FROM packing_box WHERE id=:id")
    suspend fun boxWithItems(id: Long): BoxWithItems?

    @Query("SELECT * FROM packing_item WHERE boxId=:boxId ORDER BY id")
    suspend fun itemsOfBox(boxId: Long): List<PackingItem>

    /**
     * 精确匹配归一化箱码。理论上唯一，返回多条即为箱码冲突，交由业务层提示用户。
     */
    @Query("SELECT * FROM packing_box WHERE barcodeNorm=:norm LIMIT 2")
    suspend fun findExactByNorm(norm: String): List<PackingBox>

    /**
     * 前缀拼接匹配：扫码串以库内箱码结尾（例如扫了 "XXW00305P108580-1"）。
     *
     * 刻意不做反向 LIKE（"barcodeNorm LIKE '%'||:norm"）：短码 "1" / "801" 会命中
     * 大量库内箱码，配合 LIMIT 2 与「唯一在库即取」会静默错箱出库。
     */
    @Query(
        "SELECT * FROM packing_box WHERE :norm LIKE '%'||barcodeNorm " +
            "ORDER BY LENGTH(barcodeNorm) DESC LIMIT 2"
    )
    suspend fun matchBarcodeTail(norm: String): List<PackingBox>

    @Query("SELECT * FROM packing_box WHERE orderNo=:orderNo AND boxNo=:boxNo LIMIT 2")
    suspend fun matchOrderBox(orderNo: String, boxNo: String): List<PackingBox>

    /* 列表 / 搜索 */
    @Query(
        "SELECT b.id AS id, b.barcode AS barcode, b.customerCode AS customerCode, " +
            "b.customerName AS customerName, b.orderNo AS orderNo, b.boxNo AS boxNo, " +
            "b.status AS status, b.packDate AS packDate, " +
            "COALESCE((SELECT GROUP_CONCAT(i.partCode || ' ' || i.partName) FROM packing_item i WHERE i.boxId=b.id),'') AS partSummary, " +
            "COALESCE((SELECT SUM(i.quantity) FROM packing_item i WHERE i.boxId=b.id),0) AS totalQty " +
            "FROM packing_box b " +
            "WHERE (:status='' OR b.status=:status) AND (" +
            ":q='' OR b.orderNo LIKE '%'||:q||'%' OR b.barcodeNorm LIKE '%'||:q||'%' " +
            "OR b.customerName LIKE '%'||:q||'%' OR b.boxNo=:q " +
            "OR EXISTS(SELECT 1 FROM packing_item i WHERE i.boxId=b.id AND " +
            "(i.partCode LIKE '%'||:q||'%' OR i.partName LIKE '%'||:q||'%'))) " +
            "ORDER BY b.packDate DESC, b.orderNo DESC, b.boxNo+0, b.boxNo LIMIT :limit"
    )
    fun observeBoxes(status: String, q: String, limit: Int): Flow<List<BoxListRow>>

    @Query(
        "SELECT COUNT(*) FROM packing_box WHERE status=:status AND importedAt >= :since"
    )
    fun observeImportedSince(status: String, since: Long): Flow<Int>

    /* 库存清单（在库，打包记录格式，一行 = 一个箱内一种配件） */
    @Query(
        "SELECT b.id AS boxId, b.packDate AS packDate, b.customerCode AS customerCode, " +
            "b.customerName AS customerName, b.salesperson AS salesperson, b.country AS country, " +
            "b.orderNo AS orderNo, b.boxNo AS boxNo, b.dimension AS dimension, b.volume AS volume, " +
            "b.grossWeight AS grossWeight, b.packingUnit AS packingUnit, b.remark AS boxRemark, " +
            "i.partCode AS partCode, i.englishName AS englishName, i.partName AS partName, " +
            "i.quantity AS quantity, i.machineModel AS machineModel, i.remark AS itemRemark " +
            "FROM packing_box b JOIN packing_item i ON i.boxId=b.id " +
            "WHERE b.status='IN_STOCK' ORDER BY b.orderNo, b.boxNo+0, b.boxNo, i.id"
    )
    suspend fun inventoryRows(): List<InventoryRow>

    @Query(
        "SELECT b.id AS boxId, b.packDate AS packDate, b.customerCode AS customerCode, " +
            "b.customerName AS customerName, b.salesperson AS salesperson, b.country AS country, " +
            "b.orderNo AS orderNo, b.boxNo AS boxNo, b.dimension AS dimension, b.volume AS volume, " +
            "b.grossWeight AS grossWeight, b.packingUnit AS packingUnit, b.remark AS boxRemark, " +
            "i.partCode AS partCode, i.englishName AS englishName, i.partName AS partName, " +
            "i.quantity AS quantity, i.machineModel AS machineModel, i.remark AS itemRemark " +
            "FROM packing_box b JOIN packing_item i ON i.boxId=b.id " +
            "WHERE b.id=:boxId ORDER BY i.id"
    )
    suspend fun boxRows(boxId: Long): List<InventoryRow>

    /* 物料汇总（在库） */
    @Query(
        "SELECT i.partCode AS partCode, MAX(i.partName) AS partName, MAX(i.englishName) AS englishName, " +
            "MAX(i.machineModel) AS machineModel, SUM(i.quantity) AS totalQty, " +
            "COUNT(DISTINCT b.id) AS boxCount, COUNT(*) AS itemLines " +
            "FROM packing_box b JOIN packing_item i ON i.boxId=b.id " +
            "WHERE b.status='IN_STOCK' GROUP BY i.partCode ORDER BY i.partCode"
    )
    suspend fun summaryRows(): List<SummaryRow>

    /* 发货 / 盘点用 */

    /** 该箱是否仍有未上传的出库事件（OPEN 或 FINISHED 但尚未同步的发货单） */
    @Query(
        "SELECT COUNT(*) FROM shipment_box sb JOIN shipment s ON s.id=sb.shipmentId " +
            "WHERE sb.boxId=:boxId AND s.syncedAt IS NULL AND s.status IN ('OPEN','FINISHED')"
    )
    suspend fun hasLocalOutbound(boxId: Long): Int

    @Query("UPDATE packing_box SET status='SHIPPED', shipmentId=:shipmentId, shippedAt=:at, updatedAt=:at WHERE id=:boxId")
    suspend fun markShipped(boxId: Long, shipmentId: Long, at: Long)

    @Query("UPDATE packing_box SET status='IN_STOCK', shipmentId=NULL, shippedAt=NULL, updatedAt=:at WHERE id=:boxId")
    suspend fun markInStock(boxId: Long, at: Long)

    /** 对账发现服务端已删行时把本地箱置为作废（软删除，保留轨迹） */
    @Query("UPDATE packing_box SET status='VOID', updatedAt=:at WHERE barcodeNorm=:norm")
    suspend fun markVoidByNorm(norm: String, at: Long)

    @Query("SELECT COUNT(*) FROM packing_box WHERE status='IN_STOCK'")
    suspend fun stockBoxCount(): Int

    /** 对账用：全量箱摘要，只投影三列，避免把 2 万箱完整对象读进内存 */
    @Query("SELECT barcodeNorm, rev, status FROM packing_box")
    suspend fun allDigests(): List<BoxDigest>

    @Query("SELECT COUNT(*) FROM packing_box WHERE status='IN_STOCK' AND importedAt>=:since")
    suspend fun newStockSince(since: Long): Int

    @Query("SELECT * FROM packing_box WHERE status='IN_STOCK'")
    suspend fun allInStockBoxes(): List<PackingBox>

    @Query("SELECT * FROM packing_box WHERE status='IN_STOCK' AND id NOT IN " +
        "(SELECT boxId FROM stock_check_scan WHERE checkId=:checkId AND boxId IS NOT NULL)")
    suspend fun missingBoxes(checkId: Long): List<PackingBox>

    @Query(
        "SELECT i.partCode AS partCode, MAX(i.partName) AS partName, SUM(i.quantity) AS totalQty " +
            "FROM packing_box b JOIN packing_item i ON i.boxId=b.id " +
            "WHERE b.status='IN_STOCK' GROUP BY i.partCode"
    )
    suspend fun systemStockByPart(): List<PartQtyRow>
}

/* ---------------- 发货单 ---------------- */

@Dao
interface ShipmentDao {

    @Insert
    suspend fun insertShipment(s: Shipment): Long

    @Update
    suspend fun updateShipment(s: Shipment)

    @Query("SELECT * FROM shipment WHERE id=:id")
    suspend fun findById(id: Long): Shipment?

    @Query("SELECT * FROM shipment WHERE status='OPEN' ORDER BY createdAt DESC LIMIT 1")
    suspend fun openShipment(): Shipment?

    @Insert
    suspend fun insertShipmentBox(b: ShipmentBox): Long

    @Insert
    suspend fun insertShipmentItems(items: List<ShipmentItem>)

    @Query("SELECT * FROM shipment_box WHERE shipmentId=:sid AND boxId=:boxId LIMIT 1")
    suspend fun findBox(sid: Long, boxId: Long): ShipmentBox?

    @Query(
        "SELECT sb.id AS shipmentBoxId, sb.barcode AS barcode, sb.orderNo AS orderNo, " +
            "sb.boxNo AS boxNo, sb.customerCode AS customerCode, sb.customerName AS customerName, " +
            "sb.scannedAt AS scannedAt, " +
            "COALESCE((SELECT GROUP_CONCAT(i.partCode || ' ' || i.quantity) FROM shipment_item i WHERE i.shipmentBoxId=sb.id),'') AS itemSummary, " +
            "COALESCE((SELECT SUM(i.quantity) FROM shipment_item i WHERE i.shipmentBoxId=sb.id),0) AS totalQty " +
            "FROM shipment_box sb WHERE sb.shipmentId=:sid ORDER BY sb.scannedAt DESC"
    )
    fun observeShipmentBoxes(sid: Long): Flow<List<ShipmentRow>>

    @Query(
        "SELECT s.id AS id, s.docNo AS docNo, s.customerCode AS customerCode, " +
            "s.customerName AS customerName, s.receiver AS receiver, s.operator AS operator, " +
            "s.status AS status, s.createdAt AS createdAt, s.finishedAt AS finishedAt, s.syncedAt AS syncedAt, " +
            "(SELECT COUNT(*) FROM shipment_box b WHERE b.shipmentId=s.id) AS boxCount, " +
            "(SELECT COALESCE(SUM(i.quantity),0) FROM shipment_item i WHERE i.shipmentId=s.id) AS totalQty " +
            "FROM shipment s ORDER BY s.createdAt DESC LIMIT :limit"
    )
    fun observeShipments(limit: Int): Flow<List<ShipmentListRow>>

    @Query("SELECT COALESCE(SUM(quantity),0) FROM shipment_item WHERE shipmentId=:sid")
    suspend fun totalQty(sid: Long): Long

    @Query("SELECT COUNT(*) FROM shipment_box WHERE shipmentId=:sid")
    suspend fun boxCount(sid: Long): Int

    @Query("SELECT COUNT(*) FROM shipment WHERE createdAt>=:since AND status='FINISHED'")
    fun observeFinishedSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM shipment WHERE syncedAt IS NULL AND status='FINISHED'")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT * FROM shipment WHERE syncedAt IS NULL AND status='FINISHED' ORDER BY finishedAt LIMIT 20")
    suspend fun unsynced(): List<Shipment>

    @Query("UPDATE shipment SET syncedAt=:at WHERE id=:id")
    suspend fun markSynced(id: Long, at: Long)

    @Query(
        "SELECT s.docNo AS docNo, s.createdAt AS createdAt, s.customerCode AS customerCode, " +
            "s.customerName AS customerName, s.receiver AS receiver, sb.orderNo AS orderNo, " +
            "sb.boxNo AS boxNo, sb.barcode AS barcode, i.partCode AS partCode, i.partName AS partName, " +
            "i.englishName AS englishName, i.machineModel AS machineModel, i.quantity AS quantity, " +
            "s.operator AS operator " +
            "FROM shipment s JOIN shipment_box sb ON sb.shipmentId=s.id " +
            "JOIN shipment_item i ON i.shipmentBoxId=sb.id " +
            "WHERE (:shipmentId=0 OR s.id=:shipmentId) " +
            "ORDER BY s.createdAt, sb.scannedAt, i.id"
    )
    suspend fun detailRows(shipmentId: Long): List<ShipmentDetailRow>

    @Query(
        "SELECT i.partCode AS partCode, MAX(i.partName) AS partName, SUM(i.quantity) AS totalQty " +
            "FROM shipment_item i WHERE i.shipmentId=:sid GROUP BY i.partCode ORDER BY i.partCode"
    )
    suspend fun summaryOfShipment(sid: Long): List<PartQtyRow>

    @Query("SELECT COUNT(*) FROM shipment WHERE status='FINISHED'")
    suspend fun finishedCount(): Int

    /* 取消 / 删除单箱 */
    @Query("SELECT boxId FROM shipment_box WHERE shipmentId=:sid")
    suspend fun boxIdsOf(sid: Long): List<Long>

    @Query("DELETE FROM shipment_item WHERE shipmentId=:sid")
    suspend fun deleteItemsOf(sid: Long)

    @Query("DELETE FROM shipment_box WHERE shipmentId=:sid")
    suspend fun deleteBoxesOf(sid: Long)

    @Query("DELETE FROM shipment WHERE id=:id")
    suspend fun deleteShipment(id: Long)

    @Query("SELECT * FROM shipment_box WHERE id=:id")
    suspend fun findShipmentBox(id: Long): ShipmentBox?

    @Query("DELETE FROM shipment_item WHERE shipmentBoxId=:shipmentBoxId")
    suspend fun deleteItemsOfBox(shipmentBoxId: Long)

    @Query("DELETE FROM shipment_box WHERE id=:id")
    suspend fun deleteShipmentBox(id: Long)

    @Query("UPDATE packing_box SET status='IN_STOCK', shipmentId=NULL, shippedAt=NULL, updatedAt=:at WHERE shipmentId=:sid")
    suspend fun revertBoxesOf(sid: Long, at: Long)

    @Query("SELECT * FROM shipment_box WHERE shipmentId=:sid")
    suspend fun boxesOf(sid: Long): List<ShipmentBox>

    @Query("SELECT * FROM shipment_item WHERE shipmentBoxId=:shipmentBoxId")
    suspend fun itemsOfShipmentBox(shipmentBoxId: Long): List<ShipmentItem>
}

/* ---------------- 盘点 ---------------- */

@Dao
interface StockCheckDao {

    @Insert
    suspend fun insertCheck(c: StockCheck): Long

    @Update
    suspend fun updateCheck(c: StockCheck)

    @Query("SELECT * FROM stock_check WHERE id=:id")
    suspend fun findById(id: Long): StockCheck?

    @Query("SELECT * FROM stock_check WHERE status='OPEN' ORDER BY startedAt DESC LIMIT 1")
    suspend fun openCheck(): StockCheck?

    @Insert
    suspend fun insertScan(s: StockCheckScan): Long

    @Query("SELECT * FROM stock_check_scan WHERE checkId=:cid AND boxId=:boxId LIMIT 1")
    suspend fun findScan(cid: Long, boxId: Long): StockCheckScan?

    @Query("SELECT COUNT(*) FROM stock_check_scan WHERE checkId=:cid")
    fun observeScanCount(cid: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM stock_check_scan s WHERE s.checkId=:cid AND s.expected=1"
    )
    fun observeMatchedCount(cid: Long): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(i.quantity),0) FROM stock_check_scan s " +
            "JOIN packing_item i ON i.boxId=s.boxId WHERE s.checkId=:cid AND s.expected=1"
    )
    fun observeScannedQty(cid: Long): Flow<Long>

    @Query(
        "SELECT s.id AS id, s.barcode AS barcode, s.orderNo AS orderNo, s.boxNo AS boxNo, " +
            "s.expected AS expected, s.scannedAt AS scannedAt, " +
            "COALESCE((SELECT GROUP_CONCAT(i.partCode || ' ' || i.quantity) FROM packing_item i WHERE i.boxId=s.boxId),'') AS partSummary, " +
            "COALESCE((SELECT SUM(i.quantity) FROM packing_item i WHERE i.boxId=s.boxId),0) AS totalQty " +
            "FROM stock_check_scan s WHERE s.checkId=:cid ORDER BY s.scannedAt DESC LIMIT :limit"
    )
    fun observeScans(cid: Long, limit: Int): Flow<List<StockScanRow>>

    @Query(
        "SELECT s.id AS id, s.barcode AS barcode, s.orderNo AS orderNo, s.boxNo AS boxNo, " +
            "s.expected AS expected, s.scannedAt AS scannedAt, " +
            "COALESCE((SELECT GROUP_CONCAT(i.partCode || ' ' || i.quantity) FROM packing_item i WHERE i.boxId=s.boxId),'') AS partSummary, " +
            "COALESCE((SELECT SUM(i.quantity) FROM packing_item i WHERE i.boxId=s.boxId),0) AS totalQty " +
            "FROM stock_check_scan s WHERE s.checkId=:cid ORDER BY s.expected ASC, s.id"
    )
    suspend fun scans(cid: Long): List<StockScanRow>

    @Query(
        "SELECT s.id AS id, s.batchNo AS batchNo, s.operator AS operator, s.status AS status, " +
            "s.startedAt AS startedAt, s.finishedAt AS finishedAt, s.syncedAt AS syncedAt, " +
            "(SELECT COUNT(*) FROM stock_check_scan x WHERE x.checkId=s.id) AS scanCount " +
            "FROM stock_check s ORDER BY s.startedAt DESC LIMIT :limit"
    )
    fun observeChecks(limit: Int): Flow<List<StockCheckListRow>>

    @Query("SELECT COUNT(*) FROM stock_check WHERE syncedAt IS NULL AND status='FINISHED'")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT * FROM stock_check WHERE syncedAt IS NULL AND status='FINISHED' ORDER BY finishedAt LIMIT 20")
    suspend fun unsynced(): List<StockCheck>

    @Query("UPDATE stock_check SET syncedAt=:at WHERE id=:id")
    suspend fun markSynced(id: Long, at: Long)

    @Query(
        "SELECT i.partCode AS partCode, MAX(i.partName) AS partName, SUM(i.quantity) AS totalQty " +
            "FROM stock_check_scan s JOIN packing_item i ON i.boxId=s.boxId " +
            "WHERE s.checkId=:cid AND s.expected=1 GROUP BY i.partCode"
    )
    suspend fun scannedStockByPart(cid: Long): List<PartQtyRow>

    @Query(
        "SELECT i.partCode AS partCode, MAX(i.partName) AS partName, SUM(i.quantity) AS totalQty " +
            "FROM stock_check_scan s JOIN packing_item i ON i.boxId=s.boxId " +
            "WHERE s.checkId=:cid AND s.expected=0 GROUP BY i.partCode"
    )
    suspend fun extraStockByPart(cid: Long): List<PartQtyRow>

    @Query("SELECT COUNT(*) FROM stock_check_scan WHERE checkId=:cid AND boxId IS NULL")
    suspend fun unknownScanCount(cid: Long): Int

    /** 上传用：返回实体而不是展示行 */
    @Query("SELECT * FROM stock_check_scan WHERE checkId=:cid ORDER BY expected ASC, id")
    suspend fun scanEntities(cid: Long): List<StockCheckScan>

    @Query("SELECT COUNT(*) FROM stock_check_scan WHERE checkId=:cid AND expected=1")
    suspend fun matchedCount(cid: Long): Int

    @Query("SELECT COUNT(*) FROM stock_check_scan WHERE checkId=:cid AND expected=0")
    suspend fun extraCount(cid: Long): Int

    @Query("DELETE FROM stock_check_scan WHERE checkId=:cid")
    suspend fun deleteScans(cid: Long)

    @Query("DELETE FROM stock_check WHERE id=:cid")
    suspend fun deleteCheck(cid: Long)
}

/* ---------------- 配置 / 日志 ---------------- */

@Dao
interface MiscDao {

    @Query("SELECT v FROM sync_state WHERE k=:k")
    suspend fun get(k: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncState)

    @Insert
    suspend fun log(entry: OpLogEntry)

    @Query("SELECT * FROM op_log ORDER BY at DESC LIMIT :limit")
    fun observeLogs(limit: Int): Flow<List<OpLogEntry>>
}
