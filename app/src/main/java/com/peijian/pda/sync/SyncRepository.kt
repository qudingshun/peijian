package com.peijian.pda.sync

import com.peijian.pda.data.AppDatabase
import com.peijian.pda.data.PackingRepository
import com.peijian.pda.data.entity.BoxStatus
import com.peijian.pda.importer.BoxPayload

/**
 * 同步业务：拉取打包数据（增量）、上发发货单与盘点结果。
 */
class SyncRepository(
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val client: SyncClient
) {
    private val repo = PackingRepository(db)

    data class PullReport(
        val revBefore: Long,
        val revAfter: Long,
        val received: Int,
        val inserted: Int,
        val updated: Int,
        val items: Int,
        val serverTime: String
    )

    data class PushReport(val shipments: Int, val stockChecks: Int)

    data class ReconcileReport(
        val serverRev: Long,
        val serverCount: Int,
        val clientCount: Int,
        val missing: Int,
        val stale: Int,
        val extra: Int,
        val applied: Int,
        val voided: Int,
        val skippedVoid: Int
    )

    /**
     * 对账：上报本机箱摘要，服务端返回差异并下发权威数据。
     * - missing / stale：应用服务端完整数据（补入 + 刷新），force 保证状态以服务端为准；
     * - extra（服务端已删行）：本地置 VOID，但有未同步出库事件的箱跳过，避免丢单据。
     */
    suspend fun reconcile(): ReconcileReport {
        val digests = db.packingDao().allDigests().map {
            SyncClient.DigestInput(it.barcodeNorm, it.rev, it.status)
        }
        val result = client.reconcile(digests)

        val applied = if (result.changedBoxes.isNotEmpty()) {
            repo.applyBoxes(result.changedBoxes, prefs.separator, force = true)
        } else PackingRepository.ApplyResult(0, 0, 0)

        var voided = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        for (norm in result.extra) {
            val local = db.packingDao().findExactByNorm(norm).firstOrNull() ?: continue
            // 有未同步出库事件时不能作废，否则发货单会丢失可追溯的箱
            if (db.packingDao().hasLocalOutbound(local.id) > 0) {
                skipped++
                continue
            }
            if (local.status != BoxStatus.VOID) {
                db.packingDao().markVoidByNorm(norm, now)
                voided++
            }
        }

        // 补入/刷新完成后推进版本号；extra 的作废不影响服务端的 rev 语义
        if (result.serverRev > prefs.lastRev) prefs.lastRev = result.serverRev
        prefs.lastSyncAt = System.currentTimeMillis()

        return ReconcileReport(
            serverRev = result.serverRev,
            serverCount = result.serverCount,
            clientCount = result.clientCount,
            missing = result.missing.size,
            stale = result.stale.size,
            extra = result.extra.size,
            applied = applied.inserted + applied.updated,
            voided = voided,
            skippedVoid = skipped
        )
    }

    suspend fun pull(): PullReport {
        val since = prefs.lastRev
        val result = client.pull(since)
        val boxes: List<BoxPayload> = result.boxes
        val applied = repo.applyBoxes(boxes, prefs.separator, force = false)
        // 只有成功落库后才推进版本号
        if (result.rev > since) prefs.lastRev = result.rev
        prefs.lastSyncAt = System.currentTimeMillis()
        return PullReport(
            revBefore = since,
            revAfter = prefs.lastRev,
            received = boxes.size,
            inserted = applied.inserted,
            updated = applied.updated,
            items = applied.items,
            serverTime = result.serverTime
        )
    }

    suspend fun pushAll(): PushReport {
        var shipmentCount = 0
        var checkCount = 0

        val shipments = db.shipmentDao().unsynced()
        for (s in shipments) {
            val boxes = db.shipmentDao().boxesOf(s.id)
            client.pushShipment(s, boxes) { sbId ->
                db.shipmentDao().itemsOfShipmentBox(sbId).map {
                    Triple(it.partCode, it.partName, it.quantity)
                }
            }
            db.shipmentDao().markSynced(s.id, System.currentTimeMillis())
            shipmentCount++
        }

        val checks = db.stockCheckDao().unsynced()
        for (c in checks) {
            val scans = db.stockCheckDao().scanEntities(c.id)
            client.pushStockCheck(c, scans) { scanId ->
                db.stockCheckDao().itemsOfScan(scanId).map {
                    Triple(it.partCode, it.partName, it.quantity)
                }
            }
            db.stockCheckDao().markSynced(c.id, System.currentTimeMillis())
            checkCount++
        }

        if (shipmentCount + checkCount > 0) prefs.lastSyncAt = System.currentTimeMillis()
        return PushReport(shipmentCount, checkCount)
    }
}
