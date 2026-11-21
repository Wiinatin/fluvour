package app.fluvour.reading

import app.fluvour.srs.Phase

/** How mature a kanji is. Driven by Study ratings only (the FSRS memory state), never by the practice page's Submit. */
enum class Tier { PLAIN, COPPER, SILVER, GOLD, JADE, RADIANT }

/** Thresholds in days of memory stability. Starting values; Win will tune them on the phone. */
object TierRules {
    const val SILVER_DAYS = 7.0
    const val GOLD_DAYS = 21.0
    const val JADE_DAYS = 60.0
    const val RADIANT_DAYS = 180.0
}

/**
 * Never studied = PLAIN. Studied but still learning or relearning, or stability under 7 days = COPPER.
 * Then SILVER (7+ days), GOLD (21+), JADE (60+), RADIANT (180+). A Wrong rating can drop a tier: that is expected.
 */
fun tierOf(started: Boolean, phase: Phase, stabilityDays: Double): Tier = when {
    !started || phase == Phase.NEW -> Tier.PLAIN
    phase == Phase.LEARNING || phase == Phase.RELEARNING -> Tier.COPPER
    stabilityDays >= TierRules.RADIANT_DAYS -> Tier.RADIANT
    stabilityDays >= TierRules.JADE_DAYS -> Tier.JADE
    stabilityDays >= TierRules.GOLD_DAYS -> Tier.GOLD
    stabilityDays >= TierRules.SILVER_DAYS -> Tier.SILVER
    else -> Tier.COPPER
}
