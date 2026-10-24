package app.fluvour.srs

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Streak and per-day counts from review timestamps. Pure Kotlin, no Android, so it can be tested and ported.
 *
 * Streak rule (rest days): the streak is the number of days studied in an unbroken run, where a single missed
 * day in a row is a rest day that does not break it. Two missed days in a row end the run. Today counts as
 * still open: not having studied yet today never breaks anything.
 */
object StudyStats {
    fun countsByDay(reviewTimes: List<Long>, zone: ZoneId): Map<LocalDate, Int> =
        reviewTimes.groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()

    fun streak(studied: Set<LocalDate>, today: LocalDate): Int {
        if (studied.isEmpty()) return 0
        val earliest = studied.min()
        var day = if (today in studied) today else today.minusDays(1)
        var streak = 0
        var missed = 0
        while (!day.isBefore(earliest)) {
            if (day in studied) { streak++; missed = 0 } else { missed++; if (missed >= 2) break }
            day = day.minusDays(1)
        }
        return streak
    }
}
