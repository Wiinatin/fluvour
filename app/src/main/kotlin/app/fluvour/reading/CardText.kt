package app.fluvour.reading

/**
 * Turns the AI's 3-line explanation (1. form and reading, 2. meaning in this sentence, 3. a note) into what a card needs:
 * a short meaning for the card, and the whole explanation kept as the card's notes. Pure JVM, no Android.
 * Anything that is not that numbered shape (a dictionary gloss, text you typed) is left exactly as it is.
 */
object CardText {
    data class Split(val meaning: String, val notes: String?)

    private val first = Regex("^\\s*1[.)]")
    private val second = Regex("^\\s*2[.)]")
    private val number = Regex("^\\s*\\d+[.)]\\s*")
    private val context = Regex("^(in|for) (this|the|that) (sentence|context|case|passage|phrase)[,:]?\\s*", RegexOption.IGNORE_CASE)
    private val verb = Regex("^(here[, ]+)?(it|this|the word|this word|that)?\\s*(here\\s+)?(means|is used to mean|refers to|stands for|translates (as|to)|acts as|functions as|is)[:,]?\\s+", RegexOption.IGNORE_CASE)
    private val label = Regex("^(meaning|means)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    private val cut = Regex("[,;:.]?[\"\u201D']?\\s+(used|which|that|often|usually|typically|commonly|indicating|showing|connecting|expressing|suggesting|implying|conveying|signifying|denoting|referring|and is|as in)\\b|\\s[\u2014\\-]\\s", RegexOption.IGNORE_CASE)
    private val quoted = Regex("^([\"'\u201C][^\"'\u201D]+[\"'\u201D](\\s+or\\s+[\"'\u201C][^\"'\u201D]+[\"'\u201D])*),\\s.*$")
    private val firstOption = Regex("^([\"\u201C][^\"\u201D]+[\"\u201D])\\s+or\\s+")
    private val participle = Regex(",\\s+[A-Za-z]{4,}ing\\b")
    private const val MAX = 40

    /** `meaning` is short; `notes` is the original text when it was split, otherwise null. */
    fun fromExplanation(text: String): Split {
        val t = text.trim()
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2 || !first.containsMatchIn(lines[0])) return Split(t, null)
        val raw = (lines.firstOrNull { second.containsMatchIn(it) } ?: lines[1]).replace(number, "")
        val gloss = shorten(raw)
        return if (gloss.isBlank()) Split(t, null) else Split(gloss, t)
    }

    /**
     * The notes as the back of the card shows them: when they are the AI explanation, the line that already became the meaning
     * is left out (and the list numbers dropped), so nothing is said twice. Anything else is returned unchanged.
     */
    fun notesForCard(notes: String): String {
        val lines = notes.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2 || !first.containsMatchIn(lines[0])) return notes.trim()
        return lines.filterNot { second.containsMatchIn(it) }.joinToString("\n") { it.replace(number, "") }
    }

    /** The AI explanation inside a card's notes (the first block that starts with "1."), or null. */
    fun explanationIn(notes: String?): String? =
        notes?.split(Regex("\n\\s*\n"))?.map { it.trim() }?.firstOrNull { first.containsMatchIn(it.lineSequence().firstOrNull().orEmpty()) && it.lines().size >= 2 }

    private fun shorten(raw: String): String {
        var s = raw.trim().replace(context, "").replace(verb, "").replace(label, "").trim()
        cut.find(s)?.let { if (it.range.first > 0) s = s.substring(0, it.range.first) }
        quoted.find(s)?.let { s = it.groupValues[1] }        // "but" or "however", then a comment: keep the quoted part
        participle.find(s)?.let { if (it.range.first > 0) s = s.substring(0, it.range.first) }
        // still long and "a" or "b": the first quoted option is enough for a card
        if (s.length > MAX) firstOption.find(s)?.let { s = it.groupValues[1] }
        s = s.substringBefore(". ").trim().trimEnd('.', ';', ',', ':').trim()
        if (s.length > MAX) {
            val at = s.take(MAX).lastIndexOf(' ')
            s = s.take(if (at > 20) at else MAX).trimEnd(',', ';', ' ') + "\u2026"
        }
        if (s.length > 2 && s.first() == '\'' && s.last() == '\'' && s.count { it == '\'' } == 2) s = s.substring(1, s.length - 1)
        if (s.count { it == '"' } % 2 == 1) s += "\""   // a cut inside quotes: close them
        return s
    }
}
