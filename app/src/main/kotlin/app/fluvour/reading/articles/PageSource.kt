package app.fluvour.reading.articles

/** A site whose list is one web page or feed and whose articles are web pages. Subclasses only say how to read them. */
abstract class PageSource(
    override val id: String, override val name: String, override val langs: Set<String>,
    private val listUrl: String, private val license: String,
    private val listAccept: String = "text/html"
) : ArticleSource {
    protected abstract fun parseList(html: String): List<ArticleItem>
    protected abstract fun parseArticle(html: String): String?

    override suspend fun list(lang: String, level: String?): Result<List<ArticleItem>> = runCatching {
        val r = PoliteHttp.get(listUrl, listAccept)
        if (!r.ok) error("$name answered ${r.code}")
        parseList(r.body).ifEmpty { error("$name list was empty or in a new format") }
    }

    override suspend fun fetch(item: ArticleItem): Result<ArticleText> = runCatching {
        val r = PoliteHttp.get(item.url, "text/html")
        if (!r.ok) error("$name answered ${r.code}")
        val body = parseArticle(r.body) ?: error("Could not find the article text")
        ArticleText(item.title, body, item.url, license, name)
    }
}
