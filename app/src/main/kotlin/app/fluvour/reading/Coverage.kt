package app.fluvour.reading

/**
 * Phase 2A step 6: known-word coverage ("you understand 82% of this article"). Pure JVM, no Android imports.
 * `known` of `total` word tokens in a text are words you already have as graduated cards.
 * Every occurrence counts, so a word repeated ten times weighs ten. A token is known when its surface form or its
 * dictionary form (lemma) matches a known word, ignoring case. Numbers are skipped.
 */
data class Coverage(val known: Int, val total: Int) {
    val percent: Int get() = if (total == 0) 0 else Math.round(known * 100f / total)

    // A companion, not a separate `object Coverage`: a class and an object cannot share a name in one package.
    companion object {
        fun compute(texts: List<Pair<String, List<Token>>>, knownWords: Set<String>): Coverage {
            if (knownWords.isEmpty()) return Coverage(0, countWords(texts))
            val known = knownWords.map { it.trim().lowercase() }.toSet()
            var k = 0; var n = 0
            for ((text, tokens) in texts) for (t in tokens) {
                if (!t.isWord || t.end > text.length) continue
                val surface = text.substring(t.start, t.end)
                if (surface.all { it.isDigit() }) continue
                n++
                if (surface.lowercase() in known || t.lemma?.lowercase() in known) k++
            }
            return Coverage(k, n)
        }

        private fun countWords(texts: List<Pair<String, List<Token>>>): Int =
            texts.sumOf { (text, tokens) -> tokens.count { it.isWord && it.end <= text.length && !text.substring(it.start, it.end).all { c -> c.isDigit() } } }
    }
}
