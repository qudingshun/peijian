package com.peijian.pda.importer

import com.peijian.pda.barcode.BarcodeRules
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 把「配件记录.xlsx」的打包记录工作表解析成箱/配件。
 *
 * 列顺序（源表固定）：
 *  0 打包日期 | 1 客户编码 | 2 客户名称 | 3 业务员 | 4 出口国 | 5 生产单号 | 6 箱号
 *  7 长*宽*高 | 8 体积 | 9 毛重 | 10 配件编码 | 11 英文描述 | 12 配件名称 | 13 数量
 *  14 整机型号 | 15 打包单位 | 16 备注
 *
 * 源表大量使用合并单元格（同一箱多行配件），XlsxReader 已按合并区域回填。
 */
object PackingImporter {

    /** 重复箱号（同客户+单号+箱号）处理方式 */
    object DuplicatePolicy {
        /** 合并：同箱同配件编码数量相加（默认） */
        const val MERGE = "MERGE"
        /** 以最后一行为准：整箱内容取最后一次打包记录 */
        const val LATEST = "LATEST"
        /** 全部保留：重复箱号自动加 -2 / -3 后缀，条码同步变化 */
        const val KEEP_ALL = "KEEP_ALL"
    }

    private val dateFormats = listOf(
        "yyyy-MM-dd", "yyyy/M/d", "yyyy.MM.dd", "yyyyMMdd", "M/d/yyyy", "yyyy-M-d"
    )
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun parse(
        file: File,
        policy: String = DuplicatePolicy.MERGE,
        sheetIndex: Int = 0
    ): Pair<List<BoxPayload>, ImportResult> {
        val started = System.currentTimeMillis()
        val sheet = XlsxReader.read(file, sheetIndex)
        val headerRow = findHeaderRow(sheet)
        require(headerRow > 0) { "没有找到表头（需要包含「配件编码」「生产单号」）" }

        val header = headerIndex(sheet, headerRow)
        fun idx(name: String, fallback: Int) = header[name] ?: fallback

        val cDate = idx("打包日期", 0)
        val cCust = idx("客户编码", 1)
        val cCustName = idx("客户名称", 2)
        val cSales = idx("业务员", 3)
        val cCountry = idx("出口国", 4)
        val cOrder = idx("生产单号", 5)
        val cBox = idx("箱号", 6)
        val cDim = idx("长*宽*高(cm)", 7)
        val cVol = idx("体积(㎥)", 8)
        val cWeight = idx("毛重(kg)", 9)
        val cPCode = idx("配件编码", 10)
        val cEName = idx("英文描述", 11)
        val cName = idx("配件名称", 12)
        val cQty = idx("数量", 13)
        val cModel = idx("整机型号", 14)
        val cUnit = idx("打包单位", 15)
        val cRemark = idx("备注", 16)

        // 先按 sourceKey 聚合
        val groups = LinkedHashMap<String, MutableBox>()
        var skipped = 0
        var duplicates = 0

        for (r in headerRow + 1..sheet.maxRow) {
            val partCode = sheet.cell(r, cPCode).trim()
            val orderNo = sheet.cell(r, cOrder).trim()
            if (partCode.isEmpty() && orderNo.isEmpty()) continue
            if (partCode.isEmpty()) { skipped++; continue }

            val qty = parseQty(sheet.cell(r, cQty))
            val customerCode = sheet.cell(r, cCust).trim()
            val boxNoRaw = BarcodeRules.normalizeBoxNo(sheet.cell(r, cBox))
            // 极少数没有箱号的记录：用 0 占位，保证能入库
            val boxNo = boxNoRaw.ifEmpty { "0" }
            val key = "$customerCode|$orderNo|$boxNo"
            val date = parseDate(sheet.cell(r, cDate))

            val item = ItemPayload(
                partCode = partCode,
                englishName = sheet.cell(r, cEName).trim(),
                partName = sheet.cell(r, cName).trim(),
                quantity = qty,
                machineModel = sheet.cell(r, cModel).trim(),
                remark = sheet.cell(r, cRemark).trim()
            )

            val existing = groups[key]
            if (existing == null) {
                groups[key] = MutableBox(
                    customerCode = customerCode,
                    customerName = sheet.cell(r, cCustName).trim(),
                    salesperson = sheet.cell(r, cSales).trim(),
                    country = sheet.cell(r, cCountry).trim(),
                    orderNo = orderNo,
                    boxNo = boxNo,
                    packDate = date,
                    dimension = sheet.cell(r, cDim).trim(),
                    volume = parseDouble(sheet.cell(r, cVol)),
                    grossWeight = parseDouble(sheet.cell(r, cWeight)),
                    packingUnit = sheet.cell(r, cUnit).trim(),
                    remark = sheet.cell(r, cRemark).trim(),
                    sourceRow = r
                ).also { it.items.add(item) }
            } else {
                duplicates++
                when (policy) {
                    DuplicatePolicy.LATEST -> {
                        // 只有更晚的打包日期才覆盖（同一日期保留并合并数量，避免丢行）
                        val prevDate = existing.packDate
                        if (date != null && (prevDate == null || date >= prevDate)) {
                            existing.overwriteHeader(date, r)
                            existing.items.clear()
                            existing.items.add(item)
                        } else {
                            existing.mergeItem(item)
                        }
                    }
                    DuplicatePolicy.KEEP_ALL -> {
                        var n = 2
                        var newKey = "$key#$n"
                        while (groups.containsKey(newKey)) { n++; newKey = "$key#$n" }
                        groups[newKey] = MutableBox(
                            customerCode = customerCode,
                            customerName = sheet.cell(r, cCustName).trim(),
                            salesperson = sheet.cell(r, cSales).trim(),
                            country = sheet.cell(r, cCountry).trim(),
                            orderNo = orderNo,
                            boxNo = "$boxNo-$n",
                            packDate = date,
                            dimension = sheet.cell(r, cDim).trim(),
                            volume = parseDouble(sheet.cell(r, cVol)),
                            grossWeight = parseDouble(sheet.cell(r, cWeight)),
                            packingUnit = sheet.cell(r, cUnit).trim(),
                            remark = sheet.cell(r, cRemark).trim(),
                            sourceRow = r
                        ).also { it.items.add(item) }
                    }
                    else -> existing.mergeItem(item)
                }
            }
        }

        val boxes = groups.values.map { it.toPayload() }
        return boxes to ImportResult(
            boxes = boxes.size,
            items = boxes.sumOf { it.items.size },
            inserted = 0,
            updated = 0,
            duplicates = duplicates,
            skippedRows = skipped,
            durationMs = System.currentTimeMillis() - started
        )
    }

    private fun findHeaderRow(sheet: XlsxReader.SheetData): Int {
        for (r in 1..minOf(30, sheet.maxRow)) {
            var hasPart = false
            var hasOrder = false
            for (c in 0..sheet.maxCol) {
                val v = sheet.cell(r, c)
                if (v.contains("配件编码")) hasPart = true
                if (v.contains("生产单号")) hasOrder = true
            }
            if (hasPart && hasOrder) return r
        }
        return 0
    }

    private fun headerIndex(sheet: XlsxReader.SheetData, row: Int): Map<String, Int> {
        val map = HashMap<String, Int>()
        for (c in 0..sheet.maxCol) {
            val name = sheet.cell(row, c).trim()
            if (name.isNotEmpty()) map[name] = c
        }
        return map
    }

    fun parseQty(raw: String): Int {
        val t = raw.trim()
        if (t.isEmpty()) return 0
        val d = t.toDoubleOrNull() ?: return 0
        return d.roundToInt()
    }

    private fun parseDouble(raw: String): Double? {
        val t = raw.trim().replace(",", "")
        return t.toDoubleOrNull()
    }

    /** 支持 Excel 日期序列号与常见文本日期 */
    fun parseDate(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { d ->
            XlsxReader.excelDateToMillis(d)?.let { return it }
        }
        for (fmt in dateFormats) {
            runCatching {
                val d = LocalDate.parse(t, DateTimeFormatter.ofPattern(fmt, Locale.US))
                return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        // 带时间的文本：取前 10 位
        if (t.length >= 10) {
            runCatching {
                val d = LocalDate.parse(t.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE)
                return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }

    fun formatDate(millis: Long?): String =
        millis?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormat)
        } ?: ""

    private class MutableBox(
        var customerCode: String,
        var customerName: String,
        var salesperson: String,
        var country: String,
        var orderNo: String,
        var boxNo: String,
        var packDate: Long?,
        var dimension: String,
        var volume: Double?,
        var grossWeight: Double?,
        var packingUnit: String,
        var remark: String,
        val sourceRow: Int
    ) {
        val items = ArrayList<ItemPayload>()

        fun mergeItem(item: ItemPayload) {
            val i = items.indexOfFirst { it.partCode == item.partCode && it.machineModel == item.machineModel }
            if (i >= 0) {
                val old = items[i]
                items[i] = old.copy(quantity = old.quantity + item.quantity)
            } else {
                items.add(item)
            }
        }

        fun overwriteHeader(date: Long?, row: Int) {
            packDate = date
        }

        fun toPayload(): BoxPayload = BoxPayload(
            customerCode = customerCode,
            customerName = customerName,
            salesperson = salesperson,
            country = country,
            orderNo = orderNo,
            boxNo = boxNo,
            packDate = packDate,
            dimension = dimension,
            volume = volume,
            grossWeight = grossWeight,
            packingUnit = packingUnit,
            remark = remark,
            items = items,
            sourceRow = sourceRow
        )
    }
}
