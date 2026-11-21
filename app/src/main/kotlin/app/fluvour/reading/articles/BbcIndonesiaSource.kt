package app.fluvour.reading.articles

/** Indonesian news from the BBC feed (about 3 stories a day). The feed gives only a summary, so the article page is fetched when opened. */
object BbcIndonesiaSource : PageSource(
    IndonesianParse.BBC_ID, IndonesianParse.BBC_NAME, setOf("id"),
    "https://feeds.bbci.co.uk/indonesia/rss.xml", "BBC News Indonesia (personal use)", "application/rss+xml, application/xml, text/xml"
) {
    override fun parseList(html: String) = IndonesianParse.bbcList(html)
    override fun parseArticle(html: String) = IndonesianParse.bbcArticle(html)
}
