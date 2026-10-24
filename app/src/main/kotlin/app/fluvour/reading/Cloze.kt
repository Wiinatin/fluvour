package app.fluvour.reading

/** A sentence with one word blanked out: show `before` + a blank + `after`; `answer` is the text that was removed. */
data class ClozeText(val before: String, val answer: String, val after: String)

/**
 * Phase 2A step 5: cloze cards from mined sentences. Pure JVM, no Android imports.
 * The card's word is usually the dictionary form (食べる), while the sentence holds the inflected form (食べた),
 * so the match goes through the splitter's tokens first (surface or lemma), then falls back to a plain text search.
 */
object Cloze {
    /** `tokens` = WordSplitter.split(sentence, lang). Returns null when the word is not found, so the card stays a normal card. */
    fun build(sentence: String, word: String, tokens: List<Token>): ClozeText? {
        val w = word.trim()
        if (sentence.isBlank() || w.isEmpty()) return null
        val hit = tokens.firstOrNull { it.isWord && it.end <= sentence.length &&
            (sentence.substring(it.start, it.end).equals(w, ignoreCase = true) || it.lemma.equals(w, ignoreCase = true)) }
        val (s, e) = if (hit != null) hit.start to hit.end else {
            val i = sentence.indexOf(w, ignoreCase = true)
            if (i < 0) return null
            i to i + w.length
        }
        return ClozeText(sentence.substring(0, s), sentence.substring(s, e), sentence.substring(e))
    }
}
