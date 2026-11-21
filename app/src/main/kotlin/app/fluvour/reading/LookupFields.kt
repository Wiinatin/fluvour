package app.fluvour.reading

/**
 * The extra card fields hidden in the AI explanation for Malay and Arabic (step 4). Pure JVM, no Android.
 *
 * Malay answer (numbered, so [CardText] still finds the meaning on line 2):
 *   1. Standard: tidak | Sarawak: indak
 *   2. not
 *   3. Sarawak example: Aku indak tauk.
 *   4. one short note
 * Arabic answer:
 *   1. Vowelled: جَلَسَ | Root: ج-ل-س | Lemma: جَلَسَ
 *   2. he sat
 *   3. one short note
 * Any other language (or any answer without these labels) gives null, and nothing changes for it.
 */
object LookupFields {
    data class Fields(val word: String? = null, val variantWord: String? = null, val variantExample: String? = null, val root: String? = null, val lemma: String? = null) {
        val any get() = word != null || variantWord != null || variantExample != null || root != null || lemma != null
    }

    private val number = Regex("^\\s*\\d+[.)]\\s*")
    private val none = setOf("same", "-", "\u2014", "\u2013", "none", "n/a", "same in sarawak")

    fun parse(text: String?): Fields? {
        if (text.isNullOrBlank()) return null
        var word: String? = null; var variant: String? = null; var example: String? = null; var root: String? = null; var lemma: String? = null
        for (raw in text.lines()) {
            val line = raw.trim().replace(number, "").replace("*", "").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("Sarawak example", ignoreCase = true)) { example = example ?: value(line.substringAfter(':', "")); continue }
            if (!line.contains(':')) continue
            for (part in line.split('|')) {
                val key = part.substringBefore(':').trim().lowercase()
                val v = value(part.substringAfter(':', ""))
                when (key) {
                    "standard", "vowelled", "vocalised", "vocalized" -> word = word ?: v
                    "sarawak" -> variant = variant ?: v
                    "root" -> root = root ?: v
                    "lemma" -> lemma = lemma ?: v
                }
            }
        }
        val f = Fields(word, variant, example, root, lemma)
        return f.takeIf { it.any }
    }

    private fun value(s: String): String? {
        val v = s.trim().trim('"', '\u201C', '\u201D').trim()
        return v.takeIf { it.isNotEmpty() && it.lowercase() !in none }
    }

    /** Standard Malay and Sarawak Malay in one request. */
    fun malayPrompt(target: String, ui: String, word: String, sentence: String) =
        "You help a learner of $target who reads real articles. The learner studies Standard Malay and Sarawak Malay together. Explain the word \"$word\" as it is used in this sentence:\n" +
            "\"$sentence\"\n\n" +
            "Answer in $ui, plain text, no markdown, exactly these numbered lines:\n" +
            "1. Standard: <the Standard Malaysian Malay form, dictionary form> | Sarawak: <the everyday Sarawak form, or the word same if it is identical>\n" +
            "2. A short plain gloss of at most 6 words, without wording like \"in this sentence\".\n" +
            "3. Sarawak example: <one short natural sentence in Sarawak Malay using the Sarawak form; leave this line out if the Sarawak form is the same>\n" +
            "4. One short note on usage or grammar, only if useful."

    /** Quranic Arabic: vowelled form, three-letter root, lemma and meaning. */
    fun arabicPrompt(target: String, ui: String, word: String, sentence: String) =
        "You help a learner of $target who studies the Quran. Explain the word \"$word\" as it is used in this sentence:\n" +
            "\"$sentence\"\n\n" +
            "Answer in $ui, plain text, no markdown, exactly these numbered lines:\n" +
            "1. Vowelled: <the word with full vowel marks, Uthmani style> | Root: <the root letters separated by hyphens, e.g. ك-ت-ب> | Lemma: <the dictionary form with full vowel marks>\n" +
            "2. A short plain gloss of at most 6 words, without wording like \"in this sentence\".\n" +
            "3. One short note on grammar (form, case, or a common phrase), only if useful."
}
