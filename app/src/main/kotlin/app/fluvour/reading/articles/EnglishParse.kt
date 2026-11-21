package app.fluvour.reading.articles

/**
 * Plain-JVM parsing for the English adapters. Page layouts are NOT verified against the live sites (chat cannot reach them),
 * so each article reader tries the likely containers first and falls back to the long paragraphs of the page.
 */
object EnglishParse {
    const val BNE_BASE = "https://breakingnewsenglish.com"
    const val NIL_BASE = "https://www.newsinlevels.com"
    const val VOA_BASE = "https://learningenglish.voanews.com"

    data class Link(val href: String, val text: String)

    /** Every `<a href>` of the page with its visible text. */
    fun links(html: String): List<Link> =
        Regex("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>").findAll(html)
            .map { Link(it.groupValues[1].substringBefore('#').trim(), HtmlText.text(it.groupValues[2])) }.toList()

    fun absolute(base: String, href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base + href
        else -> "$base/$href"
    }

    /** "ai-boom-hits-markets" becomes "Ai boom hits markets". */
    fun slugTitle(slug: String): String =
        slug.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private fun path(url: String): String = url.substringAfter("://").substringAfter('/', "").let { "/" + it }.substringBefore('?')

    // Breaking News English: lessons look like /2410/241027-ai-boom.html, with optional level files like -3.html.
    private val BNE = Regex("^/(\\d{4}/\\d{6}-[a-z0-9-]+?)(?:-\\d)?\\.html$")

    fun bneList(html: String, max: Int = 20): List<ArticleItem> {
        val seen = LinkedHashMap<String, ArticleItem>()
        for (l in links(html)) {
            val url = absolute(BNE_BASE, l.href)
            if (!url.contains("breakingnewsenglish.com")) continue
            val key = BNE.find(path(url))?.groupValues?.get(1) ?: continue
            seen.getOrPut(key) {
                ArticleItem("$BNE_BASE/$key.html", slugTitle(key.substringAfter('/').substringAfter('-')), "en", "bne", "Breaking News English")
            }
        }
        return seen.values.take(max)
    }

    fun bneArticle(html: String): String? = article(html, listOf("id" to "article", "id" to "reading", "class" to "article"))

    // News in Levels: /products/some-title-level-1/ (levels 1 to 3 of the same story).
    private val NIL = Regex("^/products/([a-z0-9-]+?)-level-([123])/?$")

    /** Up to [maxStories] stories, each with the levels the page links to. */
    fun nilList(html: String, maxStories: Int = 8): List<ArticleItem> {
        val stories = LinkedHashMap<String, MutableMap<Int, String>>()
        for (l in links(html)) {
            val url = absolute(NIL_BASE, l.href)
            if (!url.contains("newsinlevels.com")) continue
            val m = NIL.find(path(url)) ?: continue
            stories.getOrPut(m.groupValues[1]) { sortedMapOf() }[m.groupValues[2].toInt()] = url
        }
        return stories.entries.take(maxStories).flatMap { (slug, levels) ->
            levels.map { (n, url) -> ArticleItem(url, slugTitle(slug), "en", "nil", "News in Levels", "News in Levels, version $n of 3") }
        }
    }

    fun nilArticle(html: String): String? = article(html, listOf("id" to "nContent", "class" to "nContent"))

    // VOA Learning English: /a/some-title/1234567.html
    private val VOA = Regex("^/a/([^/]+)/\\d+\\.html$")

    fun voaList(html: String, max: Int = 25): List<ArticleItem> {
        val seen = LinkedHashMap<String, ArticleItem>()
        for (l in links(html)) {
            val url = absolute(VOA_BASE, l.href)
            if (!url.contains("learningenglish.voanews.com")) continue
            val m = VOA.find(path(url)) ?: continue
            val title = l.text.takeIf { it.length in 8..160 && '\n' !in it } ?: slugTitle(m.groupValues[1])
            seen.getOrPut(url.substringBefore('?')) { ArticleItem(url.substringBefore('?'), title, "en", "voa", "VOA Learning English") }
        }
        return seen.values.take(max)
    }

    fun voaArticle(html: String): String? = article(html, listOf("id" to "article-content", "class" to "wsw"))

    // ---- shared ----

    /** The text of the first container that holds at least [minLen] characters, else the long paragraphs of the page. */
    fun article(html: String, containers: List<Pair<String, String>>, minLen: Int = 150): String? {
        for ((attr, part) in containers) {
            val inner = HtmlText.element(html, part, attr) ?: continue
            val t = HtmlText.text(inner)
            if (t.length >= minLen) return t
        }
        val p = Regex("(?is)<p\\b[^>]*>(.*?)</p>").findAll(html).map { HtmlText.text(it.groupValues[1]) }
            .filter { it.length >= 60 }.take(15).joinToString("\n\n")
        return p.takeIf { it.length >= minLen }
    }
}
