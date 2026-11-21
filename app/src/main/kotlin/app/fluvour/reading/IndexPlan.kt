package app.fluvour.reading

/**
 * The plain rules of the background clip library job (roadmap 4.2, phase 2): caps, delays, retry limits.
 * Pure JVM, no Android imports. All numbers are guesses, tune them here after measuring on a real phone.
 */
object IndexPlan {
    /** Videos read per day at most (a video with no subtitles counts too, it costs a page load). */
    const val DAILY_CAP = 100
    /** The library stops growing at this many usable clips (all languages). Above it, pruning cuts it down to [PRUNE_TO]. */
    const val LIBRARY_CAP = 5000
    const val PRUNE_TO = 4500
    /** Rows kept only as markers (no subtitles, unavailable): small, but they stop a dead video from being read again. Oldest are dropped past this. */
    const val MARKER_CAP = 20000
    /** Links waiting to be read, per language. The lowest priority and oldest are dropped first. */
    const val QUEUE_CAP = 2000
    /** At most this many related videos are queued from one well-fitting clip. */
    const val SEED_PER_VIDEO = 8
    /** A link that fails to open this many times is dropped. */
    const val MAX_ATTEMPTS = 3
    /** Random pause between two videos, so the requests do not look like a script. */
    const val DELAY_MIN_MS = 3_000L
    const val DELAY_MAX_MS = 8_000L
    /** This many failures in a row end the run early (YouTube or the network is not answering); WorkManager retries later with backoff. */
    const val FAIL_STREAK = 5
    /** One run stops after this long, well inside Android's limit for background work. */
    const val RUN_MS = 8L * 60 * 1000
    /** How often the job is allowed to start (it only starts while charging and on an unmetered network). */
    const val PERIOD_HOURS = 3L

    /** [r] is a random number from 0 to 1. */
    fun delayMs(r: Double): Long = DELAY_MIN_MS + ((DELAY_MAX_MS - DELAY_MIN_MS) * r.coerceIn(0.0, 1.0)).toLong()
    fun dayKey(nowMs: Long): Long = nowMs / 86_400_000L
    fun remainingToday(doneToday: Int): Int = (DAILY_CAP - doneToday).coerceAtLeast(0)
    fun roomInLibrary(total: Int): Int = (LIBRARY_CAP - total).coerceAtLeast(0)
}
