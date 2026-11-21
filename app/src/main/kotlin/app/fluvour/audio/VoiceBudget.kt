package app.fluvour.audio

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/**
 * The daily allowance for Gemini voice requests made ahead of time in the background. The free limit is not hardcoded: it starts at a
 * cautious guess, drops to the number of requests actually made on a day when every key said "limit", and grows a little after a day that
 * used most of its allowance without any limit. Only 80% of the ceiling is used, so the speaker button keeps working. Requests made by
 * taps count too (see TtsManager.fetch). Gemini's daily quotas reset at midnight Pacific time, so the day is counted in that zone.
 */
class VoiceBudget(ctx: Context) {
    private val p = ctx.getSharedPreferences("voice_budget", Context.MODE_PRIVATE)

    /** Settings > AI keys > Make voices ahead. */
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply() }

    /** Pause between background requests; doubles when the per-minute limit was hit, up to 30 seconds. */
    var gapMs: Long
        get() = p.getLong("gap", 5_000L)
        set(v) { p.edit().putLong("gap", v.coerceIn(3_000L, 30_000L)).apply() }

    private fun today(): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }
    private fun ceiling() = p.getInt("ceiling", START_CEILING)
    private fun cap(ceiling: Int) = (ceiling * 0.8).toInt()

    private fun roll() {
        val d = today()
        val saved = p.getLong("day", -1L)
        if (saved == d) return
        if (saved != -1L && !p.getBoolean("limited", false) && p.getInt("count", 0) >= cap(ceiling()) * 0.9) {
            p.edit().putInt("ceiling", (ceiling() * 1.25).toInt()).apply()   // a full day with no limit: try a bit more
        }
        p.edit().putLong("day", d).putInt("count", 0).putBoolean("limited", false).apply()
    }

    fun usedToday(): Int { roll(); return p.getInt("count", 0) }
    /** Counts one request to Gemini's voice. */
    fun add() { roll(); p.edit().putInt("count", p.getInt("count", 0) + 1).apply() }
    /** How many more background requests are allowed today. */
    fun room(): Int { roll(); return if (p.getBoolean("limited", false)) 0 else (cap(ceiling()) - p.getInt("count", 0)).coerceAtLeast(0) }
    fun limitedToday(): Boolean { roll(); return p.getBoolean("limited", false) }
    /** Every key refused even after a wait: today's count is the real ceiling, and nothing more is asked today. */
    fun noteLimited() {
        roll()
        val n = p.getInt("count", 0)
        p.edit().putBoolean("limited", true).apply()
        if (n >= 10) p.edit().putInt("ceiling", n).apply()
    }

    fun saveProgress(ready: Int, total: Int) { p.edit().putInt("ready", ready).putInt("total", total).apply() }

    fun statusLine(): String {
        val ready = p.getInt("ready", -1)
        val a = if (ready >= 0) "Ready $ready of ${p.getInt("total", 0)}. " else "Has not run yet. "
        val b = if (limitedToday()) "Daily limit reached: continues after the reset." else "Today ${usedToday()} of about ${cap(ceiling())} requests used."
        return a + b
    }

    private companion object { const val START_CEILING = 50 }
}
