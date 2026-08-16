package com.uhmk.pos.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

enum class RangePreset(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    ALL_TIME("All time"),
    CUSTOM("Custom"),
}

/** An inclusive millisecond window, resolved in the device's own timezone. */
data class DateRange(
    val from: Long,
    val to: Long,
    val label: String,
    val preset: RangePreset = RangePreset.CUSTOM,
) {
    companion object {
        private val zone: ZoneId get() = ZoneId.systemDefault()
        private val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

        fun startOfDay(date: LocalDate): Long =
            date.atStartOfDay(zone).toInstant().toEpochMilli()

        fun endOfDay(date: LocalDate): Long =
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        fun of(preset: RangePreset, today: LocalDate = LocalDate.now(zone)): DateRange =
            when (preset) {
                RangePreset.TODAY ->
                    DateRange(startOfDay(today), endOfDay(today), "Today", preset)

                RangePreset.YESTERDAY -> today.minusDays(1).let {
                    DateRange(startOfDay(it), endOfDay(it), "Yesterday", preset)
                }

                RangePreset.THIS_WEEK -> {
                    val start = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    DateRange(startOfDay(start), endOfDay(today), "This week", preset)
                }

                RangePreset.THIS_MONTH -> {
                    val start = today.withDayOfMonth(1)
                    DateRange(startOfDay(start), endOfDay(today), "This month", preset)
                }

                RangePreset.LAST_MONTH -> {
                    val prev = today.minusMonths(1)
                    DateRange(
                        startOfDay(prev.withDayOfMonth(1)),
                        endOfDay(prev.with(TemporalAdjusters.lastDayOfMonth())),
                        "Last month",
                        preset,
                    )
                }

                RangePreset.LAST_7_DAYS ->
                    DateRange(
                        startOfDay(today.minusDays(6)),
                        endOfDay(today),
                        "Last 7 days",
                        preset,
                    )

                RangePreset.LAST_30_DAYS ->
                    DateRange(
                        startOfDay(today.minusDays(29)),
                        endOfDay(today),
                        "Last 30 days",
                        preset,
                    )

                RangePreset.ALL_TIME ->
                    DateRange(0L, Long.MAX_VALUE, "All time", preset)

                RangePreset.CUSTOM ->
                    DateRange(startOfDay(today), endOfDay(today), "Custom", preset)
            }

        fun custom(from: LocalDate, to: LocalDate): DateRange {
            val (a, b) = if (from.isAfter(to)) to to from else from to to
            val label = if (a == b) a.format(dayFormat) else "${a.format(dayFormat)} – ${b.format(dayFormat)}"
            return DateRange(startOfDay(a), endOfDay(b), label, RangePreset.CUSTOM)
        }

        fun toLocalDate(epochMillis: Long): LocalDate =
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    }
}

object Clock {
    private val stampFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")
    private val fileFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun stamp(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(stampFormat)

    fun timeOnly(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormat)

    fun fileStamp(epochMillis: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(fileFormat)
}
