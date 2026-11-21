package app.fluvour.reading

/** The raw numbers read from one video's subtitles: how often each word occurs. Every percentage is recomputed from [counts]. */
class ClipCounts(val tokenTotal: Int, val counts: Map<String, Int>) {
    val uniqueCount: Int get() = counts.size
}

/**
 * What the clips list shows for one video. Ratios are 0..1, the `...Pct` fields are rounded for display.
 * [levelIndex] is the video's level on the language's scale (see [ClipLevels]); [approx] says the numbers rest on surface forms or rank bands.
 */
data class ClipScore(
    val levelIndex: Int, val levelLabel: String,
    val levelKnows: Double, val youKnow: Double, val levelKnowsPct: Int, val youKnowPct: Int,
    val newWordsPerMin: Double, val approx: Boolean, val bandApprox: Boolean
)

/** Constants of the ranking, in one place so they are easy to tune. */
object ClipRanking {
    /** Share of running words a learner should know for the clip to count as "at their level". */
    const val TARGET = 0.90
    const val BAND_LOW = 0.85
    const val BAND_HIGH = 0.95
    /** The video's level is the lowest level where coverage reaches this. */
    const val LEVEL_COVERAGE = 0.90
    /** At the top two levels a clip must still be at least this familiar before new words per minute decide the order. */
    const val TOP_MIN_KNOW = 0.85
    /** Clips shorter or longer than this are left out. */
    const val MIN_MS = 30_000L
    const val MAX_MS = 5 * 60_000L

    /** True when the clip is "at the learner's level": inside the 85 to 95% band, or at the top two levels at least 85% familiar. */
    fun atLevel(s: ClipScore, declaredIndex: Int, scale: ClipLevels.Scale): Boolean =
        if (scale.isTopTwo(declaredIndex)) s.youKnow >= TOP_MIN_KNOW else s.youKnow in BAND_LOW..BAND_HIGH

    /**
     * Best first. Normal levels: inside the 85 to 95% band first, closest to 90% first. Top two levels: clips with "you know" of at least 85%
     * first, most new words per minute first. Clips outside those groups follow, nearest first, so N clips are always available.
     */
    fun <T> rank(items: List<T>, declaredIndex: Int, scale: ClipLevels.Scale, score: (T) -> ClipScore): List<T> {
        if (scale.isTopTwo(declaredIndex)) {
            val (eligible, rest) = items.partition { score(it).youKnow >= TOP_MIN_KNOW }
            return eligible.sortedByDescending { score(it).newWordsPerMin } + rest.sortedByDescending { score(it).youKnow }
        }
        val (inBand, outside) = items.partition { score(it).youKnow in BAND_LOW..BAND_HIGH }
        return inBand.sortedBy { Math.abs(score(it).youKnow - TARGET) } + outside.sortedBy { Math.abs(score(it).youKnow - TARGET) }
    }
}

/** Counting words and working out the level and the two percentages of a clip. Pure JVM except through [WordSplitter]. */
object ClipMeasurer {
    /** Surface forms of English and the other spaced languages are matched without base forms, so their numbers are approximate. Japanese uses base forms, Chinese has none to find. */
    fun isApprox(lang: String) = lang.substringBefore('-').lowercase() !in setOf("ja", "zh")

    /**
     * Counts the words of the subtitle lines. Text should already be free of repeated lines (see [dedupeRolling]).
     * The key of a word is its dictionary form when the splitter knows one, otherwise the lower-cased surface form.
     */
    fun count(texts: List<String>, lang: String): ClipCounts {
        val counts = HashMap<String, Int>()
        var total = 0
        for (t in texts) {
            for (tok in WordSplitter.split(t, lang)) {
                if (!tok.isWord) continue
                val key = (tok.lemma ?: t.substring(tok.start, tok.end)).lowercase().trim()
                if (key.isEmpty() || key.none { it.isLetter() } || key.all { it.isDigit() }) continue
                counts[key] = (counts[key] ?: 0) + 1
                total++
            }
        }
        return ClipCounts(total, counts)
    }

    /** Running words per level index. Words missing from the list are not counted in any level (they stay unknown). */
    private fun histogram(c: ClipCounts, scale: ClipLevels.Scale, table: LevelTable?): IntArray {
        val h = IntArray(scale.codes.size)
        if (table == null) return h
        for ((w, n) in c.counts) {
            val i = ClipLevels.levelIndex(scale, table.find(w)) ?: continue
            h[i] += n
        }
        return h
    }

    /** Lowest level where at least [ClipRanking.LEVEL_COVERAGE] of the running words are listed at that level or below; the top level if none. */
    private fun videoLevel(h: IntArray, total: Int): Int {
        if (total == 0) return h.lastIndex
        var cum = 0
        for (i in h.indices) { cum += h[i]; if (cum.toDouble() / total >= ClipRanking.LEVEL_COVERAGE) return i }
        return h.lastIndex
    }

    /** The video's level code on the language's scale, stored with the video. Does not depend on the user. */
    fun levelCode(c: ClipCounts, lang: String, table: LevelTable?): String? {
        if (c.tokenTotal == 0) return null
        val scale = ClipLevels.scale(lang)
        return scale.codes[videoLevel(histogram(c, scale, table), c.tokenTotal)]
    }

    /**
     * The numbers for one user. [declaredCode] is the user's level code, [known] the lower-cased words of their learned cards.
     * Null when the video has no words or the code is not on the scale.
     */
    fun score(c: ClipCounts, lang: String, table: LevelTable?, declaredCode: String, known: Set<String>, durationMs: Long): ClipScore? {
        if (c.tokenTotal == 0) return null
        val scale = ClipLevels.scale(lang)
        val declared = scale.codes.indexOf(declaredCode)
        if (declared < 0) return null
        val h = histogram(c, scale, table)
        val total = c.tokenTotal
        var cumDeclared = 0
        for (i in 0..declared) cumDeclared += h[i]
        var knownTokens = 0
        for ((w, n) in c.counts) {
            val i = if (table == null) null else ClipLevels.levelIndex(scale, table.find(w))
            if ((i != null && i <= declared) || w in known) knownTokens += n
        }
        val level = videoLevel(h, total)
        val minutes = durationMs / 60_000.0
        val youKnow = knownTokens.toDouble() / total
        val levelKnows = cumDeclared.toDouble() / total
        return ClipScore(
            levelIndex = level, levelLabel = scale.labels[level],
            levelKnows = levelKnows, youKnow = youKnow,
            levelKnowsPct = Math.round(levelKnows * 100).toInt(), youKnowPct = Math.round(youKnow * 100).toInt(),
            newWordsPerMin = if (minutes > 0) (total - knownTokens) / minutes else 0.0,
            approx = isApprox(lang), bandApprox = scale.byRank
        )
    }

    /** The muted third line of a clip: `C1 · C2 knows 98% · you know 94%`. A leading `~` marks numbers that are approximate. */
    fun tagLine(s: ClipScore, declaredLabel: String): String {
        val t = if (s.approx) "~" else ""
        val lv = if (s.bandApprox) "~${s.levelLabel}" else s.levelLabel
        return "$lv · $declaredLabel knows $t${s.levelKnowsPct}% · you know $t${s.youKnowPct}%"
    }

    /**
     * Auto captions repeat lines in a rolling window (each cue is the last line plus a new one). Keeps only the new part of every line,
     * so counted words are not inflated. A heuristic: it needs an overlap of at least two words
     * (six characters for text without spaces). A rare real repeat of two words is lost, which costs far less than counting every cue twice. Manual subtitles and json3 captions pass through unchanged.
     */
    fun dedupeRolling(texts: List<String>): List<String> {
        val out = ArrayList<String>()
        var prev: String? = null
        for (raw in texts) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val p = prev
            val piece = if (p == null) t else removeOverlap(p, t)
            prev = t
            if (piece.isNotBlank()) out += piece
        }
        return out
    }

    private fun removeOverlap(prev: String, t: String): String {
        if (t == prev) return ""
        if (t.startsWith(prev)) return t.substring(prev.length).trim()
        if (' ' in t && ' ' in prev) {
            val pt = prev.split(' ').filter { it.isNotEmpty() }
            val tt = t.split(' ').filter { it.isNotEmpty() }
            for (k in minOf(pt.size, tt.size) downTo 2) {
                if (pt.takeLast(k) == tt.take(k)) return tt.drop(k).joinToString(" ")
            }
            return t
        }
        for (k in minOf(prev.length, t.length) downTo 6) if (prev.endsWith(t.take(k))) return t.substring(k)
        return t
    }
}
