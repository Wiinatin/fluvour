package app.fluvour.reading.articles

/** All sources in one place. Add a new adapter to [all]; a source that fails is skipped and named in `failed`. */
object ArticleCatalog {
    val all: List<ArticleSource> = listOf(
        NhkEasySource, NhkSource,
        BreakingNewsEnglishSource, NewsInLevelsSource, VoaSource, SimpleWikipediaSource,
        BbcIndonesiaSource, DetikSource,
        ThaiPbsSource, BbcThaiSource,
        WikipediaSource
    )

    class Listing(val items: List<ArticleItem>, val failed: List<String>)

    fun forLang(lang: String): List<ArticleSource> = all.filter { lang.substringBefore('-').lowercase() in it.langs }

    /** Lists every source of the language, one after the other (polite), and skips the ones that fail. */
    suspend fun list(lang: String, level: String?): Listing {
        val items = ArrayList<ArticleItem>(); val failed = ArrayList<String>()
        for (s in forLang(lang)) {
            val r = runCatching { s.list(lang, level) }.getOrElse { Result.failure(it) }
            r.onSuccess { items += it }.onFailure { failed += s.name }
        }
        return Listing(items, failed)
    }

    suspend fun fetch(item: ArticleItem): Result<ArticleText> {
        val s = all.firstOrNull { it.id == item.sourceId } ?: return Result.failure(IllegalStateException("Unknown source"))
        return runCatching { s.fetch(item) }.getOrElse { Result.failure(it) }
    }
}
