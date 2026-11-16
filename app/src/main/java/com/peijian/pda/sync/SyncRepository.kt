package com.peijian.pda.sync

import com.peijian.pda.data.AppDatabase
import com.peijian.pda.data.PackingRepository
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
            client.pushStockCheck(c, scans) { boxId ->
                db.packingDao().itemsOfBox(boxId).map {
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
