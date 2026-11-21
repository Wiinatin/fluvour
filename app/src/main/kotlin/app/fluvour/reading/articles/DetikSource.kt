package app.fluvour.reading.articles

/** Indonesian news from the detikNews feed. The feed address is unverified; if it fails the source is skipped. */
object DetikSource : PageSource(
    IndonesianParse.DETIK_ID, IndonesianParse.DETIK_NAME, setOf("id"),
    "https://news.detik.com/rss", "detikNews (personal use)", "application/rss+xml, application/xml, text/xml"
) {
    override fun parseList(html: String) = IndonesianParse.detikList(html)
    override fun parseArticle(html: String) = IndonesianParse.detikArticle(html)
}
