package app.fluvour.srs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsrsTest {
    /** 3377 reviews generated from the official ts-fsrs 5.4.2; every field must match after every review. */
    @Test fun matchesTsFsrsGoldenVectors() {
        val url = javaClass.getResource("/fsrs-vectors.txt")!!
        val (checked, problems) = GoldenVectors.run(File(url.toURI()))
        assertTrue(checked > 3000)
        assertEquals(emptyList<String>(), problems.take(5))
    }

    @Test fun newGoodUsesW2AndStepsToTenMinutes() {
        val c = Scheduler().review(CardState(), Rating.GOOD, 0L)
        assertEquals(Phase.LEARNING, c.phase)
        assertEquals(2.3065, c.stability, 1e-9)
        assertEquals(10 * Scheduler.MINUTE, c.dueMillis)
    }

    @Test fun fuzzIsDeterministicAndInRange() {
        val s = Scheduler(SchedulerConfig(enableFuzz = true, learningStepsMinutes = emptyList()))
        val a = s.review(CardState(), Rating.EASY, 1_000_000L)
        val b = s.review(CardState(), Rating.EASY, 1_000_000L)
        assertEquals(a.scheduledDays, b.scheduledDays)
        assertTrue(a.scheduledDays in 6..16)
    }
}
