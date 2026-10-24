package app.fluvour.srs

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class StudyStatsTest {
    private val today = LocalDate.of(2026, 10, 7)
    private fun days(vararg back: Long) = back.map { today.minusDays(it) }.toSet()

    @Test fun noReviewsMeansNoStreak() = assertEquals(0, StudyStats.streak(emptySet(), today))

    @Test fun todayOpenDoesNotBreakIt() = assertEquals(3, StudyStats.streak(days(1, 2, 3), today))

    @Test fun includesToday() = assertEquals(4, StudyStats.streak(days(0, 1, 2, 3), today))

    @Test fun singleRestDayDoesNotBreak() = assertEquals(4, StudyStats.streak(days(0, 1, 3, 4), today))

    @Test fun twoMissedDaysBreak() = assertEquals(2, StudyStats.streak(days(0, 1, 4, 5), today))

    @Test fun yesterdayAndTodayBothMissedKeepsNothingBeyondOneRest() = assertEquals(2, StudyStats.streak(days(2, 3), today))

    @Test fun threeDaysAgoIsBroken() = assertEquals(0, StudyStats.streak(days(3, 4), today))

    @Test fun countsGroupByLocalDay() {
        val zone = ZoneId.of("UTC")
        val t = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val counts = StudyStats.countsByDay(listOf(t, t + 1000, t - 1000), zone)
        assertEquals(2, counts[today]); assertEquals(1, counts[today.minusDays(1)])
    }
}
