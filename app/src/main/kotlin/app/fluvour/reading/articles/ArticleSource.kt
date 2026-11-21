package app.fluvour.reading.articles

/** One article in a list, before its text is fetched. `url` is the stable key (reuse, saved copy, stored level). */
data class ArticleItem(
    val url: String, val title: String, val lang: String,
    val sourceId: String, val sourceName: String, val summary: String = ""
)

/** The full text of one article, ready to save. `license` is shown next to the article in the reader. */
data class ArticleText(val title: String, val body: String, val url: String, val license: String, val site: String)

/**
 * One website. An adapter lists articles and fetches one on request. A failure is returned as a failed Result, never thrown,
 * so the catalog can skip the source. `level` is the user's level code and may be null; sources may ignore it,
 * because the level of every article is measured by the app after fetching (see ArticleLevel).
 */
interface ArticleSource {
    val id: String
    val name: String
    val langs: Set<String>
    suspend fun list(lang: String, level: String?): Result<List<ArticleItem>>
    suspend fun fetch(item: ArticleItem): Result<ArticleText>
}
