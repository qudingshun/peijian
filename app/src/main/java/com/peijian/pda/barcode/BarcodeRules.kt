package com.peijian.pda.barcode

import com.peijian.pda.data.entity.PackingBox

/**
 * 箱码规则：
 *   客户编码 + 生产单号 + 箱号
 * 默认用 `-` 连接：W00305-P108580-1
 *
 * 现场如果标签机上直接拼接（W00305P1085801），扫码时也能匹配：
 * 所有比较都先 [normalize]（只留字母数字并大写），再做精确 / 后缀匹配。
 */
object BarcodeRules {

    const val DEFAULT_SEPARATOR = "-"

    /**
     * 归一化后允许参与扫码匹配的最小长度。
     * 短码（如 "1"、"801"）可能是任意箱码的后缀，放行会造成错箱出库。
     */
    const val MIN_SCAN_NORM_LENGTH = 6

    fun normalize(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }

    fun build(customerCode: String, orderNo: String, boxNo: String, separator: String = DEFAULT_SEPARATOR): String {
        val parts = listOf(customerCode, orderNo, boxNo)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return parts.joinToString(separator)
    }

    fun of(box: PackingBox, separator: String = DEFAULT_SEPARATOR): String =
        build(box.customerCode, box.orderNo, box.boxNo, separator)

    /**
     * 从扫码内容里拆出 客户编码 / 生产单号 / 箱号，找不到的部分返回空串。
     * 支持：A-B-C、A/B/C、A B C、A|B|C，以及只有 单号-箱号 的简写。
     */
    fun split(code: String): Triple<String, String, String> {
        val cleaned = code.trim()
        val parts = cleaned.split('-', '/', '|', '#', ' ', '\t', ':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return when {
            parts.size >= 3 -> Triple(parts[0], parts[1], parts[2])
            parts.size == 2 -> Triple("", parts[0], parts[1])
            else -> {
                // 纯拼接：用生产单号形如 P + 6~10 位数字 切分
                val regex = Regex("^(.*?)([Pp]\\d{6,10})(\\d{1,5})$")
                val m = regex.matchEntire(cleaned)
                if (m != null) {
                    Triple(m.groupValues[1].trim('-', '/', '|', ' '), m.groupValues[2], m.groupValues[3])
                } else Triple("", "", "")
            }
        }
    }

    fun looksLikeBarcode(code: String): Boolean = normalize(code).length >= MIN_SCAN_NORM_LENGTH

    /** 箱号显示：数值型去掉 .0 */
    fun normalizeBoxNo(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val d = t.toDoubleOrNull()
        return if (d != null && d == d.toLong().toDouble()) d.toLong().toString() else t
    }
}
