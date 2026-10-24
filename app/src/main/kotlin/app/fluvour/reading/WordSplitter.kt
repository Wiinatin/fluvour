package app.fluvour.reading

import java.text.BreakIterator
import java.util.Locale

/**
 * A piece of text. Word pieces are tappable; the rest (spaces, punctuation) are not.
 * `lemma` is the dictionary form when the splitter knows it and it differs from the surface (Japanese: 食べた -> 食べる).
 * `reading` is a hiragana reading (Japanese only, for now).
 */
data class Token(val start: Int, val end: Int, val isWord: Boolean, val lemma: String? = null, val reading: String? = null)

/**
 * Splits text into words, one engine per language (2A step 3):
 *  - Japanese: Kuromoji (IPADIC), which also gives base forms and readings.
 *  - Chinese: jieba (jieba-analysis).
 *  - Thai: the platform's ICU word breaker, which ships a Thai dictionary.
 *  - Every other language (and any engine that fails): ICU word breaks, which is right for space-separated scripts.
 * Pure JVM: no Android imports. Call from a background thread: the first Japanese or Chinese call loads a dictionary.
 */
object WordSplitter {
    private class Span(val start: Int, val end: Int, val lemma: String? = null, val reading: String? = null)

    fun split(text: String, langTag: String): List<Token> {
        if (text.isEmpty()) return emptyList()
        val spans = try {
            when (langTag.substringBefore('-').lowercase()) {
                "ja" -> kuromoji(text)
                "zh" -> jieba(text)
                else -> icu(text, langTag)
            }
        } catch (e: Throwable) { icu(text, langTag) }   // a missing or broken dictionary must never stop reading
        return normalise(text, spans)
    }

    /** Sorted, non-overlapping tokens that cover the whole text (gaps become non-word tokens). */
    private fun normalise(text: String, spans: List<Span>): List<Token> {
        val out = ArrayList<Token>()
        var cursor = 0
        for (s in spans.sortedBy { it.start }) {
            if (s.start < cursor || s.end <= s.start || s.end > text.length) continue
            if (s.start > cursor) out += Token(cursor, s.start, isWord(text, cursor, s.start))
            val word = isWord(text, s.start, s.end)
            out += Token(s.start, s.end, word, if (word) s.lemma else null, if (word) s.reading else null)
            cursor = s.end
        }
        if (cursor < text.length) out += Token(cursor, text.length, isWord(text, cursor, text.length))
        return out
    }

    private fun icu(text: String, langTag: String): List<Span> {
        val it = BreakIterator.getWordInstance(Locale.forLanguageTag(langTag))
        it.setText(text)
        val out = ArrayList<Span>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) { out += Span(start, end); start = end; end = it.next() }
        return out
    }

    private val kuromojiTokenizer by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }
    private fun kuromoji(text: String): List<Span> = kuromojiTokenizer.tokenize(text).map { t ->
        val base = t.baseForm.takeIf { it != "*" && it != t.surface }
        val reading = t.reading.takeIf { it != "*" }?.let(::katakanaToHiragana)
        Span(t.position, t.position + t.surface.length, base, reading)
    }

    private val jiebaSegmenter by lazy { com.huaban.analysis.jieba.JiebaSegmenter() }
    // Offsets, not the returned words: jieba lower-cases and normalises the words it returns.
    private fun jieba(text: String): List<Span> =
        jiebaSegmenter.process(text, com.huaban.analysis.jieba.JiebaSegmenter.SegMode.SEARCH).map { Span(it.startOffset, it.endOffset) }

    internal fun katakanaToHiragana(s: String): String =
        s.map { if (it in '\u30A1'..'\u30F6') (it.code - 0x60).toChar() else it }.joinToString("")

    /** The token containing `offset`, if it is a word. */
    fun wordAt(tokens: List<Token>, offset: Int): Token? =
        tokens.firstOrNull { offset >= it.start && offset < it.end }?.takeIf { it.isWord }

    private fun isWord(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private val terminators = setOf('.', '!', '?', '。', '！', '？', '…', '\n')

    /**
     * Start (inclusive) and end (exclusive) of the sentence around `offset`, with surrounding spaces trimmed off.
     * Used for sentence mining, so the saved position points at exactly the saved text.
     */
    fun sentenceSpan(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val at = offset.coerceIn(0, text.length - 1)
        var s = at
        while (s > 0 && text[s - 1] !in terminators) s--
        var e = at
        while (e < text.length && text[e] !in terminators) e++
        if (e < text.length) e++            // keep the closing mark
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s to e
    }

    /** The sentence around `offset` (from the previous sentence end to the next), trimmed. */
    fun sentenceAround(text: String, offset: Int): String =
        sentenceSpan(text, offset).let { (s, e) -> text.substring(s, e) }
}
