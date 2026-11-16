package com.peijian.pda

import com.peijian.pda.barcode.BarcodeRules
import com.peijian.pda.importer.PackingImporter
import com.peijian.pda.importer.XlsxReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * 用真实「配件记录.xlsx」验证导入链路：
 *  - XlsxReader 的合并单元格回填 / sharedStrings / 工作表定位
 *  - PackingImporter 的 17 列表头映射、重复箱号合并、数量与日期
 *
 * 生产环境用 Android 的 Xml.newPullParser()，这里注入 kxml2 让同一份代码在 JVM 上跑。
 */
class PackingImportTest {

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
    fun reads_merged_cells_and_headers() {
        assumeTrue("仓库根目录没有 配件记录.xlsx，跳过", xlsx.exists())

        val sheet = XlsxReader.read(xlsx)
        assertEquals(22569, sheet.maxRow)
        assertEquals(16, sheet.maxCol)

        // 表头 17 列
        val header = (0..16).map { sheet.cell(1, it) }
        assertEquals(
            listOf(
                "打包日期", "客户编码", "客户名称", "业务员", "出口国", "生产单号", "箱号",
                "长*宽*高(cm)", "体积(㎥)", "毛重(kg)", "配件编码", "英文描述", "配件名称",
                "数量", "整机型号", "打包单位", "备注"
            ),
            header
        )

        // 合并单元格：源表第 2 行起一箱多行，箱号等必须在每一行都能读到
        assertEquals("P108580", sheet.cell(2, 5))
        assertEquals("1", sheet.cell(2, 6))
        assertTrue(sheet.cell(3, 5).isNotEmpty())
        assertEquals(sheet.cell(2, 5), sheet.cell(3, 5))
    }

    @Test
    fun imports_real_workbook_with_merge_policy() {
        assumeTrue("仓库根目录没有 配件记录.xlsx，跳过", xlsx.exists())

        val started = System.currentTimeMillis()
        val (boxes, result) = PackingImporter.parse(
            xlsx, PackingImporter.DuplicatePolicy.MERGE
        )
        val cost = System.currentTimeMillis() - started
        println("JVM 导入 ${result.boxes} 箱 / ${result.items} 行，用时 ${cost}ms")

        assertEquals(20929, boxes.size)
        assertEquals(20929, result.boxes)
        assertEquals(22554, result.items)
        assertEquals(1639, result.duplicates)
        assertEquals(0, result.skippedRows)
        assertTrue("解析应在 30 秒内完成，实际 ${cost}ms", cost < 30_000)

        // 每箱都有编码/单号/箱号，箱码可拼出
        boxes.forEach { b ->
            assertTrue(b.customerCode.isNotEmpty())
            assertTrue(b.orderNo.isNotEmpty())
            assertTrue(b.boxNo.isNotEmpty())
            assertTrue(b.items.isNotEmpty())
            val code = BarcodeRules.build(b.customerCode, b.orderNo, b.boxNo)
            assertEquals(3, code.split("-").size.coerceAtMost(3))
        }

        // 源表第一箱：W00009 / P108990 / 1，30 件操作说明书 + 其它配件
        val first = boxes.first { it.orderNo == "P108990" && it.boxNo == "1" && it.customerCode == "W00009" }
        assertEquals("W00009-P108990-1", BarcodeRules.build(first.customerCode, first.orderNo, first.boxNo))
        assertEquals("天津博信诚国际贸易有限公司", first.customerName)
        assertEquals("马来西亚", first.country)
        assertNotNull(first.packDate)
        assertEquals(0.057, first.volume!!, 1e-9)
        assertEquals(10.22, first.grossWeight!!, 1e-9)
        assertTrue(first.items.any { it.partCode == "301B80A008256" && it.quantity == 40 })

        // 源表没有箱号的 2 行按 0 占位，保证不丢数据
        assertTrue(boxes.any { it.boxNo == "0" })
    }

    @Test
    fun latest_policy_keeps_box_count() {
        assumeTrue("仓库根目录没有 配件记录.xlsx，跳过", xlsx.exists())

        val (boxes, result) = PackingImporter.parse(
            xlsx, PackingImporter.DuplicatePolicy.LATEST
        )
        // LATEST 只影响重复箱的内容，箱的数量不变
        assertEquals(20929, boxes.size)
        assertEquals(20929, result.boxes)
        assertTrue(result.items > 0)
    }

    @Test
    fun keep_all_policy_suffixes_duplicate_boxes() {
        assumeTrue("仓库根目录没有 配件记录.xlsx，跳过", xlsx.exists())

        val (boxes, _) = PackingImporter.parse(
            xlsx, PackingImporter.DuplicatePolicy.KEEP_ALL
        )
        // 重复箱全部保留，数量多于 MERGE；且没有两个箱共用同一个 (客户,单号,箱号)
        assertTrue(boxes.size > 20929)
        assertEquals(boxes.size, boxes.map { Triple(it.customerCode, it.orderNo, it.boxNo) }.toSet().size)
        assertTrue(boxes.any { it.boxNo.endsWith("-2") })
    }
}
