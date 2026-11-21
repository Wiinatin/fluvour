package app.fluvour.reading.articles

import app.fluvour.reading.ClipLevels
import app.fluvour.reading.ClipMeasurer
import app.fluvour.reading.LevelTable

/**
 * Articles tab: the level of an article, measured from its own words with the same method as Watch clips. Pure JVM, no Android imports.
 * The level does not depend on the user. A site's own level label is never used.
 */
object ArticleLevel {
    /** Below this many words the text is too short to say anything, so no level is given. */
    const val MIN_WORDS = 20

    class Measured(val code: String?, val tokenTotal: Int)

    /** Counts the words of [text] and finds the lowest level that covers enough of them. Code is null without a level list or with too little text. */
    fun measure(text: String, lang: String, table: LevelTable?): Measured {
        val c = ClipMeasurer.count(listOf(text), lang)
        if (table == null || c.tokenTotal < MIN_WORDS) return Measured(null, c.tokenTotal)
        return Measured(ClipMeasurer.levelCode(c, lang, table), c.tokenTotal)
    }

    /** What the row shows: "N3", "B2", or "~B2" for Thai and Indonesian (bands of word frequency). Null when there is no level. */
    fun label(lang: String, code: String?): String? {
        val l = ClipLevels.label(lang, code) ?: return null
        return if (ClipLevels.scale(lang).byRank) "~$l" else l
    }
}
