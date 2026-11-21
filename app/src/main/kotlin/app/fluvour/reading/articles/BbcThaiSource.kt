package app.fluvour.reading.articles

/** Thai news from the BBC Thai feed. The feed address follows the pattern of the other BBC language feeds but was not confirmed. */
object BbcThaiSource : PageSource(
    ThaiParse.BBC_TH_ID, ThaiParse.BBC_TH_NAME, setOf("th"),
    "https://feeds.bbci.co.uk/thai/rss.xml", "BBC News Thai (personal use)", "application/rss+xml, application/xml, text/xml"
) {
    override fun parseList(html: String) = ThaiParse.bbcList(html)
    override fun parseArticle(html: String) = ThaiParse.bbcArticle(html)
}
