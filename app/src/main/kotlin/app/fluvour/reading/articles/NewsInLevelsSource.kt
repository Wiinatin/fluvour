package app.fluvour.reading.articles

/** Each story in three versions of rising difficulty; every version is its own row. */
object NewsInLevelsSource : PageSource("nil", "News in Levels", setOf("en"), EnglishParse.NIL_BASE + "/", "News in Levels (personal use)") {
    override fun parseList(html: String) = EnglishParse.nilList(html)
    override fun parseArticle(html: String) = EnglishParse.nilArticle(html)
}
