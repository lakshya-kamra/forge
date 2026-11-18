package com.laksh.forge.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    val isoDay: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val shortDayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    fun today(): String = LocalDate.now(zone).format(isoDay)

    fun dayOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(isoDay)

    fun daysAgo(n: Int): String = LocalDate.now(zone).minusDays(n.toLong()).format(isoDay)

    fun startOfDayMillis(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayMillis(date: String): Long =
        LocalDate.parse(date).atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()

    fun timeLabel(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().format(timeFmt)

    fun dayLabel(date: String): String = runCatching {
        val d = LocalDate.parse(date)
        when (d) {
            LocalDate.now(zone) -> "Today"
            LocalDate.now(zone).minusDays(1) -> "Yesterday"
            else -> d.format(dayFmt)
        }
    }.getOrDefault(date)

    fun shortLabel(date: String): String =
        runCatching { LocalDate.parse(date).format(shortDayFmt) }.getOrDefault(date)

    fun shortLabel(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(shortDayFmt)

    fun plusDays(date: String, n: Int): String =
        LocalDate.parse(date).plusDays(n.toLong()).format(isoDay)

    fun isFuture(date: String): Boolean =
        runCatching { LocalDate.parse(date).isAfter(LocalDate.now(zone)) }.getOrDefault(false)

    /** Meal slot inferred from the clock, so the user rarely has to change it. */
    fun mealSlotNow(): String {
        val h = java.time.LocalTime.now(zone).hour
        return when {
            h < 11 -> "BREAKFAST"
            h < 16 -> "LUNCH"
            h < 21 -> "DINNER"
            else -> "SNACK"
        }
    }

    fun durationLabel(startMs: Long, endMs: Long?): String {
        val end = endMs ?: System.currentTimeMillis()
        val mins = ((end - startMs) / 60000).coerceAtLeast(0)
        return if (mins < 60) "${mins}m" else "${mins / 60}h ${mins % 60}m"
    }
}
