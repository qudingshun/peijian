package com.peijian.pda

import com.peijian.pda.scan.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 声音/震动设置的 JSON 持久化 round-trip 与默认值测试（纯逻辑，不触发音频） */
class HapticsConfigTest {

    @Test
    fun stageKeysAreStable() {
        assertEquals("success", Haptics.Stage.SUCCESS.key)
        assertEquals("wrong_customer", Haptics.Stage.WRONG_CUSTOMER.key)
        assertEquals("not_in_plan", Haptics.Stage.NOT_IN_PLAN.key)
        assertEquals(Haptics.Stage.NOT_IN_PLAN, Haptics.Stage.from("not_in_plan"))
        assertEquals(Haptics.Stage.SUCCESS, Haptics.Stage.from("unknown"))
    }

    @Test
    fun defaultsAlarmStrongerThanSuccess() {
        val ok = Haptics.defaults[Haptics.Stage.SUCCESS]!!
        val alarm = Haptics.defaults[Haptics.Stage.WRONG_CUSTOMER]!!
        assertTrue(alarm.level > ok.level)
        assertTrue(ok.sound)
        assertTrue(alarm.sound)
    }

    @Test
    fun feedbackSettingJsonRoundTrip() {
        val original = Haptics.FeedbackSetting(sound = false, level = 3)
        val json = original.toJson()
        val decoded = Haptics.FeedbackSetting.from(json)
        assertFalse(decoded.sound)
        assertEquals(3, decoded.level)
    }

    @Test
    fun feedbackSettingClampsLevel() {
        val json = org.json.JSONObject().put("sound", true).put("level", 99)
        assertEquals(3, Haptics.FeedbackSetting.from(json).level)
        val neg = org.json.JSONObject().put("sound", true).put("level", -5)
        assertEquals(0, Haptics.FeedbackSetting.from(neg).level)
    }

    @Test
    fun missingJsonFallsBackToDefaults() {
        val d = Haptics.FeedbackSetting.from(null)
        assertTrue(d.sound)
        assertEquals(2, d.level)
    }
}
