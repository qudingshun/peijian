package com.peijian.pda.data

import androidx.room.withTransaction
import com.peijian.pda.barcode.BarcodeRules
import com.peijian.pda.data.dao.BoxListRow
import com.peijian.pda.data.dao.PartQtyRow
import com.peijian.pda.data.entity.BoxStatus
import com.peijian.pda.data.entity.BoxWithItems
import com.peijian.pda.data.entity.PackingBox
import com.peijian.pda.data.entity.PackingItem
import com.peijian.pda.data.entity.Shipment
import com.peijian.pda.data.entity.ShipmentBox
import com.peijian.pda.data.entity.ShipmentItem
import com.peijian.pda.data.entity.ShipmentPlanBox
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
        fun serverStatus(raw: String): String =
            if (raw == BoxStatus.SHIPPED || raw == BoxStatus.VOID) raw else BoxStatus.IN_STOCK

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
                        status = serverStatus(p.status),
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
                // 服务端状态为权威；本机仍有未上传的出库事件时保留本地状态，避免离线出库被覆盖
                val nextStatus = if (packing.hasLocalOutbound(existing.id) > 0) {
                    existing.status
                } else {
                    serverStatus(p.status)
                }
                packing.updateBox(
                    existing.copy(
                        status = nextStatus,
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

    /**
     * 候选裁决：纯逻辑，便于 JVM 单测覆盖。
     * 精确唯一 > 前缀拼接唯一 > 单号+箱号唯一；任一层出现多条都报歧义。
     * 不再按「在库状态」猜测——那会把短码误命中变成静默错箱出库。
     */
    internal object ScanResolver {
        sealed interface Outcome {
            data object NotFound : Outcome
            data class Ambiguous(val boxes: List<PackingBox>) : Outcome
            data class Unique(val box: PackingBox) : Outcome
        }

        fun resolve(
            exact: List<PackingBox>,
            tail: List<PackingBox>,
            fallback: List<PackingBox>
        ): Outcome = when {
            exact.size == 1 -> Outcome.Unique(exact.first())
            exact.size > 1 -> Outcome.Ambiguous(exact)
            tail.size == 1 -> Outcome.Unique(tail.first())
            tail.size > 1 -> Outcome.Ambiguous(tail)
            fallback.size == 1 -> Outcome.Unique(fallback.first())
            fallback.size > 1 -> Outcome.Ambiguous(fallback)
            else -> Outcome.NotFound
        }
    }

    suspend fun lookup(code: String): ScanLookup {
        val norm = BarcodeRules.normalize(code)
        if (norm.length < BarcodeRules.MIN_SCAN_NORM_LENGTH) return ScanLookup.NotFound

        val exact = packing.findExactByNorm(norm)
        val tail = if (exact.isEmpty()) packing.matchBarcodeTail(norm) else emptyList()
        val fallback = if (exact.isEmpty() && tail.isEmpty()) {
            // 纯拼接串无分隔符，单号/箱号边界不唯一；穷举所有合理拆分查库，
            // 命中多条交给 ScanResolver 报歧义，而不是用单一正则猜错边界。
            BarcodeRules.splitCandidates(code).flatMap { (_, orderNo, boxNo) ->
                // 生产单号形如 P108580；过短或纯数字的片段不参与反查，避免误命中
                if (orderNo.length >= 4 && orderNo.any { it.isLetter() } && boxNo.isNotBlank()) {
                    packing.matchOrderBox(orderNo, boxNo)
                } else emptyList()
            }.distinctBy { it.id }
        } else emptyList()

        return when (val outcome = ScanResolver.resolve(exact, tail, fallback)) {
            is ScanResolver.Outcome.NotFound -> ScanLookup.NotFound
            is ScanResolver.Outcome.Ambiguous -> ScanLookup.Ambiguous(outcome.boxes)
            is ScanResolver.Outcome.Unique ->
                ScanLookup.Found(outcome.box, packing.itemsOfBox(outcome.box.id))
        }
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
        const val WRONG_CUSTOMER = "WRONG_CUSTOMER"
        const val NOT_IN_PLAN = "NOT_IN_PLAN"
    }

    /**
     * 出库校验门禁（纯逻辑，便于 JVM 单测）：
     *  1) 发货单填了客户编码时，箱客户编码必须一致（大小写/空白不敏感）；
     *  2) 本单有预选清单时，箱必须在清单内；清单为空表示不启用该限制。
     */
    object OutboundGuard {
        sealed interface Verdict {
            data object Pass : Verdict
            data class Reject(val status: String, val message: String) : Verdict
        }

        fun check(customerCode: String, planIds: Set<Long>, box: PackingBox): Verdict {
            val want = customerCode.trim()
            if (want.isNotEmpty() && !box.customerCode.trim().equals(want, ignoreCase = true)) {
                return Verdict.Reject(
                    ScanStatus.WRONG_CUSTOMER,
                    "客户不符：本单 $want，该箱 ${box.customerCode}"
                )
            }
            if (planIds.isNotEmpty() && box.id !in planIds) {
                return Verdict.Reject(ScanStatus.NOT_IN_PLAN, "该箱不在本次预选清单里")
            }
            return Verdict.Pass
        }
    }

    /**
     * 撤销资格（纯逻辑，便于 JVM 单测）：
     * 仅"已打包 FINISHED 且尚未上传（syncedAt 为空）"的发货单可撤销。
     * 已上传单不可撤销——服务端已落库，本地删单只会丢追溯、且无法回退服务端状态。
     */
    object DiscardGuard {
        fun canDiscard(status: String, syncedAt: Long?): Boolean =
            status == ShipmentStatus.FINISHED && syncedAt == null
    }

    data class ScanResult(
        val status: String,
        val code: String,
        val box: PackingBox? = null,
        val items: List<PackingItem> = emptyList(),
        val message: String = ""
    )

    suspend fun openShipment(): Shipment? = shipments.openShipment()

    /** 客户编码 → 客户名称（从数据源箱表里取） */
    suspend fun customerNameOf(code: String): String? = packing.customerNameOf(code.trim())

    /* 预选箱 */
    suspend fun planBoxes(sid: Long): List<ShipmentPlanBox> = shipments.planBoxes(sid)

    suspend fun addPlanBox(sid: Long, row: BoxListRow) {
        shipments.insertPlanBox(
            ShipmentPlanBox(
                shipmentId = sid,
                boxId = row.id,
                barcode = row.barcode,
                orderNo = row.orderNo,
                boxNo = row.boxNo,
                customerCode = row.customerCode,
                customerName = row.customerName,
                partSummary = row.partSummary,
                totalQty = row.totalQty
            )
        )
    }

    suspend fun removePlanBox(sid: Long, boxId: Long) = shipments.deletePlanBox(sid, boxId)

    suspend fun clearPlan(sid: Long) = shipments.clearPlanBoxes(sid)

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
            // 预选清单（空 = 不启用限制）
            val planIds = shipments.planBoxIds(shipmentId).toSet()

            when (val lookup = lookup(code)) {
                is ScanLookup.NotFound -> ScanResult(ScanStatus.NOT_FOUND, code, message = "系统里没有这个箱码")
                is ScanLookup.Ambiguous -> ScanResult(
                    ScanStatus.AMBIGUOUS, code, lookup.boxes.firstOrNull(),
                    message = "箱码重复：${lookup.boxes.joinToString { it.barcode }}"
                )
                is ScanLookup.Found -> {
                    val box = lookup.box
                    // 门禁先于重复/状态判断：错客户、错箱直接拒绝并报警
                    when (val verdict = OutboundGuard.check(shipment.customerCode, planIds, box)) {
                        is OutboundGuard.Verdict.Reject -> return@withTransaction ScanResult(
                            verdict.status, code, box, lookup.items, verdict.message
                        )
                        OutboundGuard.Verdict.Pass -> Unit
                    }
                    if (shipments.findBox(shipmentId, box.id) != null) {
                        return@withTransaction ScanResult(
                            ScanStatus.DUPLICATE, code, box, lookup.items, "这一箱已经扫过"
                        )
                    }
                    if (box.status != BoxStatus.IN_STOCK) {
                        val reason = if (box.status == BoxStatus.SHIPPED) {
                            "这一箱已于 ${box.shippedAt ?: ""} 发出"
                        } else {
                            "这一箱状态为 ${box.status}，不能出库"
                        }
                        return@withTransaction ScanResult(
                            ScanStatus.ALREADY_SHIPPED, code, box, lookup.items, reason
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

    /**
     * 撤销一张已打包（FINISHED）但尚未上传的发货单。
     * 删除单据及其箱/明细/预选箱，并把关联箱回退到在库。
     * 已上传（syncedAt != null）的单不可撤销：服务端已有该单与 SHIPPED 箱，
     * 本地删除只会丢失追溯记录、无法回退服务端状态，因此拒绝。
     */
    suspend fun discardShipment(id: Long): Boolean = db.withTransaction {
        val s = shipments.findById(id) ?: return@withTransaction false
        if (!DiscardGuard.canDiscard(s.status, s.syncedAt)) return@withTransaction false
        val at = System.currentTimeMillis()
        shipments.revertBoxesOf(id, at)
        shipments.deleteItemsOf(id)
        shipments.deleteBoxesOf(id)
        shipments.clearPlanBoxes(id)
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
                    val scanId = checks.insertScan(
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
                    // 把箱内配件复制成快照：此后源表改动不会改写这次盘点的实盘数
                    checks.insertItems(lookup.items.map { it ->
                        com.peijian.pda.data.entity.StockCheckItem(
                            scanId = scanId,
                            checkId = checkId,
                            boxId = box.id,
                            partCode = it.partCode,
                            englishName = it.englishName,
                            partName = it.partName,
                            quantity = it.quantity,
                            machineModel = it.machineModel,
                            remark = it.remark
                        )
                    })
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
        checks.deleteItemsOfCheck(id)
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
