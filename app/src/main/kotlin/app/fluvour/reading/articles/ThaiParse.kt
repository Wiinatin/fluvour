package app.fluvour.reading.articles

/**
 * Plain-JVM parsing for the Thai adapters. Thai has the thinnest choice of sources. Neither a Thai PBS feed nor the BBC Thai feed address
 * is verified (searches found no Thai PBS RSS and no confirmed BBC Thai address), and the page layouts are unknown, so the readers fall back to long paragraphs.
 */
object ThaiParse {
    const val PBS_BASE = "https://www.thaipbs.or.th"
    const val PBS_ID = "thaipbs"
    const val PBS_NAME = "Thai PBS"
    const val BBC_TH_ID = "bbc-th"
    const val BBC_TH_NAME = "BBC News ไทย"

    private val PBS = Regex("^/news/content/\\d+/?$")

    /** News links of the Thai PBS archive page, with the link text as title. Links without a readable title are dropped. */
    fun pbsList(html: String, max: Int = 25): List<ArticleItem> {
        val seen = LinkedHashMap<String, ArticleItem>()
        for (l in EnglishParse.links(html)) {
            val url = EnglishParse.absolute(PBS_BASE, l.href).substringBefore('?')
            if (!url.startsWith(PBS_BASE)) continue
            if (!PBS.containsMatchIn(url.removePrefix(PBS_BASE))) continue
            val title = l.text.lines().map { it.trim() }.firstOrNull { it.length >= 8 } ?: continue
            seen.getOrPut(url) { ArticleItem(url, title.take(200), "th", PBS_ID, PBS_NAME) }
        }
        return seen.values.take(max)
    }

    fun pbsArticle(html: String): String? =
        EnglishParse.article(html, listOf("class" to "news-content", "class" to "article-content", "class" to "content-detail"))

    fun bbcList(xml: String, max: Int = 25): List<ArticleItem> =
        Rss.parse(xml, max * 2).filter { it.link.contains("bbc.") }
            .map { ArticleItem(it.link.substringBefore('?').substringBefore('#'), HtmlText.text(it.title), "th", BBC_TH_ID, BBC_TH_NAME, HtmlText.text(it.description)) }
            .distinctBy { it.url }.take(max)

    /** BBC uses the same page structure for every language service, so the Indonesian reader works here too. */
    fun bbcArticle(html: String): String? = IndonesianParse.bbcArticle(html)
}
