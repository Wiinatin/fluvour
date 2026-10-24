package app.fluvour.ui

import java.util.Locale

/** "10m", "3h", "2d", "4mo", "1.5y": the short delay shown under each rating button. */
fun formatInterval(ms: Long): String {
    val minutes = ms / 60_000.0
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${Math.round(minutes)}m"
        minutes < 60 * 24 -> {
            val hours = Math.round(minutes / 60)
            if (hours >= 24) "1d" else "${hours}h"
        }
        minutes < 60 * 24 * 30 -> "${Math.round(minutes / (60 * 24))}d"
        minutes < 60 * 24 * 365 -> "${Math.max(1, Math.round(minutes / (60 * 24 * 30.4)))}mo"
        else -> String.format(Locale.US, "%.1fy", minutes / (60 * 24 * 365))
    }
}
