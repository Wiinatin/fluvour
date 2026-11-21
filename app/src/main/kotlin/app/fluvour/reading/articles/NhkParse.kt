package app.fluvour.reading.articles

import app.fluvour.reading.MiniJson

/**
 * Plain-JVM parsing for the NHK adapters. The formats are NOT verified against the live sites (chat cannot reach them),
 * so every function tries more than one shape and returns empty or null instead of throwing when nothing fits.
 */
object NhkParse {
    const val EASY_BASE = "https://www3.nhk.or.jp/news/easy"
    const val EASY_ID = "nhk-easy"
    const val EASY_NAME = "NHK News Web Easy"
    const val NEWS_ID = "nhk-news"
    const val NEWS_NAME = "NHK News"

    /** news-list.json: dates map to lists of items that carry a `news_id`. Walks the whole document, newest first, at most [max] items. */
    fun easyList(json: String, max: Int = 30): List<ArticleItem> {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() ?: return emptyList()
        val found = ArrayList<Pair<String, ArticleItem>>()
        fun walk(n: Any?) {
            when (n) {
                is Map<*, *> -> {
                    val id = n["news_id"] as? String
                    if (id != null) {
                        val title = HtmlText.text(HtmlText.stripRuby((n["title"] as? String) ?: ""))
                        if (title.isNotBlank()) {
                            val time = (n["news_prearranged_time"] as? String) ?: ""
                            found += time to ArticleItem("$EASY_BASE/$id/$id.html", title, "ja", EASY_ID, EASY_NAME)
                        }
                    } else n.values.forEach { walk(it) }
                }
                is List<*> -> n.forEach { walk(it) }
            }
        }
        walk(root)
        return found.distinctBy { it.second.url }.sortedByDescending { it.first }.take(max).map { it.second }
    }

    /** Article page of News Web Easy: the text without furigana, or null when fewer than 40 characters are found. */
    fun easyArticle(html: String): String? {
        val h = HtmlText.stripRuby(html)
        val body = listOf("article-body", "article-main__body", "news-body")
            .firstNotNullOfOrNull { HtmlText.element(h, it) }?.let { HtmlText.text(it) }
            ?.takeIf { it.isNotBlank() } ?: HtmlText.paragraphs(h)
        return body.takeIf { it.length >= 40 }
    }

    /** RSS items of the regular NHK news feed as list items. */
    fun newsList(xml: String, max: Int = 30): List<ArticleItem> =
        Rss.parse(xml, max).map { ArticleItem(it.link, it.title, "ja", NEWS_ID, NEWS_NAME, HtmlText.text(it.description)) }

    /** Article page of regular NHK news: summary plus body when both are found, else all paragraphs. Null under 40 characters. */
    fun newsArticle(html: String): String? {
        val h = HtmlText.stripRuby(html)
        val parts = listOf("content--summary", "content--detail-body", "body-text")
            .mapNotNull { HtmlText.element(h, it) }.map { HtmlText.text(it) }.filter { it.isNotBlank() }.distinct()
        val body = if (parts.isNotEmpty()) parts.joinToString("\n\n") else HtmlText.paragraphs(h)
        return body.takeIf { it.length >= 40 }
    }
}
