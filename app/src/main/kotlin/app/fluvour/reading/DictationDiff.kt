package app.fluvour.reading

import java.text.Normalizer

/**
 * What dictation shows after Submit.
 * `refMissed` are indices into `refTokens` (the real subtitle), `ansWrong` are indices into `ansTokens` (what was typed).
 * Both token lists are the ones the diff used, so the screen can draw the same words it graded.
 */
data class DictResult(
    val refTokens: List<Token>, val refMissed: Set<Int>,
    val ansTokens: List<Token>, val ansWrong: Set<Int>,
    /** Words of the real line, and how many of them were heard right. */
    val words: Int, val right: Int,
    /** Japanese only: words with kanji in the real line, and how many of them you also wrote in kanji. Zero for other languages. */
    val kanji: Int = 0, val kanjiRight: Int = 0
) {
    val perfect get() = words > 0 && right == words
}

/**
 * Grades a typed line against the real subtitle (dictation, step two). Pure JVM, no Android.
 *  - Spaced languages: word by word. Case, punctuation and apostrophes are ignored (dont = don't); accents are not.
 *  - Japanese: by reading (kana), so 食べた and たべた are the same answer. Kanji you got right are counted on top.
 *  - Chinese and Thai: character by character, because the word breaker may split a typo differently from the real line.
 * The two sides are lined up with a longest-common-subsequence, so one missing word does not shift every word after it.
 */
object DictationDiff {
    private class Piece(val key: String, val token: Int)

    fun grade(ref: String, refTokens: List<Token>, answer: String, lang: String): DictResult {
        val l = lang.substringBefore('-').lowercase()
        val charLevel = l == "ja" || l == "zh" || l == "th"
        // curly apostrophes become straight ones (same length, so offsets hold) so the word breaker keeps "don't" in one piece
        val typed = answer.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u02BC', '\'')
        val ansTokens = if (typed.isBlank()) emptyList() else WordSplitter.split(typed, l)

        val a = units(ref, refTokens, l, charLevel)
        val b = units(typed, ansTokens, l, charLevel)
        val (hitA, hitB) = lcs(a, b)

        // a word counts as heard only when every unit of it lined up
        val refMissed = missed(refTokens, a, hitA)
        val ansWrong = missed(ansTokens, b, hitB)
        val words = refTokens.indices.count { refTokens[it].isWord && a.any { u -> u.token == it } }
        val right = words - refMissed.size

        var kanji = 0; var kanjiRight = 0
        if (l == "ja") {
            val written = norm(typed)
            refTokens.forEachIndexed { i, t ->
                val s = ref.substring(t.start, t.end)
                if (t.isWord && hasKanji(s)) { kanji++; if (i !in refMissed && norm(s) in written) kanjiRight++ }
            }
        }
        return DictResult(refTokens, refMissed, ansTokens, ansWrong, words, right, kanji, kanjiRight)
    }

    private fun missed(tokens: List<Token>, units: List<Piece>, hit: BooleanArray): Set<Int> {
        val bad = HashSet<Int>()
        units.forEachIndexed { i, u -> if (!hit[i]) bad += u.token }
        return bad.filter { tokens[it].isWord }.toSet()
    }

    /** The comparable pieces of a text: one per word, or one per character of each word. Punctuation and spaces never appear. */
    private fun units(text: String, tokens: List<Token>, l: String, charLevel: Boolean): List<Piece> {
        val out = ArrayList<Piece>()
        tokens.forEachIndexed { i, t ->
            if (!t.isWord) return@forEachIndexed
            val surface = text.substring(t.start, t.end)
            // Japanese: the reading when the tokenizer has one, so kanji choice is not penalised
            val raw = if (l == "ja") (t.reading ?: surface) else surface
            val key = norm(raw)
            if (key.isEmpty()) return@forEachIndexed
            if (charLevel) key.forEach { out += Piece(it.toString(), i) } else out += Piece(key, i)
        }
        return out
    }

    /** Lower case, full-width to normal width, katakana to hiragana, and only letters and digits left. */
    private fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder()
        for (ch in n) {
            when {
                ch in '\u30A1'..'\u30F6' -> sb.append((ch.code - 0x60).toChar())
                ch == '\u30FC' -> sb.append(ch)   // the long-vowel mark is part of a Japanese reading
                ch.isLetterOrDigit() || Character.getType(ch) == Character.NON_SPACING_MARK.toInt() || Character.getType(ch) == Character.COMBINING_SPACING_MARK.toInt() -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun hasKanji(s: String) = s.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' || it == '\u3005' }

    /** Longest common subsequence of the keys. Returns, for each side, which units took part. */
    private fun lcs(a: List<Piece>, b: List<Piece>): Pair<BooleanArray, BooleanArray> {
        val hitA = BooleanArray(a.size); val hitB = BooleanArray(b.size)
        if (a.isEmpty() || b.isEmpty()) return hitA to hitB
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0)
            dp[i][j] = if (a[i].key == b[j].key) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i].key == b[j].key -> { hitA[i] = true; hitB[j] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return hitA to hitB
    }
}
