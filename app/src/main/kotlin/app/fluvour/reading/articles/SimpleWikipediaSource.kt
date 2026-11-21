package app.fluvour.reading.articles

import app.fluvour.reading.ArticleHit
import app.fluvour.reading.WikipediaClient

/** Simple English Wikipedia (about A1 to B1): random articles. Uses WikipediaClient with the wiki code "simple"; saved as English. */
object SimpleWikipediaSource : ArticleSource {
    override val id = "simplewiki"
    override val name = "Simple English Wikipedia"
    override val langs = setOf("en")
    private const val WIKI = "simple"
    private const val LICENSE = "CC BY-SA 4.0 (Simple English Wikipedia)"

    override suspend fun list(lang: String, level: String?): Result<List<ArticleItem>> =
        WikipediaClient.list(WIKI, null).map { hits ->
            hits.map { ArticleItem("https://$WIKI.wikipedia.org/?curid=${it.pageId}", it.title, "en", id, name, it.preview) }
        }

    override suspend fun fetch(item: ArticleItem): Result<ArticleText> = runCatching {
        val pageId = item.url.substringAfter("?curid=", "").toLongOrNull() ?: error("Not a Wikipedia address")
        val f = WikipediaClient.fetch(ArticleHit(WIKI, pageId, item.title, item.summary)).getOrThrow()
        ArticleText(f.title, f.body, f.url, LICENSE, name)
    }
}
