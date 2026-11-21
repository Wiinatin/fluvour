package app.fluvour.reading.articles

/** English news lessons written for learners (the site offers several levels per story; the app measures the page it fetches). */
object BreakingNewsEnglishSource : PageSource("bne", "Breaking News English", setOf("en"), EnglishParse.BNE_BASE + "/", "Breaking News English (personal use)") {
    override fun parseList(html: String) = EnglishParse.bneList(html)
    override fun parseArticle(html: String) = EnglishParse.bneArticle(html)
}
