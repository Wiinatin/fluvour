package app.fluvour.reading

import app.fluvour.data.ArticleDao
import app.fluvour.data.ArticleEntity
import app.fluvour.data.SourceDao
import app.fluvour.data.SourceEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArticleRepository @Inject constructor(private val articles: ArticleDao, private val sources: SourceDao) {
    fun observe(lang: String) = articles.observe(lang)
    fun observeOne(id: String) = articles.observeOne(id)
    /** The saved list: one entry per article or book, without the text. */
    fun observeItems(lang: String) = articles.observeItems(lang)
    fun observeChapters(sourceId: String) = articles.observeChapters(sourceId)
    fun observeSource(sourceId: String) = sources.observeOne(sourceId)

    /** Downloads the article and saves it with a `sources` row (kind = article). Returns the article id; reuses a saved copy. */
    suspend fun import(userId: String, hit: ArticleHit): Result<String> {
        val existing = articles.byUrl("https://${hit.lang}.wikipedia.org/?curid=${hit.pageId}")
        if (existing != null) return Result.success(existing.id)
        val fetched = WikipediaClient.fetch(hit).getOrElse { return Result.failure(it) }
        val now = System.currentTimeMillis()
        val sourceId = UUID.randomUUID().toString()
        sources.upsert(SourceEntity(sourceId, userId, "article", fetched.title, fetched.url,
            "{\"license\":\"${WikipediaClient.LICENSE}\"}", now, now))
        val id = UUID.randomUUID().toString()
        articles.upsert(ArticleEntity(id, userId, sourceId, hit.lang, fetched.title, fetched.body, fetched.url,
            WikipediaClient.LICENSE, now, now))
        return Result.success(id)
    }

    /** Removes an article, or a whole book with all its chapters. Cards and mined sentences made from it stay. */
    suspend fun deleteSource(sourceId: String) {
        val now = System.currentTimeMillis()
        articles.softDeleteBySource(sourceId, now); sources.softDelete(sourceId, now)
    }
}
