package app.fluvour.reading

import kotlin.math.ln

/**
 * Thai word list with how often each word occurs (counts from a word-segmented Thai corpus). A count of 0 means "a real word, but no count".
 * File format, one word per line: `word<TAB>count`. Pure JVM, no Android.
 */
class ThaiLexicon(private val counts: Map<String, Int>) {
    val maxLen: Int = counts.keys.maxOfOrNull { it.length } ?: 0
    private val total: Double = counts.values.sumOf { it.toLong() }.coerceAtLeast(1L).toDouble() + counts.size * LIST_ONLY

    /** Cost of the word (lower = more likely: minus the log of its share of all words), or null when it is not in the list. */
    fun cost(word: String): Double? {
        val c = counts[word] ?: return null
        return ln(total) - ln((if (c > 0) c else LIST_ONLY).toDouble())
    }

    /** Cost of a piece the list does not know (a name, a new word): about as unlikely as a word seen half a time, plus a little per character. */
    fun unknownCost(length: Int): Double = ln(total) + 0.7 + 0.8 * length

    companion object {
        /** Count given to a word that is only in the plain word list. Small: such words only win when both of their parts are rare too. */
        const val LIST_ONLY = 3

        fun parse(lines: Sequence<String>): ThaiLexicon {
            val m = HashMap<String, Int>(100_000)
            for (l in lines) {
                val t = l.indexOf('\t')
                if (t <= 0) continue
                m[l.substring(0, t)] = l.substring(t + 1).trim().toIntOrNull() ?: 0
            }
            return ThaiLexicon(m)
        }
    }
}

/**
 * Fixes Thai word splits. The platform's word breaker sometimes cuts a compound in two (หมายถึง becomes หมาย + ถึง), so a tap finds the
 * wrong word. This picks the most likely way to cut the text into words, like jieba does for Chinese: every list word that fits at a
 * position is a candidate, the breaker's own pieces are kept as candidates too (so names and new words are not lost), and the cheapest
 * path through the text wins. No AI, no network.
 */
object ThaiSegmenter {
    /** Characters a Thai word cannot start with (vowel signs and tone marks that hang on the letter before). */
    private fun cannotStart(c: Char) = c == '\u0E30' || c == '\u0E31' || (c in '\u0E32'..'\u0E3A') || (c in '\u0E47'..'\u0E4E') || c == '\u0E2F' || c == '\u0E46'
    /** Characters a Thai word cannot end with (leading vowels, which come before the letter they belong to). */
    private fun cannotEnd(c: Char) = c in '\u0E40'..'\u0E44'
    private fun thai(c: Char) = c in '\u0E01'..'\u0E4E'
    private const val FALLBACK = 40.0

    /** [icu] are the breaker's tokens for [text] (sorted, covering all of it). Returns tokens of the same kind. */
    fun segment(text: String, icu: List<Token>, lex: ThaiLexicon): List<Token> {
        val n = text.length
        if (n == 0) return icu
        val inf = Double.MAX_VALUE
        val best = DoubleArray(n + 1) { inf }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        val icuAt = HashMap<Int, Token>()
        for (t in icu) icuAt[t.start] = t
        fun relax(i: Int, j: Int, c: Double) {
            if (j > n || j <= i) return
            val v = best[i] + c
            if (v < best[j]) { best[j] = v; from[j] = i }
        }
        for (i in 0 until n) {
            if (best[i] == inf) continue
            val ch = text[i]
            if (thai(ch) && !cannotStart(ch)) {
                var j = i + 1
                val limit = minOf(n, i + lex.maxLen)
                while (j <= limit) {
                    if (!cannotEnd(text[j - 1])) {
                        val c = lex.cost(text.substring(i, j))
                        if (c != null) relax(i, j, c)
                    }
                    j++
                }
            }
            icuAt[i]?.let { t ->
                val piece = text.substring(t.start, t.end)
                val c = when {
                    !t.isWord -> 0.0
                    !thai(piece[0]) -> 2.0
                    else -> lex.cost(piece) ?: lex.unknownCost(piece.length)
                }
                relax(i, t.end, c)
            }
            relax(i, i + 1, FALLBACK)
        }
        // walk back from the end
        val cuts = ArrayList<Int>()
        var p = n
        while (p > 0) { cuts += p; p = from[p]; if (p < 0) return icu }
        cuts.reverse()
        val out = ArrayList<Token>(cuts.size)
        var s = 0
        for (e in cuts) { out += Token(s, e, hasLetter(text, s, e)); s = e }
        return out
    }

    private fun hasLetter(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }
}
