package com.peijian.pda

import com.peijian.pda.data.PackingRepository
import com.peijian.pda.data.entity.ShipmentStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 撤销未上传发货单的资格门禁（纯逻辑） */
class DiscardGuardTest {

    @Test
    fun finishedNotSynced_canDiscard() {
        assertTrue(PackingRepository.DiscardGuard.canDiscard(ShipmentStatus.FINISHED, null))
    }

    @Test
    fun finishedSynced_cannotDiscard() {
        assertFalse(PackingRepository.DiscardGuard.canDiscard(ShipmentStatus.FINISHED, 123L))
    }

    @Test
    fun openNotSynced_cannotDiscard() {
        assertFalse(PackingRepository.DiscardGuard.canDiscard(ShipmentStatus.OPEN, null))
    }

    @Test
    fun voidNotSynced_cannotDiscard() {
        assertFalse(PackingRepository.DiscardGuard.canDiscard(ShipmentStatus.VOID, null))
    }
}
