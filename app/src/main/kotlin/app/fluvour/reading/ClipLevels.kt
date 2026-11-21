package app.fluvour.reading

/**
 * Clip recommendations (roadmap 4.2): the level scale of each language and how a word maps onto it. Pure JVM, no Android imports.
 *
 * Scales run from easiest to hardest. English (A1-C2), Japanese (N5-N1) and Chinese (HSK 1-7+) use the exam codes in the level tables.
 * Every other language, Thai included, has no free exam list, so its levels are approximate bands of the frequency rank and are
 * labelled CEFR-style. Numbers from such a scale are shown with a leading `~`.
 */
object ClipLevels {
    class Scale(val lang: String, val codes: List<String>, val labels: List<String>, val byRank: Boolean, val bands: List<Int> = RANK_BANDS) {
        val top: Int get() = codes.lastIndex
        /** The top two levels, where percentages saturate near 100 and ranking switches to new words per minute. */
        fun isTopTwo(index: Int) = index >= codes.size - 2
    }

    private val CEFR = listOf("A1", "A2", "B1", "B2", "C1", "C2")

    /** Largest frequency rank of each band A1..C2 for languages without an exam list. The last band takes everything left in the list. Tune here. */
    val RANK_BANDS = listOf(500, 1500, 3000, 6000, 12000, Int.MAX_VALUE)

    private val EN = Scale("en", CEFR, CEFR, false)
    private val JA = Scale("ja", listOf("N5", "N4", "N3", "N2", "N1"), listOf("N5", "N4", "N3", "N2", "N1"), false)
    private val ZH = Scale("zh", listOf("H1", "H2", "H3", "H4", "H5", "H6", "H7"), listOf("HSK 1", "HSK 2", "HSK 3", "HSK 4", "HSK 5", "HSK 6", "HSK 7-9"), false)

    /** Quranic Arabic: rank is the lemma's frequency rank in the Quran. Bands cover roughly 50 / 70 / 80 / 90 / 95 % of the running words. */
    private val AR = Scale("ar", listOf("Q1", "Q2", "Q3", "Q4", "Q5", "Q6"), listOf("Quran 1", "Quran 2", "Quran 3", "Quran 4", "Quran 5", "Quran 6"), true,
        listOf(50, 190, 390, 910, 1670, Int.MAX_VALUE))

    fun scale(lang: String): Scale = when (lang) {
        "ar" -> AR
        "en" -> EN
        "ja" -> JA
        "zh" -> ZH
        else -> Scale(lang, CEFR, CEFR, true)
    }

    /** Index of the word's level on the language's scale, or null when the word is not listed (counts as unknown). */
    fun levelIndex(scale: Scale, info: LevelInfo?): Int? {
        if (info == null) return null
        if (scale.byRank) {
            val r = info.rank ?: return null
            return scale.bands.indexOfFirst { r <= it }.takeIf { it >= 0 }
        }
        val code = info.code ?: return null
        val i = scale.codes.indexOf(code.uppercase())
        return if (i >= 0) i else null
    }

    /** Label of a stored level code ("C2", "N3", "H4" becomes "HSK 4"); null when the code is not on the scale. */
    fun label(lang: String, code: String?): String? {
        val s = scale(lang)
        val i = s.codes.indexOf(code ?: return null)
        return if (i >= 0) s.labels[i] else null
    }

    /** The labels shown in the level picker, easiest first; each pairs with the code that is stored. */
    fun options(lang: String): List<Pair<String, String>> = scale(lang).let { s -> s.codes.zip(s.labels) }
}
