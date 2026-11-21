package com.peijian.pda.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peijian.pda.BuildConfig
import com.peijian.pda.PeijianApp
import com.peijian.pda.backup.DbBackup
import com.peijian.pda.data.PackingRepository
import com.peijian.pda.data.dao.BoxListRow
import com.peijian.pda.data.entity.ShipmentPlanBox
import com.peijian.pda.data.dao.ShipmentRow
import com.peijian.pda.data.dao.StockScanRow
import com.peijian.pda.data.entity.BoxStatus
import com.peijian.pda.data.entity.PackingBox
import com.peijian.pda.data.entity.Shipment
import com.peijian.pda.data.entity.StockCheck
import com.peijian.pda.export.Exporter
import com.peijian.pda.importer.PackingImporter
import com.peijian.pda.logic.DateText
import com.peijian.pda.scan.Haptics
import com.peijian.pda.scan.Haptics.Stage as FeedbackStage
import com.peijian.pda.sync.SyncClient
import com.peijian.pda.sync.SyncRepository
import com.peijian.pda.sync.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

data class SettingsState(
    val serverUrl: String = "",
    val token: String = "",
    val operator: String = "",
    val deviceName: String = "",
    val separator: String = "-",
    val duplicatePolicy: String = PackingImporter.DuplicatePolicy.MERGE,
    val lastRev: Long = 0,
    val lastSyncAt: Long = 0,
    val feedback: Map<FeedbackStage, Haptics.FeedbackSetting> = Haptics.defaults
)

data class UiEvent(val text: String, val ok: Boolean = true, val restart: Boolean = false)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val peijian = app as PeijianApp
    private val db = peijian.db
    private val prefs = peijian.prefs
    private val repo = PackingRepository(db)
    private val syncRepo = SyncRepository(db, prefs, SyncClient(prefs))

    private val packingDao = db.packingDao()
    private val shipmentDao = db.shipmentDao()
    private val checkDao = db.stockCheckDao()

    /** 扫码枪广播流（HID 键盘模式走各页输入框） */
    val scanEvents = peijian.scanner.scans

    /* ---------------- 全局状态 ---------------- */

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _busyText = MutableStateFlow("")
    val busyText: StateFlow<String> = _busyText.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    /** 已校验、等待用户确认的备份（用于恢复确认弹窗） */
    private val _pendingRestore = MutableStateFlow<DbBackup.Prepared?>(null)
    val pendingRestore: StateFlow<DbBackup.Prepared?> = _pendingRestore.asStateFlow()

    private val appVersion: String
        get() = BuildConfig.VERSION_NAME

    fun readSettings() = SettingsState(
        serverUrl = prefs.serverUrl,
        token = prefs.token,
        operator = prefs.operator,
        deviceName = prefs.deviceName,
        separator = prefs.separator,
        duplicatePolicy = prefs.duplicatePolicy,
        lastRev = prefs.lastRev,
        lastSyncAt = prefs.lastSyncAt,
        feedback = FeedbackStage.entries.associateWith { prefs.feedbackSetting(it) }
    )

    fun saveSettings(
        serverUrl: String,
        token: String,
        operator: String,
        deviceName: String,
        separator: String,
        duplicatePolicy: String
    ) {
        prefs.serverUrl = serverUrl
        prefs.token = token.trim()
        prefs.operator = operator
        prefs.deviceName = deviceName
        prefs.separator = separator
        prefs.duplicatePolicy = duplicatePolicy
        _settings.value = readSettings()
        emit("设置已保存")
    }

    fun saveFeedback(all: Map<FeedbackStage, Haptics.FeedbackSetting>) {
        prefs.saveFeedback(all)
        _settings.value = readSettings()
        emit("扫码反馈设置已保存")
    }

    fun applyPairing(text: String) {
        if (prefs.applyPairing(text)) {
            _settings.value = readSettings()
            emit("配对成功：${prefs.serverUrl}")
        } else {
            emit("配对码格式不对", ok = false)
        }
    }

    /* ---------------- 数据流 ---------------- */

    val stockBoxCount = packingDao.observeStockBoxCount()
    val stockPartCount = packingDao.observeStockPartCount()
    val stockQty = packingDao.observeStockQty()
    val shippedBoxCount = packingDao.observeShippedBoxCount()
    val allBoxCount = packingDao.observeBoxCount()
    val unsyncedShipments = shipmentDao.observeUnsyncedCount()
    val unsyncedChecks = checkDao.observeUnsyncedCount()

    private val _boxQuery = MutableStateFlow("")
    private val _boxFilter = MutableStateFlow("")
    val boxQuery: StateFlow<String> = _boxQuery.asStateFlow()
    val boxFilter: StateFlow<String> = _boxFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val boxes: StateFlow<List<BoxListRow>> =
        combine(_boxQuery, _boxFilter) { q, f -> q.trim() to f }
            .flatMapLatest { (q, f) -> packingDao.observeBoxes(f, q, 300) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    fun setBoxQuery(q: String) { _boxQuery.value = q }
    fun setBoxFilter(f: String) { _boxFilter.value = f }

    private val _boxDetail = MutableStateFlow<com.peijian.pda.data.entity.BoxWithItems?>(null)
    val boxDetail: StateFlow<com.peijian.pda.data.entity.BoxWithItems?> = _boxDetail.asStateFlow()

    fun loadBoxDetail(id: Long) {
        viewModelScope.launch { _boxDetail.value = repo.boxDetail(id) }
    }

    fun clearBoxDetail() { _boxDetail.value = null }

    /* ---------------- 出库 ---------------- */

    private val _openShipment = MutableStateFlow<Shipment?>(null)
    val openShipment: StateFlow<Shipment?> = _openShipment.asStateFlow()

    private val _shipmentBoxes = MutableStateFlow<List<ShipmentRow>>(emptyList())
    val shipmentBoxes: StateFlow<List<ShipmentRow>> = _shipmentBoxes.asStateFlow()

    private val _lastScan = MutableStateFlow<PackingRepository.ScanResult?>(null)
    val lastScan: StateFlow<PackingRepository.ScanResult?> = _lastScan.asStateFlow()

    /* 预选箱：候选在库箱 + 本单已勾选 */
    private val _planQuery = MutableStateFlow("")
    val planQuery: StateFlow<String> = _planQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val planCandidates: StateFlow<List<BoxListRow>> =
        combine(_planQuery, _openShipment) { q, s -> q.trim() to (s?.customerCode ?: "") }
            .flatMapLatest { (q, code) -> packingDao.observePlanCandidates(code, q, 300) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    private val _planBoxes = MutableStateFlow<List<ShipmentPlanBox>>(emptyList())
    val planBoxes: StateFlow<List<ShipmentPlanBox>> = _planBoxes.asStateFlow()

    fun setPlanQuery(q: String) { _planQuery.value = q }

    /** 输入客户编码后从数据源取客户名称；返回 null 表示数据源里没有 */
    suspend fun lookupCustomerName(code: String): String? = withContext(Dispatchers.IO) {
        repo.customerNameOf(code)
    }

    private var planJob: Job? = null

    private fun refreshPlan(sid: Long?) {
        planJob?.cancel()
        if (sid == null) {
            _planBoxes.value = emptyList()
            return
        }
        planJob = viewModelScope.launch {
            shipmentDao.observePlanBoxes(sid).collect { _planBoxes.value = it }
        }
    }

    fun togglePlanBox(row: BoxListRow) {
        val s = _openShipment.value ?: return
        viewModelScope.launch {
            if (_planBoxes.value.any { it.boxId == row.id }) {
                repo.removePlanBox(s.id, row.id)
                emit("已取消预选：${row.orderNo} 箱${row.boxNo}")
            } else {
                repo.addPlanBox(s.id, row)
                emit("已预选：${row.orderNo} 箱${row.boxNo}")
            }
        }
    }

    fun clearPlan() {
        val s = _openShipment.value ?: return
        viewModelScope.launch {
            repo.clearPlan(s.id)
            emit("已清空预选箱（恢复为不限制）")
        }
    }

    val shipments = shipmentDao.observeShipments(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    val checks = checkDao.observeChecks(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    val shipmentDetail: StateFlow<List<com.peijian.pda.data.dao.ShipmentDetailRow>> = MutableStateFlow(emptyList())

    private fun refreshShipment(id: Long?) {
        shipmentJob?.cancel()
        if (id == null) {
            _shipmentBoxes.value = emptyList()
            return
        }
        shipmentJob = viewModelScope.launch {
            shipmentDao.observeShipmentBoxes(id).collect { _shipmentBoxes.value = it }
        }
    }

    fun startShipment(
        docNo: String,
        customerCode: String,
        customerName: String,
        receiver: String,
        note: String
    ) {
        viewModelScope.launch {
            val open = repo.openShipment()
            if (open != null) {
                _openShipment.value = open
                refreshShipment(open.id)
                refreshPlan(open.id)
                emit("已有一张未结束的发货柜：${open.docNo}", ok = false)
                return@launch
            }
            val id = repo.createShipment(
                docNo = docNo.ifBlank { "FH" + DateText.docStamp() },
                customerCode = customerCode.trim(),
                customerName = customerName.trim(),
                receiver = receiver.trim(),
                operator = prefs.operator.ifBlank { prefs.deviceName },
                note = note.trim()
            )
            val s = shipmentDao.findById(id)
            _openShipment.value = s
            refreshShipment(id)
            refreshPlan(id)
            emit("发货柜已建立")
        }
    }

    fun loadOpenState() {
        viewModelScope.launch {
            val s = repo.openShipment()
            _openShipment.value = s
            refreshShipment(s?.id)
            refreshPlan(s?.id)
            val c = repo.openCheck()
            _openCheck.value = c
            refreshCheck(c?.id)
        }
    }

    fun scanOutbound(code: String) {
        val s = _openShipment.value ?: run {
            emit("请先建立发货单", ok = false)
            return
        }
        if (!acceptScan(code)) return
        viewModelScope.launch {
            val r = repo.scanOutbound(s.id, code, prefs.operator.ifBlank { prefs.deviceName })
            _lastScan.value = r
            when (r.status) {
                PackingRepository.ScanStatus.OK -> {
                    feedback(FeedbackStage.SUCCESS)
                    val qty = r.items.sumOf { it.quantity }
                    emit("✓ ${r.box?.orderNo} 箱${r.box?.boxNo}  ${r.items.size} 种 / $qty 件")
                }
                else -> {
                    feedback(feedbackStageOf(r.status))
                    emit(r.message.ifBlank { "扫码失败" }, ok = false)
                }
            }
        }
    }

    private fun feedback(stage: FeedbackStage) {
        Haptics.feedback(getApplication(), stage, _settings.value.feedback[stage])
    }

    private fun feedbackStageOf(status: String): FeedbackStage = when (status) {
        PackingRepository.ScanStatus.DUPLICATE -> FeedbackStage.DUPLICATE
        PackingRepository.ScanStatus.NOT_FOUND -> FeedbackStage.NOT_FOUND
        PackingRepository.ScanStatus.ALREADY_SHIPPED -> FeedbackStage.ALREADY_SHIPPED
        PackingRepository.ScanStatus.AMBIGUOUS -> FeedbackStage.AMBIGUOUS
        PackingRepository.ScanStatus.WRONG_CUSTOMER -> FeedbackStage.WRONG_CUSTOMER
        PackingRepository.ScanStatus.NOT_IN_PLAN -> FeedbackStage.NOT_IN_PLAN
        else -> FeedbackStage.NOT_FOUND
    }

    fun removeShipmentBox(shipmentBoxId: Long) {
        viewModelScope.launch {
            if (repo.removeShipmentBox(shipmentBoxId)) emit("已撤回这一箱")
            else emit("撤回失败", ok = false)
        }
    }

    fun finishShipment() {
        val s = _openShipment.value ?: return
        viewModelScope.launch {
            if (repo.finishShipment(s.id)) {
                emit("发货完成：${s.docNo}")
                _openShipment.value = null
                _shipmentBoxes.value = emptyList()
                _planBoxes.value = emptyList()
                _lastScan.value = null
            } else {
                emit("至少扫一箱才能完成", ok = false)
            }
        }
    }

    fun cancelShipment() {
        val s = _openShipment.value ?: return
        viewModelScope.launch {
            if (repo.cancelShipment(s.id)) {
                repo.clearPlan(s.id)
                emit("发货柜已取消，箱回到在库")
                _openShipment.value = null
                _shipmentBoxes.value = emptyList()
                _planBoxes.value = emptyList()
                _lastScan.value = null
            } else emit("取消失败（已完成的发货柜不能取消）", ok = false)
        }
    }

    /** 撤销已打包但未上传的发货单（按 id，不依赖 _openShipment） */
    fun discardShipment(id: Long, docNo: String) {
        viewModelScope.launch {
            if (repo.discardShipment(id)) {
                emit("发货柜 $docNo 已撤销，关联箱已回到在库")
                // 若恰好正在查看这张单，清空相关状态
                if (_openShipment.value?.id == id) {
                    _openShipment.value = null
                    _shipmentBoxes.value = emptyList()
                    _planBoxes.value = emptyList()
                    _lastScan.value = null
                }
            } else emit("撤销失败：仅可撤销已打包且未上传的发货柜", ok = false)
        }
    }

    /* ---------------- 盘点 ---------------- */

    private val _openCheck = MutableStateFlow<StockCheck?>(null)
    val openCheck: StateFlow<StockCheck?> = _openCheck.asStateFlow()

    private val _onCheckScans = MutableStateFlow<List<StockScanRow>>(emptyList())
    val checkScans: StateFlow<List<StockScanRow>> = _onCheckScans.asStateFlow()

    private var shipmentJob: Job? = null
    private var checkJob: Job? = null

    private fun refreshCheck(id: Long?) {
        checkJob?.cancel()
        if (id == null) {
            _onCheckScans.value = emptyList()
            return
        }
        checkJob = viewModelScope.launch {
            checkDao.observeScans(id, 500).collect { _onCheckScans.value = it }
        }
    }

    fun startCheck(batchNo: String, note: String) {
        viewModelScope.launch {
            val open = repo.openCheck()
            if (open != null) {
                _openCheck.value = open
                refreshCheck(open.id)
                emit("已有一个未结束的盘点批次：${open.batchNo}", ok = false)
                return@launch
            }
            val id = repo.startCheck(
                batchNo = batchNo.ifBlank { "PD" + DateText.docStamp() },
                operator = prefs.operator.ifBlank { prefs.deviceName },
                note = note.trim()
            )
            val c = checkDao.findById(id)
            _openCheck.value = c
            refreshCheck(id)
            emit("盘点已开始")
        }
    }

    fun scanStocktake(code: String) {
        val c = _openCheck.value ?: run {
            emit("请先开始盘点", ok = false)
            return
        }
        if (!acceptScan(code)) return
        viewModelScope.launch {
            val r = repo.scanStocktake(c.id, code, prefs.operator.ifBlank { prefs.deviceName })
            _lastScan.value = r
            when (r.status) {
                PackingRepository.ScanStatus.OK -> {
                    feedback(FeedbackStage.SUCCESS)
                    emit("✓ 已盘 ${r.box?.orderNo} 箱${r.box?.boxNo}")
                }
                PackingRepository.ScanStatus.ALREADY_SHIPPED,
                PackingRepository.ScanStatus.NOT_FOUND -> {
                    feedback(feedbackStageOf(r.status))
                    emit(r.message, ok = false)
                }
                else -> {
                    feedback(feedbackStageOf(r.status))
                    emit(r.message.ifBlank { "扫码失败" }, ok = false)
                }
            }
        }
    }

    fun finishCheck() {
        val c = _openCheck.value ?: return
        viewModelScope.launch {
            if (repo.finishCheck(c.id)) {
                emit("盘点已结束，可以导出差异表")
                _openCheck.value = null
                _onCheckScans.value = emptyList()
            }
        }
    }

    fun cancelCheck() {
        val c = _openCheck.value ?: return
        viewModelScope.launch {
            if (repo.cancelCheck(c.id)) {
                emit("盘点已取消")
                _openCheck.value = null
                _onCheckScans.value = emptyList()
            } else emit("取消失败", ok = false)
        }
    }

    /* ---------------- 同步 / 导入 ---------------- */

    fun testConnection() {
        runBusy("正在连接电脑…") {
            val info = SyncClient(prefs).health()
            emit("连接成功：$info")
        }
    }

    fun pull() {
        runBusy("正在拉取新增打包数据…") {
            val r = syncRepo.pull()
            _settings.value = readSettings()
            emit(
                if (r.received == 0) "没有新数据（版本 ${r.revAfter}）"
                else "收到 ${r.received} 箱：新增 ${r.inserted}，更新 ${r.updated}，配件明细 ${r.items} 行" +
                    if (r.pages > 1) "（分 ${r.pages} 页）" else ""
            )
        }
    }

    /** 对账：上报本机箱摘要，取回服务端权威数据修复差箱/状态分歧。 */
    fun reconcile() {
        runBusy("正在与电脑对账…") {
            val r = syncRepo.reconcile()
            _settings.value = readSettings()
            val head = "对账完成：本机 ${r.clientCount} / 电脑 ${r.serverCount} 箱，服务版本 ${r.serverRev}"
            val detail = buildList {
                if (r.missing > 0) add("补入 ${r.missing} 箱")
                if (r.stale > 0) add("刷新 ${r.stale} 箱")
                if (r.extra > 0) add("作废 ${r.voided} 箱${if (r.skippedVoid > 0) "（${r.skippedVoid} 箱有未传单据已跳过）" else ""}")
                if (r.applied > 0) add("共写入 ${r.applied} 箱")
            }
            emit(if (detail.isEmpty()) "$head，两端一致" else "$head；${detail.joinToString("、")}")
        }
    }

    fun push() {
        runBusy("正在上传未同步记录…") {
            val r = syncRepo.pushAll()
            val done = "发货单 ${r.shipments} 张，盘点 ${r.stockChecks} 批"
            val head = when {
                r.shipments + r.stockChecks > 0 -> "已上传：$done"
                r.conflicts.isEmpty() -> "没有待上传的记录"
                else -> "没有记录上传成功"
            }
            emit(
                if (r.conflicts.isEmpty()) head
                else "$head；${r.conflicts.size} 条冲突未上传（仍保留在本机）：\n" +
                    r.conflicts.joinToString("\n")
            )
        }
    }

    fun importXlsx(uri: Uri) {
        runBusy("正在导入 Excel…") {
            val ctx = getApplication<Application>()
            val file = withContext(Dispatchers.IO) {
                val name = "import_${System.currentTimeMillis()}.xlsx"
                val f = File(ctx.cacheDir, name)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("无法读取所选文件")
                f
            }
            val (boxes, parsed) = withContext(Dispatchers.Default) {
                PackingImporter.parse(file, prefs.duplicatePolicy)
            }
            val applied = repo.applyBoxes(boxes, prefs.separator, force = true)
            withContext(Dispatchers.IO) { file.delete() }
            emit(
                "导入完成：${applied.inserted + applied.updated} 箱 / ${applied.items} 行" +
                    if (parsed.duplicates > 0) "（重复箱号 ${parsed.duplicates} 行已按设置处理）" else ""
            )
        }
    }

    fun exportInventory() {
        runBusy("正在导出库存清单…") {
            val r = Exporter.exportInventory(getApplication(), db)
            emit("已导出 ${r.rows} 行\n${r.file.absolutePath}")
            shareFile(r.file)
        }
    }

    fun exportShipment(shipmentId: Long) {
        runBusy("正在导出出库明细…") {
            val r = Exporter.exportShipment(getApplication(), db, shipmentId)
            emit("已导出 ${r.rows} 行\n${r.file.absolutePath}")
            shareFile(r.file)
        }
    }

    fun exportStocktake(checkId: Long) {
        runBusy("正在导出盘点差异…") {
            val r = Exporter.exportStocktake(getApplication(), db, checkId)
            emit("已导出 ${r.rows} 条扫描记录\n${r.file.absolutePath}")
            shareFile(r.file)
        }
    }

    /* ---------------- 数据库备份 / 恢复 ---------------- */

    fun backupDatabase() {
        runBusy("正在备份数据库…") {
            val ctx = getApplication<Application>()
            val boxes = packingDao.totalBoxCount()
            val unsyncedShip = shipmentDao.unsynced().size
            val unsyncedCheck = checkDao.unsynced().size
            val r = DbBackup.export(ctx, db, appVersion, boxes, unsyncedShip, unsyncedCheck)
            emit("备份完成（${r.manifest.boxCount} 箱 / ${r.manifest.dbBytes / 1024} KB）\n${r.file.absolutePath}" +
                if (r.note.isNotBlank()) "\n${r.note}" else "")
            runCatching { Exporter.shareAs(ctx, r.file, "application/zip") }
        }
    }

    /** 选择备份文件后先校验，成功后进入确认步骤；失败发事件提示。 */
    fun prepareRestore(uri: Uri) {
        runBusy("正在校验备份…") {
            val prepared = DbBackup.prepare(getApplication(), uri)
            _pendingRestore.value = prepared
            emit("备份校验通过：${prepared.manifest.boxCount} 箱，创建于 " +
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(prepared.manifest.createdAt)))
        }
    }

    fun cancelRestore() {
        val p = _pendingRestore.value ?: return
        viewModelScope.launch { DbBackup.discard(getApplication(), p) }
        _pendingRestore.value = null
    }

    /** 用户确认后执行恢复；成功后投递带 restart 标志的事件，由 UI 重启进程。 */
    fun confirmRestore() {
        val prepared = _pendingRestore.value ?: return
        runBusy("正在恢复数据库…") {
            val ctx = getApplication<Application>()
            val boxes = packingDao.totalBoxCount()
            val unsyncedShip = shipmentDao.unsynced().size
            val unsyncedCheck = checkDao.unsynced().size
            val r = DbBackup.restore(ctx, db, prepared, appVersion, boxes, unsyncedShip, unsyncedCheck)
            _pendingRestore.value = null
            val pre = if (r.preBackup != null) "（恢复前的库已另存备份）" else ""
            _events.tryEmit(
                UiEvent("恢复成功：$r.manifest.boxCount 箱$pre\n应用即将重启…", ok = true, restart = true)
            )
        }
    }

    private fun shareFile(file: File) {
        runCatching { Exporter.share(getApplication(), file) }
    }

    private fun runBusy(text: String, block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _busyText.value = text
            try {
                block()
            } catch (e: SyncException) {
                emit(e.message ?: "同步失败", ok = false)
            } catch (e: Exception) {
                emit(e.message ?: e.javaClass.simpleName, ok = false)
            } finally {
                _busy.value = false
                _busyText.value = ""
            }
        }
    }

    private fun emit(text: String, ok: Boolean = true) {
        _events.tryEmit(UiEvent(text, ok))
    }

    /* 防止同一枪重复触发（部分 ROM 广播 + HID 双发） */
    private var lastCode = ""
    private var lastAt = 0L

    private fun acceptScan(code: String): Boolean {
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastAt < 700) return false
        lastCode = code
        lastAt = now
        return true
    }

    fun clearLastScan() { _lastScan.value = null }

    fun buildBarcodePreview(customerCode: String, orderNo: String, boxNo: String): String =
        com.peijian.pda.barcode.BarcodeRules.build(customerCode, orderNo, boxNo, prefs.separator)
}

