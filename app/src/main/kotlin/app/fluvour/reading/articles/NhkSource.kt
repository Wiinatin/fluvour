package app.fluvour.reading.articles

/** Japanese, regular NHK news (about N2 to N1) from the public news feed. Breaks quietly if NHK changes the feed or the page layout. */
object NhkSource : ArticleSource {
    override val id = NhkParse.NEWS_ID
    override val name = NhkParse.NEWS_NAME
    override val langs = setOf("ja")
    private const val FEED = "https://www3.nhk.or.jp/rss/news/cat0.xml"
    private const val LICENSE = "NHK News (personal use)"

    override suspend fun list(lang: String, level: String?): Result<List<ArticleItem>> = runCatching {
        val r = PoliteHttp.get(FEED, "application/rss+xml, application/xml, text/xml")
        if (!r.ok) error("NHK News answered ${r.code}")
        NhkParse.newsList(r.body).ifEmpty { error("NHK News feed was empty or in a new format") }
    }

    override suspend fun fetch(item: ArticleItem): Result<ArticleText> = runCatching {
        val r = PoliteHttp.get(item.url, "text/html")
        if (!r.ok) error("NHK News answered ${r.code}")
        val body = NhkParse.newsArticle(r.body) ?: error("Could not find the article text")
        ArticleText(item.title, body, item.url, LICENSE, name)
    }
}
