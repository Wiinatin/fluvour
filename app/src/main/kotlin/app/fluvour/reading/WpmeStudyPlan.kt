package app.fluvour.reading

import app.fluvour.ai.WpmeLinksPrompt
import app.fluvour.srs.CardState
import app.fluvour.srs.Phase

/** FSRS memory state of one Word Power item, plus when it was first studied (for the daily limit of new items). */
data class WpmeProgress(val state: CardState, val introducedAt: Long)

data class WpmeStats(val total: Int, val learned: Int, val learning: Int, val due: Int, val newLeftToday: Int, val waiting: Int)

/** One part of a word card: the part as stored (`mis-`) and the deck element it stands for, null for a common affix that has no card. */
data class WpmePartInfo(val part: String, val element: WpmeItem?)

/** The progress file `wpme-progress.tsv`: one line per studied item, same columns as `heisig-progress.tsv`. Pure. */
object WpmeProgressData {
    const val FILE = "wpme-progress.tsv"

    fun encode(m: Map<String, WpmeProgress>): String = m.entries.joinToString("\n") { (key, p) ->
        val s = p.state
        listOf(key, s.phase.name, s.stability, s.difficulty, s.step, s.lastReviewMillis ?: "", s.dueMillis ?: "", s.scheduledDays, s.reps, s.lapses, p.introducedAt).joinToString("\t")
    }

    fun decode(lines: Sequence<String>): MutableMap<String, WpmeProgress> {
        val m = LinkedHashMap<String, WpmeProgress>()
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 11) continue
            val phase = runCatching { Phase.valueOf(p[1]) }.getOrNull() ?: continue
            val state = CardState(
                phase, p[2].toDoubleOrNull() ?: continue, p[3].toDoubleOrNull() ?: continue, p[4].toIntOrNull() ?: 0,
                p[5].toLongOrNull(), p[6].toLongOrNull(), p[7].toIntOrNull() ?: 0, p[8].toIntOrNull() ?: 0, p[9].toIntOrNull() ?: 0
            )
            m[p[0]] = WpmeProgress(state, p[10].toLongOrNull() ?: 0L)
        }
        return m
    }
}

/**
 * Pure: which deck elements a word's parts stand for. A part stands for every element one of whose stems it matches (the same
 * rule the link checker uses). A part that matches no element is a common affix and never blocks a word.
 */
class WpmeUnlock(elements: List<WpmeItem>) {
    private val stems: List<Pair<WpmeItem, List<String>>> = elements.filter { it.kind == WpmeData.ELEMENT }.map { it to WpmeData.stems(it.form) }

    fun elementsFor(part: String): List<WpmeItem> {
        val c = WpmeLinks.core(part)
        if (c.isEmpty()) return emptyList()
        return stems.filter { WpmeLinksPrompt.isElementCore(c, it.second) }.map { it.first }
    }

    /**
     * True when the word may be studied. A word with no link, or a plain one (no parts), has no rule and is always ready. Otherwise each
     * part that stands for deck elements needs one of them (any, since a part can match more than one) to be [known].
     */
    fun ready(link: WpmeLink?, known: Set<String>): Boolean {
        if (link == null || link.parts.isEmpty()) return true
        return link.parts.map { elementsFor(it) }.filter { it.isNotEmpty() }.all { req -> req.any { it.key in known } }
    }
}

/** Pure: what to study. Elements are offered first, then words whose parts are all known; due items come before new ones. */
object WpmeStudyPlan {
    fun known(progress: Map<String, WpmeProgress>): Set<String> = progress.filterValues { it.state.phase == Phase.REVIEW }.keys

    /** Items nobody has studied that can be started now, in order: elements (chapter order), then ready words (chapter order). [items] is in the deck's own order. */
    fun fresh(items: List<WpmeItem>, links: Map<String, WpmeLink>, unlock: WpmeUnlock, progress: Map<String, WpmeProgress>): List<WpmeItem> {
        val known = known(progress)
        val unstarted = items.filter { it.key !in progress }
        return unstarted.filter { it.kind == WpmeData.ELEMENT } + unstarted.filter { it.kind == WpmeData.WORD && unlock.ready(links[it.key], known) }
    }

    /** Unstarted words that are held back until their parts are known. */
    fun waiting(items: List<WpmeItem>, links: Map<String, WpmeLink>, unlock: WpmeUnlock, progress: Map<String, WpmeProgress>): Int {
        val known = known(progress)
        return items.count { it.kind == WpmeData.WORD && it.key !in progress && !unlock.ready(links[it.key], known) }
    }

    fun due(items: List<WpmeItem>, progress: Map<String, WpmeProgress>, isDue: (CardState) -> Boolean): List<WpmeItem> {
        val byKey = items.associateBy { it.key }
        return progress.entries.filter { isDue(it.value.state) }.sortedBy { it.value.state.dueMillis ?: 0L }.mapNotNull { byKey[it.key] }
    }

    fun session(items: List<WpmeItem>, links: Map<String, WpmeLink>, unlock: WpmeUnlock, progress: Map<String, WpmeProgress>, newLeft: Int, isDue: (CardState) -> Boolean): List<WpmeItem> =
        due(items, progress, isDue) + fresh(items, links, unlock, progress).take(newLeft.coerceAtLeast(0))
}
