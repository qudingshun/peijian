package com.peijian.pda

import com.peijian.pda.barcode.BarcodeRules
import com.peijian.pda.importer.PackingImporter
import com.peijian.pda.importer.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.peijian.pda.logic.PackDate
import java.time.LocalDate

class BarcodeRulesTest {

    @Test
    fun normalize_ignoresSeparatorsAndCase() {
        assertEquals("W00305P1085801", BarcodeRules.normalize("W00305-P108580-1"))
        assertEquals("W00305P1085801", BarcodeRules.normalize("w00305/p108580/1"))
        assertEquals("W00305P1085801", BarcodeRules.normalize(" W00305 P108580 1\t"))
    }

    @Test
    fun build_usesSeparatorAndSkipsBlanks() {
        assertEquals("W00305-P108580-1", BarcodeRules.build("W00305", "P108580", "1"))
        assertEquals("W00305P1085801", BarcodeRules.build("W00305", "P108580", "1", ""))
        assertEquals("P108580-1", BarcodeRules.build("", "P108580", "1"))
    }

    @Test
    fun split_supportsCommonShapes() {
        assertEquals(Triple("W00305", "P108580", "1"), BarcodeRules.split("W00305-P108580-1"))
        assertEquals(Triple("W00305", "P108580", "1"), BarcodeRules.split("W00305/P108580/1"))
        assertEquals(Triple("W00305", "P108580", "1"), BarcodeRules.split("W00305|P108580|1"))
        // 只有 单号-箱号
        assertEquals(Triple("", "P108580", "1"), BarcodeRules.split("P108580-1"))
        // 纯拼接：客户编码 + P 开头单号 + 箱号
        assertEquals(Triple("W00305", "P108580", "1"), BarcodeRules.split("W00305P1085801"))
    }

    @Test
    fun normalizeBoxNo_dropsTrailingZero() {
        assertEquals("1", BarcodeRules.normalizeBoxNo("1"))
        assertEquals("1", BarcodeRules.normalizeBoxNo("1.0"))
        assertEquals("12", BarcodeRules.normalizeBoxNo(" 12 "))
        assertEquals("A-1", BarcodeRules.normalizeBoxNo("A-1"))
        assertEquals("", BarcodeRules.normalizeBoxNo("  "))
    }

    @Test
    fun looksLikeBarcode_rejectsTinyStrings() {
        assertTrue(BarcodeRules.looksLikeBarcode("W00305-P108580-1"))
        assertTrue(!BarcodeRules.looksLikeBarcode("12"))
    }

    @Test
    fun quantity_parsesExcelNumbers() {
        assertEquals(30, PackingImporter.parseQty("30"))
        assertEquals(30, PackingImporter.parseQty("30.4"))
        assertEquals(31, PackingImporter.parseQty("30.6"))
        assertEquals(0, PackingImporter.parseQty(""))
        assertEquals(0, PackingImporter.parseQty("abc"))
    }

    @Test
    fun date_parsesExcelSerialAndText() {
        val expected = PackDate.atDay(LocalDate.of(2024, 5, 5))
        assertEquals(1714838400000L, expected) // 2024-05-05 00:00 UTC+8，固化不随时间区漂移
        // 45417 是源表第一行的打包日期（2024-05-05）
        assertEquals(expected, XlsxReader.excelDateToMillis(45417.0))
        assertEquals(expected, PackingImporter.parseDate("45417"))
        assertEquals(expected, PackingImporter.parseDate("2024-05-05"))
        assertEquals(expected, PackingImporter.parseDate("2024/5/5"))
    }

    @Test
    fun date_rejectsGarbage() {
        assertNull(PackingImporter.parseDate(""))
        assertNull(PackingImporter.parseDate("不是日期"))
        assertNull(XlsxReader.excelDateToMillis(0.0))
    }

    @Test
    fun columnIndex_mapsExcelRefs() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(6, XlsxReader.columnIndex("G135"))
        assertEquals(27, XlsxReader.columnIndex("AB2"))
        assertEquals(16, XlsxReader.columnIndex("Q9"))
    }

    @Test
    fun formatDate_isStable() {
        val ms = PackDate.atDay(LocalDate.of(2024, 5, 5))
        assertEquals("2024-05-05", PackingImporter.formatDate(ms))
        assertEquals("", PackingImporter.formatDate(null))
        assertNotNull(PackingImporter.formatDate(System.currentTimeMillis()))
    }
}
