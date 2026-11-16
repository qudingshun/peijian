package com.peijian.pda.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 极简 XLSX 读取器（不依赖 Apache POI，安装包只增加几十 KB）。
 *
 * 能力边界（够「配件记录.xlsx 打包记录」用）：
 *  - 读取第一个 / 指定工作表
 *  - sharedStrings / inlineStr / 数字 / 布尔
 *  - 合并单元格：用左上角的值回填整块（源表大量使用合并单元格，箱号/单号必须回填）
 *
 * 不支持：公式重算（用 Excel 保存时缓存的 <v>）、日期样式识别（由调用方按列转换序列号）。
 */
object XlsxReader {

    /**
     * 解析器工厂：生产环境用 Android 自带的 Xml.newPullParser()；
     * 单元测试可注入 kxml2，从而在电脑上直接验证真实 Excel 的解析结果。
     */
    internal var parserFactory: (() -> XmlPullParser)? = null

    data class SheetData(
        val rows: List<Map<Int, String>>,
        /** 1-based 最大行号 */
        val maxRow: Int,
        /** 0-based 最大列号 */
        val maxCol: Int
    ) {
        /** 取某行某列，缺省空串 */
        fun cell(row: Int, col: Int): String = rows.getOrNull(row - 1)?.get(col) ?: ""
    }

    fun read(file: File, sheetIndex: Int = 0): SheetData = ZipFile(file).use { zip ->
        val shared = runCatching { readSharedStrings(zip) }.getOrDefault(emptyList())
        val sheetPath = runCatching { resolveSheetPath(zip, sheetIndex) }
            .getOrDefault("xl/worksheets/sheet${sheetIndex + 1}.xml")
        val grid = LinkedHashMap<Int, HashMap<Int, String>>()
        val merges = ArrayList<String>()
        var maxCol = 0

        zip.getInputStream(zip.getEntry(sheetPath) ?: error("找不到工作表 $sheetPath")).use { input ->
            val parser = newParser(input)
            var row = 0
            var col = -1
            var cellType: String? = null
            var inValue = false
            var inInline = false
            var value = StringBuilder()
            var inlineText = StringBuilder()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "row" -> {
                            row = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (row + 1)
                            col = -1
                        }
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t")
                            val ref = parser.getAttributeValue(null, "r")
                            col = ref?.let { columnIndex(it) } ?: (col + 1)
                            if (col > maxCol) maxCol = col
                            value.setLength(0)
                            inlineText.setLength(0)
                        }
                        "v" -> inValue = true
                        "is" -> inInline = true
                        "t" -> if (inInline) {
                            // 富文本里会有多个 <t>，直接拼接
                            inlineText.append(parser.nextText())
                        }
                        "mergeCell" -> parser.getAttributeValue(null, "ref")?.let { merges.add(it) }
                    }
                    XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "v" -> inValue = false
                        "is" -> inInline = false
                        "c" -> {
                            val text = when (cellType) {
                                "s" -> value.toString().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                "inlineStr" -> inlineText.toString()
                                "b" -> if (value.toString() == "1") "TRUE" else "FALSE"
                                "e" -> ""
                                else -> value.toString()
                            }
                            if (text.isNotEmpty() && row > 0 && col >= 0) {
                                grid.getOrPut(row) { HashMap() }[col] = text
                            }
                        }
                    }
                }
                parser.next()
            }
        }

        // 合并单元格回填：源表把同一箱的多行合并在一列里，不回填会丢掉箱号/单号
        for (ref in merges) {
            val (start, end) = parseRange(ref) ?: continue
            val v = grid[start.first]?.get(start.second) ?: continue
            for (r in start.first..end.first) {
                val rowMap = grid.getOrPut(r) { HashMap() }
                for (c in start.second..end.second) {
                    if (!rowMap.containsKey(c)) rowMap[c] = v
                }
            }
        }

        val maxRow = grid.keys.maxOrNull() ?: 0
        val rows = (1..maxRow).map { r -> grid[r] ?: emptyMap() }
        SheetData(rows, maxRow, maxCol)
    }

    private fun newParser(input: InputStream): XmlPullParser =
        (parserFactory?.invoke() ?: Xml.newPullParser()).apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        zip.getInputStream(entry).use { input ->
            val parser = newParser(input)
            var inSi = false
            val sb = StringBuilder()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "t" -> if (inSi) sb.append(parser.nextText())
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "si") {
                        out.add(sb.toString())
                        inSi = false
                    }
                }
                parser.next()
            }
        }
        return out
    }

    /** 通过 workbook.xml + rels 找目标工作表路径 */
    private fun resolveSheetPath(zip: ZipFile, index: Int): String {
        val relId = runCatching {
            val wb = zip.getEntry("xl/workbook.xml") ?: return@runCatching null
            var id: String? = null
            var sheet = 0
            zip.getInputStream(wb).use { input ->
                val p = newParser(input)
                while (p.eventType != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                        if (sheet == index) {
                            id = p.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "id"
                            ) ?: p.getAttributeValue(null, "r:id")
                            break
                        }
                        sheet++
                    }
                    p.next()
                }
            }
            id
        }.getOrNull() ?: return "xl/worksheets/sheet${index + 1}.xml"

        val rels = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return "xl/worksheets/sheet${index + 1}.xml"
        zip.getInputStream(rels).use { input ->
            val p = newParser(input)
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    if (p.getAttributeValue(null, "Id") == relId) {
                        val target = p.getAttributeValue(null, "Target") ?: break
                        return when {
                            target.startsWith("/") -> target.removePrefix("/")
                            target.startsWith("xl/") -> target
                            else -> "xl/$target"
                        }
                    }
                }
                p.next()
            }
        }
        return "xl/worksheets/sheet${index + 1}.xml"
    }

    /** "AB12" -> 列索引 27 */
    fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return (n - 1).coerceAtLeast(0)
    }

    /** "G135:G138" -> ((135,6),(138,6)) */
    private fun parseRange(ref: String): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val parts = ref.split(':')
        fun cell(s: String): Pair<Int, Int>? {
            val m = Regex("([A-Za-z]+)(\\d+)").find(s) ?: return null
            return m.groupValues[2].toInt() to columnIndex(m.groupValues[1])
        }
        val a = cell(parts[0]) ?: return null
        val b = cell(parts.getOrElse(1) { parts[0] }) ?: return null
        return a to b
    }

    /** Excel 序列号（1900 日期系统）→ epoch mills，取当天 00:00 */
    fun excelDateToMillis(serial: Double): Long? {
        if (serial < 1 || serial > 80000) return null
        val days = serial.toLong()
        val date = java.time.LocalDate.of(1899, 12, 30).plusDays(days)
        return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
