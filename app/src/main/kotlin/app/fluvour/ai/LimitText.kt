package app.fluvour.ai

/** The small status line under each key in Settings > AI keys: "Hit limit 17 days ago", "Active — in use now", "Waiting its turn". Pure. */
object LimitText {
    fun ago(thenMs: Long, nowMs: Long): String {
        val minutes = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        fun unit(n: Long, word: String) = "$n $word${if (n == 1L) "" else "s"} ago"
        return when {
            minutes < 1 -> "just now"
            hours < 1 -> unit(minutes, "minute")
            days < 1 -> unit(hours, "hour")
            else -> unit(days, "day")
        }
    }

    /** [active] is the key used next (first in the list). */
    fun status(limitedAt: Long?, active: Boolean, nowMs: Long): String = when {
        limitedAt != null -> "Hit limit " + ago(limitedAt, nowMs) + if (active) " · in use now" else ""
        active -> "Active — in use now"
        else -> "Waiting its turn"
    }
}
