package app.fluvour.reading

/** What the AI suggests for the Sarawak side of a standard Malay card. [exampleMeaning] is in English. */
data class SarawakSuggestion(val variantWord: String, val variantExample: String?, val exampleMeaning: String?)

/**
 * Asks the AI for the Sarawak form of a Standard Malay word, one short Sarawak example and what it means, and reads the answer.
 * Pure (no Android), so `tools/sarawaktest/Test.kt` checks it. The few examples in the prompt come from the phrasebook pack,
 * so the AI is steered towards real Kuching Sarawak Malay, and told to say SAME when it is not sure rather than invent a word.
 */
object SarawakSuggestPrompt {
    /** Added to the card's notes so a suggested card can be spotted and checked later. */
    const val NOTE = "Sarawak word, example and meaning were suggested by AI. Please check them."
    const val TEMPERATURE = 0.2
    const val MAX_TOKENS = 300

    fun build(word: String, meaning: String) =
        "You help a learner of Malay who studies Standard Malay and Sarawak Malay (as spoken in Kuching) together.\n" +
            "Give the everyday Sarawak Malay equivalent of this Standard Malay word, one short natural Sarawak example sentence that uses the Sarawak form, and the English meaning of that sentence.\n" +
            "Word: \"${word.trim()}\" (meaning: ${meaning.trim()})\n\n" +
            "Rules: use real Kuching Sarawak Malay spelling (for example -ak endings, kamek, kitak, sitok, siney), not Indonesian. " +
            "If the Sarawak form is the same as the Standard word, or you are not sure, write SAME and leave out the other two lines. Never invent a word.\n\n" +
            "Answer in plain text, no markdown, exactly these lines:\n" +
            "Sarawak: <Sarawak form, or SAME>\n" +
            "Example: <one short Sarawak Malay sentence>\n" +
            "Meaning: <English meaning of that sentence>\n\n" +
            "Examples of the format:\n" +
            "Word: \"pergi\"  ->  Sarawak: pegi | Example: Kitak nak pegi sine? | Meaning: Where are you going?\n" +
            "Word: \"awak\"  ->  Sarawak: kitak | Example: Apa khabar kitak? | Meaning: How are you?\n" +
            "Word: \"saya\"  ->  Sarawak: kamek | Example: Kamek dari Kuching. | Meaning: I am from Kuching."

    private val none = setOf("same", "-", "\u2014", "\u2013", "none", "n/a", "same in sarawak", "unsure", "unknown")

    private fun clean(s: String): String? =
        s.replace("*", "").trim().trim('"', '\u201C', '\u201D', '\'').trim().takeIf { it.isNotEmpty() && it.lowercase() !in none }

    /** Null when the AI says SAME, is unsure, gives the same word back, or the answer cannot be read. */
    fun parse(text: String?, standardWord: String): SarawakSuggestion? {
        if (text.isNullOrBlank()) return null
        var sar: String? = null; var ex: String? = null; var mean: String? = null
        for (raw in text.lines()) {
            val line = raw.trim().replace(Regex("^[-\u2022*\\d.)\\s]+"), "")
            for (part in line.split('|')) {   // an answer given on one line, "Sarawak: x | Example: y | Meaning: z", reads the same
                val key = part.substringBefore(':', "").replace("*", "").trim().lowercase()
                val v = part.substringAfter(':', "")
                when (key) { "sarawak" -> sar = sar ?: clean(v); "example" -> ex = ex ?: clean(v); "meaning" -> mean = mean ?: clean(v) }
            }
        }
        val word = sar ?: return null
        if (word.length > 40 || '\n' in word || word.equals(standardWord.trim(), ignoreCase = true)) return null
        return SarawakSuggestion(word, ex?.takeIf { it.length <= 220 }, mean?.takeIf { ex != null && it.length <= 260 })
    }
}
