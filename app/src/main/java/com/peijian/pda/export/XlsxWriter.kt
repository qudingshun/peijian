package com.peijian.pda.export

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极简 XLSX 写出器（不依赖 Apache POI）。
 * 支持多工作表、表头加粗着色、冻结首行、自动筛选、列宽。
 */
object XlsxWriter {

    data class Sheet(
        val name: String,
        val header: List<String>,
        val rows: List<List<Any?>>,
        val widths: List<Int>? = null
    )

    fun write(file: File, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "至少需要一个工作表" }
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes(sheets.size))
            put(zip, "_rels/.rels", rootRels())
            put(zip, "xl/workbook.xml", workbook(sheets))
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put(zip, "xl/styles.xml", styles())
            sheets.forEachIndexed { i, sheet ->
                put(zip, "xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet))
            }
        }
    }

    private fun put(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes(n: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..n) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s ->
            append("""<sheet name="${escape(safeName(s.name))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(n: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="10"/><name val="微软雅黑"/></font>""" +
            """<font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="微软雅黑"/></font></fonts>""" +
            """<fills count="3"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF1565C0"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""

    private fun sheetXml(sheet: Sheet): String = buildString {
        val colCount = maxOf(sheet.header.size, sheet.rows.maxOfOrNull { it.size } ?: 0)
        val lastRow = sheet.rows.size + 1
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0">""")
        if (sheet.header.isNotEmpty()) {
            append("""<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""")
        }
        append("</sheetView></sheetViews>")
        append("""<sheetFormatPr defaultRowHeight="16"/>""")
        if (colCount > 0) {
            append("<cols>")
            for (c in 1..colCount) {
                val w = sheet.widths?.getOrNull(c - 1) ?: 14
                append("""<col min="$c" max="$c" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        if (sheet.header.isNotEmpty()) {
            append("""<row r="1" ht="22" customHeight="1">""")
            sheet.header.forEachIndexed { i, h ->
                append("""<c r="${col(i + 1)}1" t="inlineStr" s="1"><is><t>${escape(h)}</t></is></c>""")
            }
            append("</row>")
        }
        sheet.rows.forEachIndexed { r, row ->
            val rowNo = r + 2
            append("""<row r="$rowNo">""")
            row.forEachIndexed { c, v -> append(cell(col(c + 1) + rowNo, v)) }
            append("</row>")
        }
        append("</sheetData>")
        if (sheet.header.isNotEmpty() && lastRow >= 1) {
            append("""<autoFilter ref="A1:${col(colCount)}$lastRow"/>""")
        }
        append("</worksheet>")
    }

    private fun cell(ref: String, v: Any?): String = when (v) {
        null -> """<c r="$ref"/>"""
        is Int, is Long, is Short, is Byte -> """<c r="$ref"><v>$v</v></c>"""
        is Double -> if (v.isFinite()) """<c r="$ref"><v>$v</v></c>""" else """<c r="$ref"/>"""
        is Float -> """<c r="$ref"><v>$v</v></c>"""
        is Boolean -> """<c r="$ref" t="b"><v>${if (v) 1 else 0}</v></c>"""
        else -> """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escape(v.toString())}</t></is></c>"""
    }

    private fun col(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim()
        return cleaned.take(31).ifEmpty { "Sheet" }
    }

    fun escape(s: String): String = buildString(s.length + 16) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> {
                    // 去掉 XML 1.0 非法控制字符
                    if (ch.code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') append(ch)
                }
            }
        }
    }
}
