package com.peijian.pda.data

import androidx.room.withTransaction
import com.peijian.pda.barcode.BarcodeRules
import com.peijian.pda.data.dao.PartQtyRow
import com.peijian.pda.data.entity.BoxStatus
import com.peijian.pda.data.entity.BoxWithItems
import com.peijian.pda.data.entity.PackingBox
import com.peijian.pda.data.entity.PackingItem
import com.peijian.pda.data.entity.Shipment
import com.peijian.pda.data.entity.ShipmentBox
import com.peijian.pda.data.entity.ShipmentItem
import com.peijian.pda.data.entity.ShipmentStatus
import com.peijian.pda.data.entity.StockCheck
import com.peijian.pda.data.entity.StockCheckScan
import com.peijian.pda.data.entity.StockCheckStatus
import com.peijian.pda.importer.BoxPayload
import com.peijian.pda.importer.ItemPayload
import java.util.UUID

class PackingRepository(private val db: AppDatabase) {

    private val packing = db.packingDao()
    private val shipments = db.shipmentDao()
    private val checks = db.stockCheckDao()

    /* ---------------- 导入 / 同步落库 ---------------- */

    data class ApplyResult(val inserted: Int, val updated: Int, val items: Int)

    suspend fun applyBoxes(
        payloads: List<BoxPayload>,
        separator: String = BarcodeRules.DEFAULT_SEPARATOR,
        force: Boolean = false
    ): ApplyResult = db.withTransaction {
        var inserted = 0
        var updated = 0
        var itemCount = 0
        val now = System.currentTimeMillis()

        for (p in payloads) {
            val sourceKey = "${p.customerCode}|${p.orderNo}|${p.boxNo}"
            val existing = packing.findBySourceKey(sourceKey)
            val barcode = BarcodeRules.build(p.customerCode, p.orderNo, p.boxNo, separator)
            val norm = BarcodeRules.normalize(barcode)

            if (existing == null) {
                val id = packing.insertBox(
                    PackingBox(
                        sourceKey = sourceKey,
                        customerCode = p.customerCode,
                        customerName = p.customerName,
                        salesperson = p.salesperson,
                        country = p.country,
                        orderNo = p.orderNo,
                        boxNo = p.boxNo,
                        barcode = barcode,
                        barcodeNorm = norm,
                        packDate = p.packDate,
                        dimension = p.dimension,
                        volume = p.volume,
                        grossWeight = p.grossWeight,
                        packingUnit = p.packingUnit,
                        remark = p.remark,
                        rev = p.rev,
                        importedAt = now,
                        updatedAt = now,
                        sourceRow = p.sourceRow
                    )
                )
                packing.insertItems(p.items.map { it.toEntity(id) })
                inserted++
                itemCount += p.items.size
            } else {
                if (!force && p.rev < existing.rev) continue
                packing.updateBox(
                    existing.copy(
                        customerCode = p.customerCode,
                        customerName = p.customerName,
                        salesperson = p.salesperson,
                        country = p.country,
                        orderNo = p.orderNo,
                        boxNo = p.boxNo,
                        barcode = barcode,
                        barcodeNorm = norm,
                        packDate = p.packDate,
                        dimension = p.dimension,
                        volume = p.volume,
                        grossWeight = p.grossWeight,
                        packingUnit = p.packingUnit,
                        remark = p.remark,
                        rev = maxOf(p.rev, existing.rev),
                        updatedAt = now,
                        sourceRow = p.sourceRow
                    )
                )
                packing.deleteItems(existing.id)
                packing.insertItems(p.items.map { it.toEntity(existing.id) })
                updated++
                itemCount += p.items.size
            }
        }
        ApplyResult(inserted, updated, itemCount)
    }

    private fun ItemPayload.toEntity(boxId: Long) = PackingItem(
        boxId = boxId,
        partCode = partCode,
        englishName = englishName,
        partName = partName,
        quantity = quantity,
        machineModel = machineModel,
        remark = remark
    )

    /* ---------------- 扫码定位 ---------------- */

    sealed class ScanLookup {
        data class Found(val box: PackingBox, val items: List<PackingItem>) : ScanLookup()
        data class Ambiguous(val boxes: List<PackingBox>) : ScanLookup()
        data object NotFound : ScanLookup()
    }

    suspend fun lookup(code: String): ScanLookup {
        val norm = BarcodeRules.normalize(code)
        if (norm.length < 3) return ScanLookup.NotFound

        var candidates = packing.matchBarcode(norm)
        if (candidates.isEmpty()) {
            val (_, orderNo, boxNo) = BarcodeRules.split(code)
            if (orderNo.isNotEmpty() && boxNo.isNotEmpty()) {
                candidates = packing.matchOrderBox(orderNo, BarcodeRules.normalizeBoxNo(boxNo))
            }
        }
        if (candidates.isEmpty()) return ScanLookup.NotFound
        if (candidates.size > 1) {
            val exact = candidates.filter { it.barcodeNorm == norm }
            if (exact.size == 1) candidates = exact
            else {
                // 同一箱号存在重复时，优先取在库的
                val inStock = candidates.filter { it.status == BoxStatus.IN_STOCK }
                if (inStock.size == 1) candidates = inStock
                else return ScanLookup.Ambiguous(candidates)
            }
        }
        val box = candidates.first()
        return ScanLookup.Found(box, packing.itemsOfBox(box.id))
    }

    suspend fun boxDetail(boxId: Long): BoxWithItems? = packing.boxWithItems(boxId)

    /* ---------------- 发货 ---------------- */

    object ScanStatus {
        const val OK = "OK"
        const val DUPLICATE = "DUPLICATE"
        const val NOT_FOUND = "NOT_FOUND"
        const val ALREADY_SHIPPED = "ALREADY_SHIPPED"
        const val AMBIGUOUS = "AMBIGUOUS"
        const val NO_SHIPMENT = "NO_SHIPMENT"
        const val NO_CHECK = "NO_CHECK"
    }

    data class ScanResult(
        val status: String,
        val code: String,
        val box: PackingBox? = null,
        val items: List<PackingItem> = emptyList(),
        val message: String = ""
    )

    suspend fun openShipment(): Shipment? = shipments.openShipment()

    suspend fun createShipment(
        docNo: String,
        customerCode: String,
        customerName: String,
        receiver: String,
        operator: String,
        note: String
    ): Long = shipments.insertShipment(
        Shipment(
            docNo = docNo,
            customerCode = customerCode,
            customerName = customerName,
            receiver = receiver,
            operator = operator,
            note = note,
            uid = UUID.randomUUID().toString().replace("-", "")
        )
    )

    suspend fun scanOutbound(shipmentId: Long, code: String, operator: String): ScanResult =
        db.withTransaction {
            val shipment = shipments.findById(shipmentId) ?: return@withTransaction ScanResult(
                ScanStatus.NO_SHIPMENT, code, message = "发货单不存在"
            )
            if (shipment.status != ShipmentStatus.OPEN) return@withTransaction ScanResult(
                ScanStatus.NO_SHIPMENT, code, message = "发货单已结束"
            )

            when (val lookup = lookup(code)) {
                is ScanLookup.NotFound -> ScanResult(ScanStatus.NOT_FOUND, code, message = "系统里没有这个箱码")
                is ScanLookup.Ambiguous -> ScanResult(
                    ScanStatus.AMBIGUOUS, code, lookup.boxes.firstOrNull(),
                    message = "箱码重复：${lookup.boxes.joinToString { it.barcode }}"
                )
                is ScanLookup.Found -> {
                    val box = lookup.box
                    if (shipments.findBox(shipmentId, box.id) != null) {
                        return@withTransaction ScanResult(
                            ScanStatus.DUPLICATE, code, box, lookup.items, "这一箱已经扫过"
                        )
                    }
                    if (box.status == BoxStatus.SHIPPED) {
                        return@withTransaction ScanResult(
                            ScanStatus.ALREADY_SHIPPED, code, box, lookup.items,
                            "这一箱已于 ${box.shippedAt ?: ""} 发出"
                        )
                    }
                    val sbId = shipments.insertShipmentBox(
                        ShipmentBox(
                            shipmentId = shipmentId,
                            boxId = box.id,
                            barcode = box.barcode,
                            barcodeNorm = box.barcodeNorm,
                            customerCode = box.customerCode,
                            customerName = box.customerName,
                            orderNo = box.orderNo,
                            boxNo = box.boxNo,
                            operator = operator
                        )
                    )
                    shipments.insertShipmentItems(
                        lookup.items.map { item ->
                            ShipmentItem(
                                shipmentId = shipmentId,
                                shipmentBoxId = sbId,
                                partCode = item.partCode,
                                partName = item.partName,
                                englishName = item.englishName,
                                machineModel = item.machineModel,
                                quantity = item.quantity
                            )
                        }
                    )
                    packing.markShipped(box.id, shipmentId, System.currentTimeMillis())
                    ScanResult(ScanStatus.OK, code, box, lookup.items)
                }
            }
        }

    suspend fun removeShipmentBox(shipmentBoxId: Long): Boolean = db.withTransaction {
        val sb = shipments.findShipmentBox(shipmentBoxId) ?: return@withTransaction false
        shipments.deleteItemsOfBox(shipmentBoxId)
        shipments.deleteShipmentBox(shipmentBoxId)
        packing.markInStock(sb.boxId, System.currentTimeMillis())
        true
    }

    suspend fun finishShipment(id: Long): Boolean = db.withTransaction {
        val s = shipments.findById(id) ?: return@withTransaction false
        if (shipments.boxCount(id) == 0) return@withTransaction false
        shipments.updateShipment(
            s.copy(status = ShipmentStatus.FINISHED, finishedAt = System.currentTimeMillis())
        )
        true
    }

    suspend fun cancelShipment(id: Long): Boolean = db.withTransaction {
        val s = shipments.findById(id) ?: return@withTransaction false
        if (s.status == ShipmentStatus.FINISHED) return@withTransaction false
        shipments.revertBoxesOf(id, System.currentTimeMillis())
        shipments.deleteItemsOf(id)
        shipments.deleteBoxesOf(id)
        shipments.deleteShipment(id)
        true
    }

    /* ---------------- 盘点 ---------------- */

    suspend fun openCheck(): StockCheck? = checks.openCheck()

    suspend fun startCheck(batchNo: String, operator: String, note: String): Long =
        checks.insertCheck(
            StockCheck(
                batchNo = batchNo,
                operator = operator,
                note = note,
                uid = UUID.randomUUID().toString().replace("-", "")
            )
        )

    suspend fun scanStocktake(checkId: Long, code: String, operator: String): ScanResult =
        db.withTransaction {
            val check = checks.findById(checkId) ?: return@withTransaction ScanResult(
                ScanStatus.NO_CHECK, code, message = "盘点批次不存在"
            )
            if (check.status != StockCheckStatus.OPEN) return@withTransaction ScanResult(
                ScanStatus.NO_CHECK, code, message = "盘点已结束"
            )

            when (val lookup = lookup(code)) {
                is ScanLookup.NotFound -> {
                    // 系统没有这个箱：记录成异常（盘盈/错码），便于导出核查
                    checks.insertScan(
                        StockCheckScan(
                            checkId = checkId,
                            boxId = null,
                            barcode = code,
                            barcodeNorm = BarcodeRules.normalize(code),
                            expected = false,
                            operator = operator
                        )
                    )
                    ScanResult(ScanStatus.NOT_FOUND, code, message = "系统无此箱码，已记为异常")
                }
                is ScanLookup.Ambiguous -> ScanResult(
                    ScanStatus.AMBIGUOUS, code, lookup.boxes.firstOrNull(),
                    message = "箱码重复：${lookup.boxes.joinToString { it.barcode }}"
                )
                is ScanLookup.Found -> {
                    val box = lookup.box
                    if (checks.findScan(checkId, box.id) != null) {
                        return@withTransaction ScanResult(
                            ScanStatus.DUPLICATE, code, box, lookup.items, "这一箱已经盘过"
                        )
                    }
                    val expected = box.status == BoxStatus.IN_STOCK
                    checks.insertScan(
                        StockCheckScan(
                            checkId = checkId,
                            boxId = box.id,
                            barcode = box.barcode,
                            barcodeNorm = box.barcodeNorm,
                            orderNo = box.orderNo,
                            boxNo = box.boxNo,
                            expected = expected,
                            operator = operator
                        )
                    )
                    ScanResult(
                        if (expected) ScanStatus.OK else ScanStatus.ALREADY_SHIPPED,
                        code, box, lookup.items,
                        message = if (expected) "" else "系统显示这箱已发出（记为盘盈/异常）"
                    )
                }
            }
        }

    suspend fun finishCheck(id: Long): Boolean = db.withTransaction {
        val c = checks.findById(id) ?: return@withTransaction false
        checks.updateCheck(c.copy(status = StockCheckStatus.FINISHED, finishedAt = System.currentTimeMillis()))
        true
    }

    suspend fun cancelCheck(id: Long): Boolean = db.withTransaction {
        val c = checks.findById(id) ?: return@withTransaction false
        if (c.status == StockCheckStatus.FINISHED) return@withTransaction false
        checks.deleteScans(id)
        checks.deleteCheck(id)
        true
    }

    /* ---------------- 盘点结果 ---------------- */

    data class DiffRow(
        val partCode: String,
        val partName: String,
        val bookQty: Long,
        val actualQty: Long
    ) {
        val diff: Long get() = actualQty - bookQty
    }

    data class StocktakeReport(
        val bookQty: Long,
        val actualQty: Long,
        val bookedBoxes: Int,
        val scannedBoxes: Int,
        val matchedBoxes: Int,
        val extraBoxes: Int,
        val unknownScans: Int,
        val rows: List<DiffRow>,
        val missingBoxes: List<PackingBox>,
        val extra: List<PartQtyRow>
    )

    suspend fun stocktakeReport(checkId: Long): StocktakeReport = db.withTransaction {
        val book = packing.systemStockByPart().associateBy { it.partCode }
        val actual = checks.scannedStockByPart(checkId).associateBy { it.partCode }
        val extra = checks.extraStockByPart(checkId)

        val codes = (book.keys + actual.keys).sorted()
        val rows = codes.map { code ->
            DiffRow(
                partCode = code,
                partName = book[code]?.partName ?: actual[code]?.partName ?: "",
                bookQty = book[code]?.totalQty ?: 0,
                actualQty = actual[code]?.totalQty ?: 0
            )
        }

        StocktakeReport(
            bookQty = book.values.sumOf { it.totalQty },
            actualQty = actual.values.sumOf { it.totalQty },
            bookedBoxes = packing.stockBoxCount(),
            scannedBoxes = checks.scans(checkId).size,
            matchedBoxes = checks.matchedCount(checkId),
            extraBoxes = checks.extraCount(checkId),
            unknownScans = checks.unknownScanCount(checkId),
            rows = rows,
            missingBoxes = packing.missingBoxes(checkId),
            extra = extra
        )
    }
}
