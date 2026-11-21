package app.fluvour.reading.articles

import app.fluvour.data.ArticleLevelDao
import app.fluvour.data.ArticleLevelEntity
import app.fluvour.reading.LevelRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Measures an article once and remembers the level by url, so rows can show it again without fetching the text. */
@Singleton
class ArticleLevelStore @Inject constructor(private val dao: ArticleLevelDao, private val levels: LevelRepository) {
    /** Measures [text], stores the result and returns it. A storage failure is ignored: the level is still returned. */
    suspend fun measureAndStore(url: String, lang: String, source: String, title: String, text: String): ArticleLevelEntity {
        val m = ArticleLevel.measure(text, lang, levels.get(lang))
        val e = ArticleLevelEntity(url, lang, source, title, m.code, m.tokenTotal, System.currentTimeMillis())
        runCatching { dao.upsert(e) }
        return e
    }

    /** Stored levels of the given urls, by url. Urls not measured yet are missing from the map. */
    suspend fun levelsFor(urls: List<String>): Map<String, ArticleLevelEntity> =
        urls.distinct().chunked(200).flatMap { runCatching { dao.byUrls(it) }.getOrDefault(emptyList()) }.associateBy { it.url }
}
