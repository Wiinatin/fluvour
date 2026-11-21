package app.fluvour.ai

import java.text.BreakIterator
import java.util.Locale

/** Four countable numbers for one recording. Never combined into a score. */
data class Measures(val seconds: Int, val distinctWords: Int, val substituted: Int, val newWords: Int)

/**
 * Measures for comparing versions of the same topic (Phase 5, step 8). Pure: no Android classes except what `java.text` gives.
 * Distinct words depend on the word splitter (ICU on Android), which is rough for Thai, Japanese and Chinese, so read the
 * trend over several versions rather than one number.
 */
object SpeakingMeasures {
    /** Number of different words in [text], ignoring case and punctuation. */
    fun distinctWords(text: String, lang: String): Int {
        if (text.isBlank()) return 0
        val it = BreakIterator.getWordInstance(Locale.forLanguageTag(lang))
        it.setText(text)
        val seen = HashSet<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end).trim()
            if (piece.any { ch -> ch.isLetterOrDigit() }) seen.add(piece.lowercase())
            start = end
            end = it.next()
        }
        return seen.size
    }

    /** Measures of one saved version, from the stored columns. */
    fun of(durationSec: Int, transcript: String, substitutedJson: String?, newWordsJson: String?, lang: String): Measures =
        Measures(
            seconds = durationSec,
            distinctWords = distinctWords(transcript, lang),
            substituted = SpeakingParse.substitutedFrom(substitutedJson).size,
            newWords = SpeakingParse.newWordsFrom(newWordsJson).size
        )

    /** Signed difference, [last] minus [first], for each measure. */
    fun change(first: Measures, last: Measures): Measures =
        Measures(last.seconds - first.seconds, last.distinctWords - first.distinctWords,
            last.substituted - first.substituted, last.newWords - first.newWords)

    /** "+3", "-2" or "0". */
    fun signed(n: Int): String = if (n > 0) "+$n" else if (n < 0) "-${-n}" else "0"
}
