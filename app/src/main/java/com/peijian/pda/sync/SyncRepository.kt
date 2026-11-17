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
        val serverTime: String,
        val pages: Int = 1
    )

    companion object {
        /** 每页拉取箱数；配合服务端 PACKING_PAGE_MAX=2000 */
        const val PULL_PAGE_SIZE = 1000
        /** 对账差异箱的分块落库大小 */
        private const val APPLY_CHUNK = 500
        /** 分页守卫，防止异常端返回 hasMore 导致死循环（2 万箱正常约 20 页） */
        private const val MAX_PAGES = 500
    }

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

        // 分块落库，避免换机首次对账（可能全量 2 万箱）一次性构建超大事务
        var inserted = 0
        var updated = 0
        var appliedItems = 0
        result.changedBoxes.chunked(APPLY_CHUNK).forEach { chunk ->
            val a = repo.applyBoxes(chunk, prefs.separator, force = true)
            inserted += a.inserted
            updated += a.updated
            appliedItems += a.items
        }
        val applied = PackingRepository.ApplyResult(inserted, updated, appliedItems)

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
        var cursorSince = since
        var cursorAfter = ""
        var pages = 0
        var received = 0
        var inserted = 0
        var updated = 0
        var itemLines = 0
        var serverTime = ""
        var serverRev = since

        // 逐页拉取 + 逐页落库：峰值内存从「全量 2 万箱」降为「单页 1000 箱」。
        // 版本号只在全部页面成功后推进；中途失败时下次从原 since 重拉，applyBoxes 幂等。
        while (true) {
            val page = client.pull(cursorSince, limit = PULL_PAGE_SIZE, afterKey = cursorAfter)
            pages++
            serverRev = page.rev
            serverTime = page.serverTime

            if (page.boxes.isNotEmpty()) {
                val applied = repo.applyBoxes(page.boxes, prefs.separator, force = false)
                received += page.boxes.size
                inserted += applied.inserted
                updated += applied.updated
                itemLines += applied.items
            }

            if (!page.hasMore) break
            // 防御：服务端声称还有下一页但本页空，或页数超守卫 → 中止，避免死循环
            if (page.boxes.isEmpty() || pages >= MAX_PAGES) {
                throw SyncException("分页拉取异常终止（pages=$pages），请重试")
            }
            cursorSince = page.nextSince
            cursorAfter = page.nextAfterKey
        }

        if (serverRev > since) prefs.lastRev = serverRev
        prefs.lastSyncAt = System.currentTimeMillis()
        return PullReport(
            revBefore = since,
            revAfter = prefs.lastRev,
            received = received,
            inserted = inserted,
            updated = updated,
            items = itemLines,
            serverTime = serverTime,
            pages = pages
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
