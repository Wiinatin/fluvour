package app.fluvour.reading.articles

/** Japanese, roughly N5 to N3. No official API: reads the site's own news list. Breaks quietly if NHK changes the format. */
object NhkEasySource : ArticleSource {
    override val id = NhkParse.EASY_ID
    override val name = NhkParse.EASY_NAME
    override val langs = setOf("ja")
    private const val LICENSE = "NHK News Web Easy (personal use)"

    override suspend fun list(lang: String, level: String?): Result<List<ArticleItem>> = runCatching {
        val r = PoliteHttp.get("${NhkParse.EASY_BASE}/news-list.json", "application/json")
        if (!r.ok) error("NHK Easy answered ${r.code}")
        NhkParse.easyList(r.body).ifEmpty { error("NHK Easy list was empty or in a new format") }
    }

    override suspend fun fetch(item: ArticleItem): Result<ArticleText> = runCatching {
        val r = PoliteHttp.get(item.url, "text/html")
        if (!r.ok) error("NHK Easy answered ${r.code}")
        val body = NhkParse.easyArticle(r.body) ?: error("Could not find the article text")
        ArticleText(item.title, body, item.url, LICENSE, name)
    }
}
