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

    @Test
    fun pure_concat_enumerates_all_box_lengths() {
        // W00305 + P108580 + 12：真正的拆分（箱号 12）必须在候选里
        val cands = BarcodeRules.splitCandidates("W00305P10858012")
        assertTrue("箱号12的拆分应存在", cands.any { it.second == "P108580" && it.third == "12" })
        // 旧正则会切出 P1085801/2，也应作为候选交给数据库裁决
        assertTrue("箱号2的拆分也应作为候选", cands.any { it.second == "P1085801" && it.third == "2" })
    }

    @Test
    fun three_digit_box_number_is_preserved() {
        val cands = BarcodeRules.splitCandidates("W00305P108580123")
        assertTrue("三位数箱号123应被保留为一个候选",
            cands.any { it.second == "P108580" && it.third == "123" })
    }

    @Test
    fun dashed_code_has_single_candidate() {
        val cands = BarcodeRules.splitCandidates("W00305-P108580-12")
        assertEquals(1, cands.size)
        assertEquals(Triple("W00305", "P108580", "12"), cands.first())
    }
}
