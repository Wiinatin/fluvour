package app.fluvour.srs

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS-6 scheduler, a port of the open-spaced-repetition `ts-fsrs` 5.4.2 flow
 * (learning steps, short-term and long-term modes, 21 default weights).
 *
 * Pure Kotlin: no Android, no UI, no database imports, so it can be ported to TypeScript
 * for Torbito web (Phase 9). Time comes in as epoch milliseconds; there is no clock inside.
 *
 * Known differences from ts-fsrs:
 *  - Fuzz uses our own deterministic generator, not Alea, so fuzzed intervals differ from ts-fsrs.
 *    Golden vectors are generated with fuzz off.
 *  - Weights are not clipped, so relearning with more than one step is unsupported for now.
 */
enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

enum class Phase { NEW, LEARNING, REVIEW, RELEARNING }

/** Memory state of one card. All plain values, easy to store and sync. */
data class CardState(
    val phase: Phase = Phase.NEW,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val step: Int = 0,                    // index of the current learning/relearning step
    val lastReviewMillis: Long? = null,
    val dueMillis: Long? = null,
    val scheduledDays: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0
)

data class SchedulerConfig(
    val weights: DoubleArray = DEFAULT_WEIGHTS,
    val desiredRetention: Double = 0.9,
    val maxIntervalDays: Int = 36500,
    val learningStepsMinutes: List<Int> = listOf(1, 10),
    val relearningStepsMinutes: List<Int> = listOf(10),
    /** true: learning steps and same-day memory updates. false: day-level scheduling only. */
    val enableShortTerm: Boolean = true,
    val enableFuzz: Boolean = false
) {
    companion object {
        /** FSRS-6 default parameters, identical in ts-fsrs 5.4.2 and the official Python and Kotlin ports. */
        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
        )
    }
}

class Scheduler(private val cfg: SchedulerConfig = SchedulerConfig()) {
    private val w = cfg.weights
    private val decay = -w[20]
    private val factor = roundTo(exp(ln(0.9) / decay) - 1.0, 8)
    private val intervalModifier = roundTo((cfg.desiredRetention.pow(1.0 / decay) - 1.0) / factor, 8)

    init {
        require(w.size == 21) { "FSRS-6 needs 21 weights" }
        require(cfg.desiredRetention > 0.0 && cfg.desiredRetention <= 1.0) { "retention must be in (0,1]" }
    }

    // ---------- memory model ----------

    fun retrievability(elapsedDays: Int, s: Double): Double =
        roundTo((1.0 + factor * elapsedDays / s).pow(decay), 8)

    private fun initStability(g: Int) = max(w[g - 1], 0.1)
    private fun initDifficulty(g: Int) = roundTo(w[4] - exp((g - 1) * w[5]) + 1.0, 8)

    private fun nextDifficulty(d: Double, g: Int): Double {
        val delta = -w[6] * (g - 3)
        val next = d + roundTo(delta * (10.0 - d) / 9.0, 8)
        return clamp(roundTo(w[7] * initDifficulty(4) + (1.0 - w[7]) * next, 8), 1.0, 10.0)
    }

    private fun nextRecallStability(d: Double, s: Double, r: Double, g: Int): Double {
        val hard = if (g == 2) w[15] else 1.0
        val easy = if (g == 4) w[16] else 1.0
        return roundTo(clamp(
            s * (1.0 + exp(w[8]) * (11.0 - d) * s.pow(-w[9]) * (exp((1.0 - r) * w[10]) - 1.0) * hard * easy),
            S_MIN, S_MAX), 8)
    }

    private fun nextForgetStability(d: Double, s: Double, r: Double): Double =
        roundTo(clamp(w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1.0 - r) * w[14]), S_MIN, S_MAX), 8)

    private fun nextShortTermStability(s: Double, g: Int): Double {
        val sinc = s.pow(-w[19]) * exp(w[17] * (g - 3 + w[18]))
        val masked = if (g >= 2) max(sinc, 1.0) else sinc
        return roundTo(clamp(s * masked, S_MIN, S_MAX), 8)
    }

    /** Returns difficulty and stability after one review, `t` = whole days since the last review. */
    private fun nextMemory(d: Double, s: Double, t: Int, g: Int, rGiven: Double? = null): Pair<Double, Double> {
        require(t >= 0) { "last review is after the review time" }
        if (d == 0.0 && s == 0.0) return Pair(clamp(initDifficulty(g), 1.0, 10.0), initStability(g))
        require(d >= 1.0 && s >= S_MIN) { "invalid memory state" }
        val r = rGiven ?: retrievability(t, s)
        val newS = when {
            t == 0 && cfg.enableShortTerm -> nextShortTermStability(s, g)
            g == 1 -> {
                val afterFail = nextForgetStability(d, s, r)
                val w17 = if (cfg.enableShortTerm) w[17] else 0.0
                val w18 = if (cfg.enableShortTerm) w[18] else 0.0
                clamp(roundTo(s / exp(w17 * w18), 8), S_MIN, afterFail)
            }
            else -> nextRecallStability(d, s, r, g)
        }
        return Pair(nextDifficulty(d, g), newS)
    }

    // ---------- intervals ----------

    private fun interval(s: Double, elapsedDays: Int, seed: Long): Int {
        val base = min(max(1, Math.round(s * intervalModifier).toInt()), cfg.maxIntervalDays)
        if (!cfg.enableFuzz || base < 2.5) return base
        val (lo, hi) = fuzzRange(base.toDouble(), elapsedDays)
        return floor(unitRandom(seed) * (hi - lo + 1) + lo).toInt()
    }

    private fun fuzzRange(interval: Double, elapsedDays: Int): Pair<Int, Int> {
        var delta = 1.0
        for ((start, end, f) in FUZZ_RANGES) delta += f * max(min(interval, end) - start, 0.0)
        val ivl = min(interval, cfg.maxIntervalDays.toDouble())
        var lo = max(2, Math.round(ivl - delta).toInt())
        val hi = min(Math.round(ivl + delta).toInt(), cfg.maxIntervalDays)
        if (ivl > elapsedDays) lo = max(lo, elapsedDays + 1)
        return Pair(min(lo, hi), hi)
    }

    // ---------- learning steps ----------

    private fun hardMinutes(steps: List<Int>): Int =
        if (steps.size == 1) Math.round(steps[0] * 1.5).toInt()
        else Math.round((steps[0] + steps[1]) / 2.0).toInt()

    /** (minutes until due, next step index), or null when the card should graduate. */
    private fun stepResult(phase: Phase, cur: Int, g: Rating): Pair<Int, Int>? {
        val steps = if (phase == Phase.RELEARNING || phase == Phase.REVIEW) cfg.relearningStepsMinutes else cfg.learningStepsMinutes
        if (steps.isEmpty() || cur >= steps.size) return null
        if (phase == Phase.REVIEW) return if (g == Rating.AGAIN) Pair(steps[max(0, cur)], 0) else null
        return when (g) {
            Rating.AGAIN -> Pair(steps[0], 0)
            Rating.HARD -> Pair(hardMinutes(steps), cur)
            Rating.GOOD -> if (cur + 1 < steps.size && steps[cur + 1] != 0) Pair(steps[cur + 1], cur + 1) else null
            Rating.EASY -> null
        }
    }

    private fun applySteps(next: CardState, g: Rating, toPhase: Phase, curPhase: Phase, curStep: Int,
                           now: Long, elapsed: Int, seed: Long): CardState {
        val r = stepResult(curPhase, curStep, g)
        val minutes = max(0, r?.first ?: 0)
        val nextStep = max(0, r?.second ?: 0)
        return when {
            minutes in 1..1439 ->
                next.copy(phase = toPhase, step = nextStep, scheduledDays = 0, dueMillis = now + minutes * MINUTE)
            minutes >= 1440 ->
                next.copy(phase = Phase.REVIEW, step = nextStep, dueMillis = now + minutes * MINUTE, scheduledDays = minutes / 1440)
            else -> {
                val days = interval(next.stability, elapsed, seed)
                next.copy(phase = Phase.REVIEW, step = 0, scheduledDays = days, dueMillis = now + days * DAY)
            }
        }
    }

    // ---------- public API ----------

    /**
     * Apply one review. [tzOffsetMillis] shifts the calendar-day boundary to the learner's local
     * midnight (ts-fsrs uses UTC, which is the default 0).
     */
    fun review(card: CardState, rating: Rating, nowMillis: Long, tzOffsetMillis: Long = 0L): CardState {
        val g = rating.value
        val last = card.lastReviewMillis
        val elapsed = if (card.phase != Phase.NEW && last != null)
            (dayIndex(nowMillis, tzOffsetMillis) - dayIndex(last, tzOffsetMillis)).toInt() else 0
        val reps = card.reps + 1
        val seed = nowMillis xor (reps * 31L) xor java.lang.Double.doubleToLongBits(card.difficulty * card.stability)
        val base = card.copy(lastReviewMillis = nowMillis, reps = reps)
        return if (cfg.enableShortTerm) shortTerm(base, card, rating, g, nowMillis, elapsed, seed)
        else longTerm(base, card, rating, g, nowMillis, elapsed, seed)
    }

    private fun shortTerm(base: CardState, card: CardState, rating: Rating, g: Int,
                          now: Long, elapsed: Int, seed: Long): CardState = when (card.phase) {
        Phase.NEW -> {
            val (d, s) = nextMemory(0.0, 0.0, 0, g)
            applySteps(base.copy(difficulty = d, stability = s, step = 0), rating, Phase.LEARNING, Phase.NEW, 0, now, elapsed, seed)
        }
        Phase.LEARNING, Phase.RELEARNING -> {
            val (d, s) = nextMemory(card.difficulty, card.stability, elapsed, g)
            applySteps(base.copy(difficulty = d, stability = s), rating, card.phase, card.phase, card.step, now, elapsed, seed)
        }
        Phase.REVIEW -> {
            val r = retrievability(elapsed, card.stability)
            fun mem(gr: Int) = nextMemory(card.difficulty, card.stability, elapsed, gr, r)
            if (rating == Rating.AGAIN) {
                val (d, s) = mem(1)
                applySteps(base.copy(difficulty = d, stability = s), rating, Phase.RELEARNING, Phase.REVIEW, 0, now, elapsed, seed)
                    .let { it.copy(lapses = it.lapses + 1) }
            } else {
                val sH = mem(2).second; val sG = mem(3).second; val sE = mem(4).second
                var hard = interval(sH, elapsed, seed); var good = interval(sG, elapsed, seed)
                hard = min(hard, good); good = max(good, hard + 1)
                val easy = max(interval(sE, elapsed, seed), good + 1)
                val pick = when (rating) { Rating.HARD -> hard; Rating.GOOD -> good; else -> easy }
                val (d, s) = mem(g)
                base.copy(phase = Phase.REVIEW, difficulty = d, stability = s, step = 0,
                    scheduledDays = pick, dueMillis = now + pick * DAY)
            }
        }
    }

    private fun longTerm(base: CardState, card: CardState, rating: Rating, g: Int,
                         now: Long, elapsed: Int, seed: Long): CardState {
        val isNew = card.phase == Phase.NEW
        val t = if (isNew) 0 else elapsed
        val r = if (isNew) null else retrievability(t, card.stability)
        val mem = (1..4).map { nextMemory(card.difficulty, card.stability, t, it, r) }
        var again = interval(mem[0].second, t, seed); var hard = interval(mem[1].second, t, seed)
        var good = interval(mem[2].second, t, seed); var easy = interval(mem[3].second, t, seed)
        again = min(again, hard); hard = max(hard, again + 1); good = max(good, hard + 1); easy = max(easy, good + 1)
        val days = intArrayOf(again, hard, good, easy)[g - 1]
        val lapse = if (rating == Rating.AGAIN && !isNew) 1 else 0
        return base.copy(phase = Phase.REVIEW, difficulty = mem[g - 1].first, stability = mem[g - 1].second,
            step = 0, scheduledDays = days, dueMillis = now + days * DAY, lapses = card.lapses + lapse)
    }

    /** Delay until due for each button, for labels like "10 min" or "3 days" on the review screen. */
    fun preview(card: CardState, nowMillis: Long, tzOffsetMillis: Long = 0L): Map<Rating, Long> =
        Rating.values().associate { it to (review(card, it, nowMillis, tzOffsetMillis).dueMillis!! - nowMillis) }

    /** New cards are always available; step cards when their time has come; review cards from the start of their day. */
    fun isDue(card: CardState, nowMillis: Long, endOfTodayMillis: Long): Boolean = when (card.phase) {
        Phase.NEW -> true
        Phase.LEARNING, Phase.RELEARNING -> (card.dueMillis ?: 0L) <= nowMillis
        Phase.REVIEW -> (card.dueMillis ?: 0L) <= endOfTodayMillis
    }

    /** Leech heuristic for Phase 3: failed many times, so offer a new angle (image, mnemonic, example). */
    fun isLeech(card: CardState, threshold: Int = 8) = card.lapses >= threshold

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        private const val S_MIN = 0.001
        private const val S_MAX = 36500.0
        private val FUZZ_RANGES = listOf(Triple(2.5, 7.0, 0.15), Triple(7.0, 20.0, 0.1), Triple(20.0, Double.POSITIVE_INFINITY, 0.05))

        fun dayIndex(millis: Long, tzOffsetMillis: Long = 0L): Long = Math.floorDiv(millis + tzOffsetMillis, DAY)
        private fun clamp(v: Double, lo: Double, hi: Double) = min(max(v, lo), hi)
        private fun roundTo(x: Double, decimals: Int): Double {
            val f = 10.0.pow(decimals)
            return Math.round(x * f) / f
        }
        /** SplitMix64 mapped to [0,1): deterministic, so the same review always fuzzes the same way. */
        private fun unitRandom(seed: Long): Double {
            var z = seed + -7046029254386353131L
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }
}
