package app.fluvour.reading

/** One book's latest-read chapter, as the progress query returns it. `chaptersBefore` counts chapters ahead of this one, so it works whatever the first ordinal is. */
data class BookProgressRow(
    val bookId: String, val articleId: String, val percent: Float, val updatedAt: Long,
    val chapters: Int, val chaptersBefore: Int
)

/** A book to show under Continue reading: the chapter to open and how far through the whole book (0..1). */
data class ContinueItem(val bookId: String, val articleId: String, val overall: Float, val updatedAt: Long)

/** Pure helpers for the book library. No Android: runs on a plain JVM. */
object LibraryMath {
    /** Stable key for a catalog book, e.g. "gutenberg:1342". Own files use "file:<sourceId>". */
    fun catalogId(origin: String, key: Any) = "$origin:$key"

    /** How far through the whole book: finished chapters plus this chapter's share. */
    fun overall(chaptersBefore: Int, chapters: Int, percent: Float): Float {
        val n = chapters.coerceAtLeast(1)
        return ((chaptersBefore.coerceAtLeast(0) + percent.coerceIn(0f, 1f)) / n).coerceIn(0f, 1f)
    }

    /** One entry per book (its most recently read chapter, the later chapter on a tie), newest first. */
    fun continueList(rows: List<BookProgressRow>): List<ContinueItem> =
        rows.groupBy { it.bookId }.map { (_, g) ->
            val r = g.maxWith(compareBy<BookProgressRow>({ it.updatedAt }, { it.chaptersBefore }))
            ContinueItem(r.bookId, r.articleId, overall(r.chaptersBefore, r.chapters, r.percent), r.updatedAt)
        }.sortedByDescending { it.updatedAt }

    /** A file name safe on every device: "gutenberg:1342" becomes "gutenberg_1342". */
    fun safeFileName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
