package com.peijian.pda.sync

import com.peijian.pda.data.entity.Shipment
import com.peijian.pda.data.entity.ShipmentBox
import com.peijian.pda.data.entity.StockCheck
import com.peijian.pda.data.entity.StockCheckScan
import com.peijian.pda.importer.BoxPayload
import com.peijian.pda.importer.ItemPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncException(message: String) : Exception(message)

/**
 * 与电脑端「配件同步服务」通信。
 * 协议见 docs/03-同步接口协议.md。
 */
class SyncClient(private val prefs: Prefs) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class PullResult(
        val rev: Long,
        val boxes: List<BoxPayload>,
        val serverTime: String
    )

    private fun base(): String {
        val url = prefs.serverUrl.trimEnd('/')
        if (url.isBlank()) throw SyncException("还没配置电脑端地址（同步页填写或扫配对码）")
        return url
    }

    suspend fun health(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${base()}/api/v1/health").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SyncException("连接失败 HTTP ${resp.code}")
            val o = JSONObject(body)
            if (!o.optBoolean("ok", false)) throw SyncException(o.optString("error", "服务返回异常"))
            "${o.optString("app", "peijian-sync")} ${o.optString("version", "")} @ ${o.optString("time", "")}"
        }
    }

    suspend fun pull(since: Long): PullResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/api/v1/packing?since=$since")
            .header("X-Sync-Token", prefs.token)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw SyncException("配对令牌不对，请在电脑端重新生成配对码")
            if (!resp.isSuccessful) throw SyncException("拉取失败 HTTP ${resp.code}: ${body.take(200)}")
            val o = JSONObject(body)
            if (!o.optBoolean("ok", false)) throw SyncException(o.optString("error", "服务返回异常"))

            val arr = o.optJSONArray("boxes") ?: JSONArray()
            val boxes = ArrayList<BoxPayload>(arr.length())
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val items = ArrayList<ItemPayload>()
                val ia = b.optJSONArray("items") ?: JSONArray()
                for (j in 0 until ia.length()) {
                    val it = ia.getJSONObject(j)
                    items.add(
                        ItemPayload(
                            partCode = it.optString("partCode"),
                            englishName = it.optString("englishName"),
                            partName = it.optString("partName"),
                            quantity = it.optInt("quantity"),
                            machineModel = it.optString("machineModel"),
                            remark = it.optString("remark")
                        )
                    )
                }
                boxes.add(
                    BoxPayload(
                        customerCode = b.optString("customerCode"),
                        customerName = b.optString("customerName"),
                        salesperson = b.optString("salesperson"),
                        country = b.optString("country"),
                        orderNo = b.optString("orderNo"),
                        boxNo = b.optString("boxNo"),
                        packDate = b.optLong("packDate", 0).takeIf { it > 0 },
                        dimension = b.optString("dimension"),
                        volume = b.optDouble("volume", Double.NaN).takeIf { it.isFinite() },
                        grossWeight = b.optDouble("grossWeight", Double.NaN).takeIf { it.isFinite() },
                        packingUnit = b.optString("packingUnit"),
                        remark = b.optString("remark"),
                        items = items,
                        rev = b.optLong("rev", 0),
                        sourceRow = b.optInt("sourceRow", 0),
                        status = b.optString("status", "IN_STOCK")
                    )
                )
            }
            PullResult(o.optLong("rev", since), boxes, o.optString("serverTime"))
        }
    }

    data class DigestInput(val boxNorm: String, val rev: Long, val status: String)

    data class ReconcileResult(
        val serverRev: Long,
        val serverCount: Int,
        val clientCount: Int,
        val missing: List<String>,
        val extra: List<String>,
        val stale: List<String>,
        /** 服务端权威的完整箱数据（missing + stale），可直接交给 applyBoxes 补入/刷新 */
        val changedBoxes: List<BoxPayload>
    )

    /** 上报本机全部箱摘要，取回两端差异；只读接口，不改变服务端状态。 */
    suspend fun reconcile(digests: List<DigestInput>): ReconcileResult = withContext(Dispatchers.IO) {
        val root = JSONObject()
        val arr = JSONArray()
        for (d in digests) {
            arr.put(
                JSONObject().apply {
                    put("boxNorm", d.boxNorm)
                    put("rev", d.rev)
                    put("status", d.status)
                }
            )
        }
        root.put("boxes", arr)

        val req = Request.Builder()
            .url("${base()}/api/v1/reconcile")
            .header("X-Sync-Token", prefs.token)
            .post(root.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw SyncException("配对令牌不对，请在电脑端重新生成配对码")
            if (!resp.isSuccessful) throw SyncException("对账失败 HTTP ${resp.code}: ${text.take(200)}")
            val o = JSONObject(text)
            if (!o.optBoolean("ok", false)) throw SyncException(o.optString("error", "对账失败"))

            val diff = o.optJSONObject("diff") ?: JSONObject()
            fun stringList(key: String): List<String> {
                val a = diff.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }
            }

            val boxesArr = o.optJSONArray("boxes") ?: JSONArray()
            val changed = ArrayList<BoxPayload>(boxesArr.length())
            for (i in 0 until boxesArr.length()) {
                val b = boxesArr.getJSONObject(i)
                val items = ArrayList<ItemPayload>()
                val ia = b.optJSONArray("items") ?: JSONArray()
                for (j in 0 until ia.length()) {
                    val it = ia.getJSONObject(j)
                    items.add(
                        ItemPayload(
                            partCode = it.optString("partCode"),
                            englishName = it.optString("englishName"),
                            partName = it.optString("partName"),
                            quantity = it.optInt("quantity"),
                            machineModel = it.optString("machineModel"),
                            remark = it.optString("remark")
                        )
                    )
                }
                changed.add(
                    BoxPayload(
                        customerCode = b.optString("customerCode"),
                        customerName = b.optString("customerName"),
                        salesperson = b.optString("salesperson"),
                        country = b.optString("country"),
                        orderNo = b.optString("orderNo"),
                        boxNo = b.optString("boxNo"),
                        packDate = b.optLong("packDate", 0).takeIf { it > 0 },
                        dimension = b.optString("dimension"),
                        volume = b.optDouble("volume", Double.NaN).takeIf { it.isFinite() },
                        grossWeight = b.optDouble("grossWeight", Double.NaN).takeIf { it.isFinite() },
                        packingUnit = b.optString("packingUnit"),
                        remark = b.optString("remark"),
                        items = items,
                        rev = b.optLong("rev", 0),
                        sourceRow = b.optInt("sourceRow", 0),
                        status = b.optString("status", "IN_STOCK")
                    )
                )
            }
            ReconcileResult(
                serverRev = o.optLong("serverRev", 0),
                serverCount = o.optInt("serverCount", 0),
                clientCount = o.optInt("clientCount", 0),
                missing = stringList("missing"),
                extra = stringList("extra"),
                stale = stringList("stale"),
                changedBoxes = changed
            )
        }
    }

    suspend fun pushShipment(
        shipment: Shipment,
        boxes: List<ShipmentBox>,
        itemsOfBox: suspend (Long) -> List<Triple<String, String, Int>>
    ): Unit = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("device", prefs.deviceName)
        root.put("uid", shipment.uid)
        root.put("docNo", shipment.docNo)
        root.put("customerCode", shipment.customerCode)
        root.put("customerName", shipment.customerName)
        root.put("receiver", shipment.receiver)
        root.put("operator", shipment.operator)
        root.put("note", shipment.note)
        root.put("createdAt", shipment.createdAt)
        root.put("finishedAt", shipment.finishedAt ?: 0)
        val arr = JSONArray()
        boxes.forEach { b ->
            val o = JSONObject()
            o.put("barcode", b.barcode)
            o.put("orderNo", b.orderNo)
            o.put("boxNo", b.boxNo)
            o.put("customerCode", b.customerCode)
            o.put("customerName", b.customerName)
            o.put("scannedAt", b.scannedAt)
            val ia = JSONArray()
            itemsOfBox(b.id).forEach { (code, name, qty) ->
                ia.put(JSONObject().put("partCode", code).put("partName", name).put("quantity", qty))
            }
            o.put("items", ia)
            arr.put(o)
        }
        root.put("boxes", arr)
        post("/api/v1/shipments", root)
    }

    suspend fun pushStockCheck(
        check: StockCheck,
        scans: List<StockCheckScan>,
        itemsOfBox: suspend (Long) -> List<Triple<String, String, Int>>
    ): Unit = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("device", prefs.deviceName)
        root.put("uid", check.uid)
        root.put("batchNo", check.batchNo)
        root.put("operator", check.operator)
        root.put("note", check.note)
        root.put("startedAt", check.startedAt)
        root.put("finishedAt", check.finishedAt ?: 0)
        val arr = JSONArray()
        scans.forEach { s ->
            val o = JSONObject()
            o.put("barcode", s.barcode)
            o.put("orderNo", s.orderNo)
            o.put("boxNo", s.boxNo)
            o.put("expected", s.expected)
            o.put("scannedAt", s.scannedAt)
            val ia = JSONArray()
            s.boxId?.let { bid -> itemsOfBox(bid) }?.forEach { (code, name, qty) ->
                ia.put(JSONObject().put("partCode", code).put("partName", name).put("quantity", qty))
            }
            o.put("items", ia)
            arr.put(o)
        }
        root.put("scans", arr)
        post("/api/v1/stockchecks", root)
    }

    private fun post(path: String, body: JSONObject) {
        val req = Request.Builder()
            .url(base() + path)
            .header("X-Sync-Token", prefs.token)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw SyncException("配对令牌不对，请重新配对")
            if (resp.code == 409) {
                val detail = runCatching { JSONObject(text).optJSONObject("detail") }.getOrNull()
                val arr = detail?.optJSONArray("conflicts")
                val names = if (arr == null) "" else (0 until arr.length()).joinToString("、") { arr.optString(it) }
                throw SyncException("以下箱已被其他设备发出或不存在，本单未上传：$names")
            }
            if (!resp.isSuccessful) throw SyncException("上传失败 HTTP ${resp.code}: ${text.take(200)}")
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (o != null && !o.optBoolean("ok", true)) throw SyncException(o.optString("error", "上传失败"))
        }
    }
}
