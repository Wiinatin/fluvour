package app.fluvour.reading.articles

/** VOA Learning English: slow news English, roughly B1 and up. Reuse terms not verified (VOA is a US government service). */
object VoaSource : PageSource("voa", "VOA Learning English", setOf("en"), EnglishParse.VOA_BASE + "/", "Voice of America, Learning English (personal use)") {
    override fun parseList(html: String) = EnglishParse.voaList(html)
    override fun parseArticle(html: String) = EnglishParse.voaArticle(html)
}
