package com.peijian.pda

import com.peijian.pda.barcode.BarcodeRules
import com.peijian.pda.importer.PackingImporter
import com.peijian.pda.importer.XlsxReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * 双端解析 golden 基准。
 *
 * 服务端 pc-server/server.py 的 parse_xlsx 与 PDA 的 PackingImporter 是两套独立实现。
 * 本测试把「同一份 配件记录.xlsx 两端 MERGE 解析必须一致」固化为断言，防止任一端
 * 后续改动造成箱码/数量漂移。基准值由 pc-server/golden_check.py 从服务端实现导出，
 * 改动解析规则时必须同时更新两端与脚本。
 */
class CrossEndParseGoldenTest {

    private val xlsx = File("../配件记录.xlsx")
    private val originalFactory = XlsxReader.parserFactory

    @Before
    fun injectParser() {
        XlsxReader.parserFactory = { KXmlParser() }
    }

    @After
    fun restoreParser() {
        XlsxReader.parserFactory = originalFactory
    }

    @Test
    fun merge_result_matches_server_golden() {
        assumeTrue("仓库根目录没有 配件记录.xlsx，跳过", xlsx.exists())

        val (boxes, result) = PackingImporter.parse(xlsx, PackingImporter.DuplicatePolicy.MERGE)

        // 与 golden_check.py 输出逐项对齐
        assertEquals("箱数", 20929, boxes.size)
        assertEquals("明细数", 22554, result.items)
        assertEquals("重复合并次数", 1639, result.duplicates)
        assertEquals("跳过行数", 0, result.skippedRows)
        assertEquals("数量总和（关键指纹）", 608554, boxes.sumOf { b -> b.items.sumOf { it.quantity } })

        // source_key 词序边界，确保箱码口径一致
        val keys = boxes.map { "${it.customerCode}|${it.orderNo}|${it.boxNo}" }.sorted()
        assertEquals(listOf("W00009|P108990|1", "W00009|P108990|10", "W00009|P108990|11"), keys.take(3))
        assertEquals(
            listOf("W00483|P113944|9", "W00483|P113976|1", "W00483|P114055|1"),
            keys.takeLast(3)
        )

        // 首箱规范化字段
        val first = boxes.first { it.orderNo == "P108990" && it.boxNo == "1" && it.customerCode == "W00009" }
        assertEquals("W00009-P108990-1", BarcodeRules.build(first.customerCode, first.orderNo, first.boxNo))
        // 2024-07-03 00:00 UTC+8：固定 zone 后不随宿主/设备时区漂移（见 PackDate）
        assertEquals(1719936000000L, first.packDate)
        assertEquals(0.057, first.volume!!, 1e-9)
        assertEquals(10.22, first.grossWeight!!, 1e-9)
        assertTrue(first.items.any { it.partCode == "301B80A008256" && it.quantity == 40 })
    }
}
