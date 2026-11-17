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
     *
     * 注意：纯拼接串（W00305P10858012）无分隔符，单号/箱号的边界无法靠正则唯一确定——
     * 旧版用 `([Pp]\d{6,10})(\d{1,5})` 会把箱号 12 误吞成单号 P1085801 / 箱 2。
     * 需要候选拆分时改用 [splitCandidates]，把歧义交给数据库裁决。
     */
    fun split(code: String): Triple<String, String, String> =
        splitCandidates(code).firstOrNull()?.let { Triple(it.first, it.second, it.third) }
            ?: Triple("", "", "")

    /**
     * 返回所有可能的 (客户编码, 生产单号, 箱号) 拆分，按箱号位数从小到大。
     * 对带分隔符的码只有一种拆法；纯拼接串则穷举箱号位数，让调用方查库裁决，
     * 多条命中时报歧义，而不是猜一个错误边界。
     */
    fun splitCandidates(code: String): List<Triple<String, String, String>> {
        val cleaned = code.trim()
        val parts = cleaned.split('-', '/', '|', '#', ' ', '\t', ':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        when {
            parts.size >= 3 -> return listOf(Triple(parts[0], parts[1], parts[2]))
            parts.size == 2 -> return listOf(Triple("", parts[0], parts[1]))
        }

        // 纯拼接：从右往左枚举箱号（1~5 位数字），前缀里找形如 P+数字 的生产单号
        val out = LinkedHashSet<Triple<String, String, String>>()
        for (boxLen in 1..5) {
            if (cleaned.length <= boxLen) break
            val boxPart = cleaned.takeLast(boxLen)
            if (!boxPart.all { it.isDigit() }) continue
            val head = cleaned.dropLast(boxLen)
            // 单号是 head 末尾的 P+至少4位数字；客户编码是其前面的部分
            val m = Regex("^(.*?)([Pp]\\d{4,})$").matchEntire(head) ?: continue
            val customer = m.groupValues[1].trim('-', '/', '|', ' ')
            val order = m.groupValues[2]
            out.add(Triple(customer, order, normalizeBoxNo(boxPart)))
        }
        return out.toList()
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
