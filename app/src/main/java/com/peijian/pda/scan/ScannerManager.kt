package com.peijian.pda.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 工业 PDA 扫码广播采集（东集 CRUISE2 等）。
 * 若设备配置成 HID 键盘模式，扫码头会把内容打进 Compose 的输入框（扫码页也提供了手动输入框兜底）。
 */
class ScannerManager(private val context: Context) {

    private val _scans = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val scans = _scans.asSharedFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(_ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action !in LISTEN_ACTIONS) return
            val data = EXTRA_KEYS.firstNotNullOfOrNull { key ->
                intent.getStringExtra(key)?.takeIf { it.isNotBlank() }
            } ?: return
            Log.d(TAG, "scan via $action: $data")
            _scans.tryEmit(data.trim())
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply { LISTEN_ACTIONS.forEach { addAction(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    companion object {
        private const val TAG = "ScannerManager"
        const val PRIMARY_ACTION = "com.android.server.scannerservice.broadcast"
        const val PRIMARY_EXTRA = "scannerdata"

        private val LISTEN_ACTIONS = listOf(
            PRIMARY_ACTION,
            "android.intent.action.SCANRESULT",
            "android.intent.action.BARCODE_CAPTURE",
            "urovo.rcv.message",
            "com.android.scanbroadcast.SCAN"
        )

        private val EXTRA_KEYS = listOf(
            PRIMARY_EXTRA,
            "value", "barcode", "barcode_value", "scan_value", "data", "result", "BarcodeStr"
        )
    }
}
