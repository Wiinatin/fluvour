package app.fluvour.reading

/**
 * When each word of a line starts, for the karaoke fill. Pure JVM, no Android.
 * Real word times (YouTube automatic captions) are used when they spell the line; otherwise the line's time is split over its words by
 * length in characters (an estimate: it assumes even speech and a line that starts and ends with the speech).
 */
object WordTiming {
    /**
     * Where each timed piece (`ms` to `text`) sits in [text]: character range plus start time. Only letters and digits are compared, so spaces,
     * commas and full stops (、。) in one of the two texts no longer break the match. Null when there are fewer than two pieces, or when the
     * pieces do not spell [text] (edited or cleaned differently), because lighting wrong letters is worse than nothing.
     */
    fun pieceSpans(text: String?, pieces: List<Pair<Long, String>>): List<LitSpan>? {
        if (text.isNullOrBlank() || pieces.size < 2) return null
        val at = ArrayList<Int>()
        text.forEachIndexed { i, ch -> if (ch.isLetterOrDigit()) at += i }
        val flat = StringBuilder().also { b -> at.forEach { b.append(text[it]) } }.toString()
        val joined = pieces.joinToString("") { p -> p.second.filter { it.isLetterOrDigit() } }
        if (!joined.equals(flat, ignoreCase = true)) return null
        val out = ArrayList<LitSpan>()
        var k = 0
        for ((ms, t) in pieces) {
            val n = t.count { it.isLetterOrDigit() }
            if (n == 0) continue
            out += LitSpan(at[k], at[k + n - 1] + 1, ms)
            k += n
        }
        return out
    }

    /**
     * Start time of each word token of [tokens] (only `isWord` ones, in order). A word inside a longer timed piece is placed by its
     * position in the piece. Null when there is nothing to time (no words, or the line has no duration).
     */
    fun starts(text: String, pieces: List<Pair<Long, String>>, tokens: List<Token>, startMs: Long, endMs: Long): List<Long>? {
        val ws = tokens.filter { it.isWord && it.start >= 0 && it.end > it.start && it.end <= text.length }
        if (ws.isEmpty() || endMs <= startMs) return null
        val spans = pieceSpans(text, pieces)
        if (spans != null) {
            return ws.map { t ->
                val i = spans.indexOfLast { it.from <= t.start }.coerceAtLeast(0)
                val sp = spans[i]
                val next = spans.getOrNull(i + 1)?.ms ?: endMs
                if (t.start <= sp.from || sp.to <= sp.from) sp.ms
                else sp.ms + ((next - sp.ms).coerceAtLeast(0L) * (t.start - sp.from).coerceAtMost(sp.to - sp.from) / (sp.to - sp.from))
            }
        }
        return estimate(text, ws, startMs, endMs)
    }

    /**
     * The estimate: the line's time split over [words] (word tokens of [text], in order). English and other spaced languages weigh a word by
     * its length. Japanese weighs it by how many beats (morae) it takes to say, adds a short pause after 、 and a longer one after 。！？, and
     * counts a line's time as speech time only up to a sensible slowest speaking rate, because YouTube keeps a line on screen after the
     * speaker has stopped. Null when there is nothing to time.
     */
    fun estimate(text: String, words: List<Token>, startMs: Long, endMs: Long): List<Long>? {
        if (words.isEmpty() || endMs <= startMs) return null
        val jp = words.any { isKana(it.reading) || (it.reading == null && text.substring(it.start, it.end).any(::isKanaChar)) }
        if (!jp) {
            val weights = words.map { t -> t.reading?.length?.takeIf { it > 0 } ?: (t.end - t.start) }
            val total = weights.sum()
            var before = 0
            return words.mapIndexed { i, _ -> (startMs + (endMs - startMs) * before / total).also { before += weights[i] } }
        }
        // beats before each word: the words' own beats, plus a pause where the text has a comma or a full stop between two words
        val begin = DoubleArray(words.size)
        var acc = 0.0
        words.forEachIndexed { i, t ->
            if (i > 0) acc += pauseBetween(text, words[i - 1].end, t.start)
            begin[i] = acc
            acc += beats(text.substring(t.start, t.end), t.reading)
        }
        val duration = minOf(endMs - startMs, (acc * 1000.0 / SLOWEST_BEATS_PER_SEC).toLong())
        return words.indices.map { startMs + (duration * begin[it] / acc).toLong() }
    }

    /** Slowest speech the estimate believes in. A line on screen longer than its words need at this speed is treated as having a silent end. */
    const val SLOWEST_BEATS_PER_SEC = 3.5
    private const val SHORT_PAUSE = 2.0
    private const val LONG_PAUSE = 3.0
    private val LIGHT = setOf("\u306F", "\u304C", "\u3092", "\u306B", "\u3078", "\u3067", "\u3068", "\u306E", "\u3082", "\u3084", "\u304B", "\u306D", "\u3088", "\u306A")   // は が を に へ で と の も や か ね よ な: spoken fast

    private fun isKanaChar(c: Char) = c in '\u3041'..'\u3096' || c in '\u30A1'..'\u30FA' || c == '\u30FC'
    private fun isKana(s: String?) = !s.isNullOrBlank() && s.all { isKanaChar(it) || it == '\u30FB' || it.isWhitespace() }

    /** Beats (morae) of a kana text: every kana counts one, except small ゃゅょ and small vowels, which join the kana before them. ー っ ん count one each. */
    fun morae(kana: String): Int = kana.count { c -> isKanaChar(c) && c !in SMALL }
    private const val SMALL = "\u3041\u3043\u3045\u3047\u3049\u3083\u3085\u3087\u308E\u30A1\u30A3\u30A5\u30A7\u30A9\u30E3\u30E5\u30E7\u30EE"

    /** Beats of one Japanese word: from its reading when Kuromoji gave one, else from its own characters (kanji 2, digits 2.5, Latin letters 1.5). */
    private fun beats(surface: String, reading: String?): Double {
        val b = if (isKana(reading)) morae(reading!!).toDouble()
        else if (reading != null && reading.isNotBlank()) reading.length.toDouble()
        else surface.sumOf { c ->
            when {
                isKanaChar(c) -> if (c in SMALL) 0.0 else 1.0
                c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c == '\u3005' -> 2.0
                c.isDigit() -> 2.5
                c in 'A'..'Z' || c in 'a'..'z' -> 1.5
                else -> 1.0
            }
        }
        return (if (surface in LIGHT) b * 0.7 else b).coerceAtLeast(0.5)
    }

    /** Beats of silence between the end of one word and the start of the next, from the punctuation between them. */
    private fun pauseBetween(text: String, from: Int, to: Int): Double {
        if (from < 0 || to > text.length || from >= to) return 0.0
        val gap = text.substring(from, to)
        return when {
            gap.any { it in "\u3002\uFF0E.!?\uFF01\uFF1F\u2026\u2025" } -> LONG_PAUSE
            gap.any { it in "\u3001\uFF0C," } -> SHORT_PAUSE
            else -> 0.0
        }
    }

    /** Short plain-words answer to "where do this line's word times come from?" (shown under the current line while testing). */
    fun source(text: String, pieces: List<Pair<Long, String>>): String = when {
        pieces.size < 2 -> "estimated (the captions have no word times)"
        pieceSpans(text, pieces) == null -> "estimated (the word times do not match the line text)"
        else -> "real word times"
    }
}
