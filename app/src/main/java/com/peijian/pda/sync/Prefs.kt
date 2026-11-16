package com.peijian.pda.sync

import android.content.Context
import android.os.Build
import com.peijian.pda.barcode.BarcodeRules

/**
 * 本机配置：电脑端地址、配对令牌、操作员、条码分隔符等。
 * 用 SharedPreferences 保存，改动即时生效。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("peijian", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_URL, normalizeUrl(value)).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value.trim()).apply()

    var operator: String
        get() = sp.getString(KEY_OPERATOR, "") ?: ""
        set(value) = sp.edit().putString(KEY_OPERATOR, value.trim()).apply()

    var deviceName: String
        get() = sp.getString(KEY_DEVICE, "")?.takeIf { it.isNotBlank() }
            ?: "PDA-${Build.MODEL}"
        set(value) = sp.edit().putString(KEY_DEVICE, value.trim()).apply()

    var separator: String
        get() = sp.getString(KEY_SEP, BarcodeRules.DEFAULT_SEPARATOR) ?: BarcodeRules.DEFAULT_SEPARATOR
        set(value) = sp.edit().putString(KEY_SEP, value.ifBlank { BarcodeRules.DEFAULT_SEPARATOR }).apply()

    var duplicatePolicy: String
        get() = sp.getString(KEY_DUP, "MERGE") ?: "MERGE"
        set(value) = sp.edit().putString(KEY_DUP, value).apply()

    var lastRev: Long
        get() = sp.getLong(KEY_REV, 0)
        set(value) = sp.edit().putLong(KEY_REV, value).apply()

    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    var pairedAt: Long
        get() = sp.getLong(KEY_PAIRED, 0)
        set(value) = sp.edit().putLong(KEY_PAIRED, value).apply()

    fun isPaired(): Boolean = serverUrl.isNotBlank() && token.isNotBlank()

    /** 支持粘贴 "192.168.1.5:8000" 或完整 URL */
    fun normalizeUrl(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    /**
     * 解析电脑端配对二维码：
     *   PEIJIANSYNC1|ip=192.168.1.5|port=8000|token=xxxx|ver=1
     * 也兼容 "192.168.1.5:8000#token" 手输格式。
     */
    fun applyPairing(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("PEIJIANSYNC1", ignoreCase = true)) {
            val map = t.split('|').drop(1).mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            val ip = map["ip"] ?: return false
            val port = map["port"] ?: "8000"
            val tk = map["token"] ?: return false
            serverUrl = "http://$ip:$port"
            token = tk
            pairedAt = System.currentTimeMillis()
            return true
        }
        val hash = t.indexOf('#')
        if (hash > 0) {
            serverUrl = t.substring(0, hash)
            token = t.substring(hash + 1)
            pairedAt = System.currentTimeMillis()
            return true
        }
        return false
    }

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_OPERATOR = "operator"
        private const val KEY_DEVICE = "device_name"
        private const val KEY_SEP = "barcode_separator"
        private const val KEY_DUP = "duplicate_policy"
        private const val KEY_REV = "last_rev"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_PAIRED = "paired_at"
    }
}
