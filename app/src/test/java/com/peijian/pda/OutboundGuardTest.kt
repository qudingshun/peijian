package com.peijian.pda

import com.peijian.pda.data.PackingRepository
import com.peijian.pda.data.entity.BoxStatus
import com.peijian.pda.data.entity.PackingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 出库门禁纯逻辑测试：客户一致性 + 预选白名单 */
class OutboundGuardTest {

    private fun box(id: Long, customer: String) = PackingBox(
        id = id,
        sourceKey = "W-P-$id",
        customerCode = customer,
        customerName = "客户",
        salesperson = "",
        country = "",
        orderNo = "P1",
        boxNo = id.toString(),
        barcode = "W-P-$id",
        barcodeNorm = "WP$id",
        status = BoxStatus.IN_STOCK
    )

    @Test
    fun noRestrictions_passesAnyBox() {
        val v = PackingRepository.OutboundGuard.check("", emptySet(), box(1, "W001"))
        assertTrue(v is PackingRepository.OutboundGuard.Verdict.Pass)
    }

    @Test
    fun customerMismatch_rejected() {
        val v = PackingRepository.OutboundGuard.check("W001", emptySet(), box(1, "W002"))
        assertTrue(v is PackingRepository.OutboundGuard.Verdict.Reject)
        assertEquals(PackingRepository.ScanStatus.WRONG_CUSTOMER, (v as PackingRepository.OutboundGuard.Verdict.Reject).status)
    }

    @Test
    fun customerCaseAndSpaceInsensitive() {
        val v = PackingRepository.OutboundGuard.check(" w001 ", emptySet(), box(1, "W001"))
        assertTrue(v is PackingRepository.OutboundGuard.Verdict.Pass)
    }

    @Test
    fun planNonEmpty_boxOutside_rejected() {
        val v = PackingRepository.OutboundGuard.check("W001", setOf(10L, 11L), box(1, "W001"))
        assertTrue(v is PackingRepository.OutboundGuard.Verdict.Reject)
        assertEquals(PackingRepository.ScanStatus.NOT_IN_PLAN, (v as PackingRepository.OutboundGuard.Verdict.Reject).status)
    }

    @Test
    fun planNonEmpty_boxInside_passes() {
        val v = PackingRepository.OutboundGuard.check("W001", setOf(1L, 11L), box(1, "W001"))
        assertTrue(v is PackingRepository.OutboundGuard.Verdict.Pass)
    }

    @Test
    fun wrongCustomerTakesPriorityOverPlan() {
        val v = PackingRepository.OutboundGuard.check("W001", setOf(1L), box(1, "W999"))
        val r = v as PackingRepository.OutboundGuard.Verdict.Reject
        assertEquals(PackingRepository.ScanStatus.WRONG_CUSTOMER, r.status)
        assertFalse(r.message.isBlank())
    }
}
