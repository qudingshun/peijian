package com.peijian.pda.logic

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateText {
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val shortFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)
    private val zone: ZoneId = ZoneId.systemDefault()

    fun dateTime(ms: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun date(ms: Long): String = dateFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun short(ms: Long): String = shortFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun todayStart(): Long = ZonedDateTime.now(zone).toLocalDate()
        .atStartOfDay(zone).toInstant().toEpochMilli()

    fun nowStem(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US).format(ZonedDateTime.now(zone))

    fun docStamp(): String =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).format(ZonedDateTime.now(zone))
}
