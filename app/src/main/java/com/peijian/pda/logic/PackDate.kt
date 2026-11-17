package com.peijian.pda.logic

import java.time.LocalDate
import java.time.ZoneId

/**
 * 打包日期的唯一权威规则（与服务端 pc-server/server.py 的 PACK_DATE_TZ 对齐）。
 *
 * 打包日期来自 Excel 的「日历日」，不是时刻；固定锚定 UTC+8 的当天 00:00，
 * 这样服务端部署在任意时区、PDA 设备在任意时区，同一序列号都得到同一个 epoch 毫秒，
 * 导出/显示也永远是同一个日历日。
 *
 * 注意：设备事件时间（createdAt / scannedAt / shippedAt）表示真实时刻，
 * 由 [DateText] 按本地时区显示，不走这里。
 */
object PackDate {
    /** 固定偏移，不用 Asia/Shanghai，避免历史夏令时影响序列号换算。 */
    val ZONE: ZoneId = ZoneId.of("UTC+08:00")

    /** Excel 1900 日期系统的日期基准（兼容 1900-02-29）。 */
    private val EPOCH_DATE: LocalDate = LocalDate.of(1899, 12, 30)

    /** 序列号 → UTC+8 当天 00:00 的 epoch 毫秒。 */
    fun fromSerial(serial: Double): Long? {
        if (serial < 1 || serial > 80000) return null
        return atDay(EPOCH_DATE.plusDays(serial.toLong()))
    }

    /** 某个日历日 → UTC+8 当天 00:00 的 epoch 毫秒。 */
    fun atDay(date: LocalDate): Long =
        date.atStartOfDay(ZONE).toInstant().toEpochMilli()
}
