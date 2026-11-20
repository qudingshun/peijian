package com.peijian.pda.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 扫码反馈：按「环节」分别设置声音开关与震动强度。
 *
 * 每个环节有独立的 [FeedbackSetting]（sound 开关 + level 强度 0~3），
 * 由 [Prefs] 持久化。级别映射：
 *   0 = 关闭；1 = 轻（60ms）；2 = 中（120ms）；3 = 强（240ms + 长间隔）
 * 报警环节（客户不符 / 不在预选范围）失败时声音用系统告警音，其他环节用普通提示音。
 */
object Haptics {

    /** 需要区分反馈的扫码环节 */
    enum class Stage(val key: String, val label: String, val alarm: Boolean = false) {
        SUCCESS("success", "登记成功"),
        DUPLICATE("duplicate", "重复扫码"),
        NOT_FOUND("not_found", "未知箱码"),
        ALREADY_SHIPPED("already_shipped", "已发出箱"),
        AMBIGUOUS("ambiguous", "箱码歧义"),
        WRONG_CUSTOMER("wrong_customer", "客户不符", alarm = true),
        NOT_IN_PLAN("not_in_plan", "不在预选范围", alarm = true);

        companion object {
            fun from(key: String?): Stage = entries.firstOrNull { it.key == key } ?: SUCCESS
        }
    }

    data class FeedbackSetting(val sound: Boolean = true, val level: Int = 2) {
        companion object {
            fun from(json: org.json.JSONObject?): FeedbackSetting = json?.let {
                FeedbackSetting(it.optBoolean("sound", true), it.optInt("level", 2).coerceIn(0, 3))
            } ?: FeedbackSetting()
        }

        fun toJson(): org.json.JSONObject = org.json.JSONObject()
            .put("sound", sound).put("level", level)
    }

    /** 全部环节的默认设置：成功轻震短音，告警强震长音 */
    val defaults: Map<Stage, FeedbackSetting> = Stage.entries.associateWith { stage ->
        when (stage) {
            Stage.SUCCESS -> FeedbackSetting(true, 1)
            Stage.WRONG_CUSTOMER, Stage.NOT_IN_PLAN -> FeedbackSetting(true, 3)
            else -> FeedbackSetting(true, 2)
        }
    }

    private const val TONE_OK = ToneGenerator.TONE_PROP_BEEP
    private const val TONE_ALARM = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
    private var toneGenerator: ToneGenerator? = null

    /** 播放一次环节反馈；[setting] 为 null 时用该环节默认值 */
    fun feedback(context: Context, stage: Stage, setting: FeedbackSetting?) {
        val s = setting ?: defaults[stage] ?: FeedbackSetting()
        if (s.level > 0) vibrate(context, s.level)
        if (s.sound) sound(stage, s.level)
    }

    /** 兼容旧调用 */
    fun success(context: Context) = feedback(context, Stage.SUCCESS, null)
    fun failure(context: Context) = feedback(context, Stage.NOT_FOUND, null)

    private fun vibrate(context: Context, level: Int) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            val pattern = when (level) {
                1 -> longArrayOf(0, 60)
                2 -> longArrayOf(0, 120, 80, 120)
                else -> longArrayOf(0, 220, 100, 220, 100, 220)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = IntArray(pattern.size) { i -> if (i % 2 == 1) 255 else 0 }
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun sound(stage: Stage, level: Int) {
        runCatching {
            val gen = toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                .also { toneGenerator = it }
            val tone = if (stage.alarm) TONE_ALARM else TONE_OK
            val duration = when {
                stage.alarm -> 700
                level >= 3 -> 400
                else -> 180
            }
            gen.startTone(tone, duration)
            // 复用生成器，避免频繁创建；延后停音由 startTone 的 duration 自动完成
        }
    }

    /** 应用退出前释放音频资源（可选） */
    fun release() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}
