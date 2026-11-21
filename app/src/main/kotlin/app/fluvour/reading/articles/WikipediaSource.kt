package app.fluvour.reading.articles

import app.fluvour.reading.ArticleHit
import app.fluvour.reading.WikipediaClient

/** Wikipedia in ja, en, th and id, as the fallback source: random articles with a real text. Uses the existing WikipediaClient. */
object WikipediaSource : ArticleSource {
    override val id = "wikipedia"
    override val name = "Wikipedia"
    override val langs = setOf("ja", "en", "th", "id")

    override suspend fun list(lang: String, level: String?): Result<List<ArticleItem>> =
        WikipediaClient.list(lang, null).map { hits ->
            hits.map { ArticleItem("https://$lang.wikipedia.org/?curid=${it.pageId}", it.title, lang, id, name, it.preview) }
        }

    override suspend fun fetch(item: ArticleItem): Result<ArticleText> = runCatching {
        val pageId = item.url.substringAfter("?curid=", "").toLongOrNull() ?: error("Not a Wikipedia address")
        val f = WikipediaClient.fetch(ArticleHit(item.lang, pageId, item.title, item.summary)).getOrThrow()
        ArticleText(f.title, f.body, f.url, WikipediaClient.LICENSE, name)
    }
}
