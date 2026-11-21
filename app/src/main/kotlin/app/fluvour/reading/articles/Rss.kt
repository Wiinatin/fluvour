package app.fluvour.reading.articles

data class RssItem(val title: String, val link: String, val description: String, val pubDate: String)

/** Plain-JVM RSS 2.0 reader (title, link, description, pubDate of each item). Handles CDATA. Items without a title or link are dropped. */
object Rss {
    fun parse(xml: String, max: Int = 50): List<RssItem> =
        Regex("(?is)<item\\b.*?</item>").findAll(xml).map { it.value }
            .map { RssItem(field(it, "title"), field(it, "link"), field(it, "description"), field(it, "pubDate")) }
            .filter { it.title.isNotBlank() && it.link.isNotBlank() }
            .take(max).toList()

    private fun field(item: String, tag: String): String {
        val m = Regex("(?is)<$tag\\b[^>]*>(.*?)</$tag>").find(item) ?: return ""
        val v = m.groupValues[1].trim()
        val c = Regex("(?s)^<!\\[CDATA\\[(.*)\\]\\]>$").find(v)
        return (if (c != null) c.groupValues[1] else HtmlText.decode(v)).trim()
    }
}
