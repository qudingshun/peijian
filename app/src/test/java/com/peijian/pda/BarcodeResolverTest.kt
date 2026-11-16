package com.peijian.pda

import com.peijian.pda.barcode.BarcodeRules
import com.peijian.pda.data.PackingRepository
import com.peijian.pda.data.entity.BoxStatus
import com.peijian.pda.data.entity.PackingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码候选裁决的回归测试。
 * 重点：任何候选层级出现多条都必须报歧义，绝不能按「唯一在库」猜测。
 */
class BarcodeResolverTest {

    private fun box(barcode: String, status: String = BoxStatus.IN_STOCK) = PackingBox(
        sourceKey = barcode,
        customerCode = "W00305",
        customerName = "客户",
        salesperson = "",
        country = "",
        orderNo = "P108580",
        boxNo = "1",
        barcode = barcode,
        barcodeNorm = BarcodeRules.normalize(barcode),
        status = status
    )

    @Test
    fun exact_single_wins_over_ambiguous_tail() {
        val exact = listOf(box("W00305-P108580-1"))
        val tail = listOf(box("XXW00305-P108580-1"), box("YYW00305-P108580-1"))
        val outcome = PackingRepository.ScanResolver.resolve(exact, tail, emptyList())

        assertTrue(outcome is PackingRepository.ScanResolver.Outcome.Unique)
        assertEquals("W00305-P108580-1", (outcome as PackingRepository.ScanResolver.Outcome.Unique).box.barcode)
    }

    @Test
    fun multiple_exact_is_ambiguous_even_if_only_one_in_stock() {
        val exact = listOf(
            box("W00305-P108580-1", BoxStatus.SHIPPED),
            box("W00305-P108580-1", BoxStatus.IN_STOCK)
        )
        val outcome = PackingRepository.ScanResolver.resolve(exact, emptyList(), emptyList())

        assertTrue(outcome is PackingRepository.ScanResolver.Outcome.Ambiguous)
        assertEquals(2, (outcome as PackingRepository.ScanResolver.Outcome.Ambiguous).boxes.size)
    }

    @Test
    fun multiple_tail_is_ambiguous() {
        val tail = listOf(box("XXW00305-P108580-1"), box("YYW00305-P108580-1"))
        val outcome = PackingRepository.ScanResolver.resolve(emptyList(), tail, emptyList())

        assertTrue(outcome is PackingRepository.ScanResolver.Outcome.Ambiguous)
    }

    @Test
    fun fallback_is_used_only_when_no_exact_or_tail() {
        val fallback = listOf(box("W00305-P108580-1"))
        val outcome = PackingRepository.ScanResolver.resolve(emptyList(), emptyList(), fallback)

        assertTrue(outcome is PackingRepository.ScanResolver.Outcome.Unique)
    }

    @Test
    fun nothing_matched_is_not_found() {
        assertTrue(
            PackingRepository.ScanResolver.resolve(emptyList(), emptyList(), emptyList())
                is PackingRepository.ScanResolver.Outcome.NotFound
        )
    }

    @Test
    fun short_codes_are_rejected_before_lookup() {
        assertTrue(!BarcodeRules.looksLikeBarcode("1"))
        assertTrue(!BarcodeRules.looksLikeBarcode("801"))
        assertTrue(BarcodeRules.looksLikeBarcode("P108580-1"))
    }
}
