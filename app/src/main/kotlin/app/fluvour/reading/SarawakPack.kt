package app.fluvour.reading

import app.fluvour.srs.Phase

/** One premade Sarawak card from `assets/sarawak/pack.tsv` (only rows that have a day order). */
data class SarawakEntry(
    val key: String, val standard: String, val sarawak: String, val english: String,
    val example: String, val tier: Int?, val dayOrder: Int, val exampleMeaning: String = ""
)

/** What a Malay card is saved with. [variantWord] is null when the pack has no standard Malay word (no separate Sarawak facet then). */
data class SarawakCardFields(val word: String, val meaning: String, val variantWord: String?, val variantExample: String?, val example: String?, val notes: String?, val variantExampleMeaning: String? = null)

/** How one pack word was handled when it unlocked: a new card was made, or the deck already had the word. */
enum class SarawakStatus { ADDED, SKIPPED }
data class SarawakProgress(val introducedAt: Long, val status: SarawakStatus, val cardId: String)

/**
 * How well a Sarawak card is known, for the ring on the Sarawak page. [percent] is memory stability as a share of the "mastered" level
 * (21 days, the same as Gold in the Heisig deck); [mastered] shows the tick. Cards never retire: a tick can go again after a lapse.
 */
data class SarawakMastery(val percent: Int, val mastered: Boolean)

object SarawakMasteryRules {
    fun of(phase: Phase, stabilityDays: Double): SarawakMastery {
        if (phase == Phase.NEW) return SarawakMastery(0, false)
        val pct = Math.round(stabilityDays / TierRules.GOLD_DAYS * 100).toInt().coerceIn(0, 100)
        val done = phase == Phase.REVIEW && stabilityDays >= TierRules.GOLD_DAYS
        return if (done) SarawakMastery(100, true) else SarawakMastery(pct.coerceAtMost(99), false)
    }
}

/** Pure (no Android), so `tools/sarawaktest/Test.kt` can check it on a plain JVM. */
object SarawakPackData {
    const val ASSET = "sarawak/pack.tsv"
    const val FILE = "sarawak-progress.tsv"
    const val LANG = "ms"

    fun key(sarawak: String) = sarawak.trim().lowercase()

    /** Columns: standard_malay, sarawak, english, example, source, tier, day_order, example_en (the last is optional). Rows without a day order (excluded words) are left out. Sorted by day order. */
    fun parse(lines: Sequence<String>): List<SarawakEntry> {
        val out = ArrayList<SarawakEntry>()
        val seen = HashSet<String>()
        for ((i, line) in lines.withIndex()) {
            if (i == 0 && line.startsWith("standard_malay")) continue
            val p = line.split('\t')
            if (p.size < 7) continue
            val order = p[6].trim().toIntOrNull() ?: continue
            val sar = p[1].trim()
            if (sar.isEmpty() || !seen.add(key(sar))) continue
            out.add(SarawakEntry(key(sar), p[0].trim(), sar, p[2].trim(), p[3].trim(), p[5].trim().toIntOrNull(), order, p.getOrNull(7)?.trim().orEmpty()))
        }
        return out.sortedBy { it.dayOrder }
    }

    /**
     * Card fields for one entry. With a standard Malay word: the card is that word, the Sarawak word is its variant (so the Sarawak
     * facet exists) and the daily review teaches the Sarawak side. Extra standard words ("Saya; Aku") go to the notes.
     * With none: the card is the Sarawak word itself (a plain word card, no Sarawak facet). Null when there is nothing to put on a card.
     */
    fun cardFields(e: SarawakEntry): SarawakCardFields? {
        val std = e.standard.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val meaning = e.english.ifBlank { std.firstOrNull().orEmpty() }
        if (meaning.isBlank() || e.sarawak.isBlank()) return null
        val ex = e.example.ifBlank { null }
        val exMeaning = if (ex != null) e.exampleMeaning.ifBlank { null } else null
        return if (std.isEmpty()) SarawakCardFields(e.sarawak, meaning, null, null, ex, "Sarawak Malay (premade deck)", exMeaning)
        else SarawakCardFields(std[0], meaning, e.sarawak, ex, null,
            if (std.size > 1) "Also: " + std.drop(1).joinToString("; ") else null, exMeaning)
    }

    /**
     * The unlock rule. Cards come in day order; today's share is the daily number minus what already unlocked today
     * (so reopening the page never adds more, and skipped days do not pile up).
     */
    fun nextToUnlock(all: List<SarawakEntry>, unlocked: Set<String>, newPerDay: Int, unlockedToday: Int): List<SarawakEntry> =
        all.asSequence().filter { it.key !in unlocked }.take((newPerDay - unlockedToday).coerceAtLeast(0)).toList()

    /** "Learn more": the next [n] cards, ignoring the daily limit. */
    fun more(all: List<SarawakEntry>, unlocked: Set<String>, n: Int): List<SarawakEntry> =
        all.asSequence().filter { it.key !in unlocked }.take(n.coerceAtLeast(0)).toList()

    fun encode(m: Map<String, SarawakProgress>): String =
        m.entries.joinToString("\n") { (k, p) -> listOf(k, p.introducedAt, p.status.name, p.cardId).joinToString("\t") }

    fun decode(lines: Sequence<String>): MutableMap<String, SarawakProgress> {
        val m = LinkedHashMap<String, SarawakProgress>()
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 4 || p[0].isBlank()) continue
            val st = runCatching { SarawakStatus.valueOf(p[2]) }.getOrNull() ?: continue
            m[p[0]] = SarawakProgress(p[1].toLongOrNull() ?: 0L, st, p[3])
        }
        return m
    }
}
