package app.fluvour.reading

import androidx.room.withTransaction
import app.fluvour.data.AppDatabase
import app.fluvour.data.BookmarkEntity
import app.fluvour.data.HighlightEntity
import app.fluvour.data.ReaderDao
import app.fluvour.data.ReadingProgressEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Saved reading position, bookmarks and highlights for the reader. All local; rows carry a userId and soft-delete like the other tables. */
@Singleton
class ReaderRepository @Inject constructor(private val dao: ReaderDao, private val db: AppDatabase) {
    suspend fun progress(articleId: String) = dao.progress(articleId)
    suspend fun saveProgress(userId: String, articleId: String, para: Int, ch: Int, percent: Float) =
        dao.saveProgress(ReadingProgressEntity(articleId, userId, para, ch, percent, System.currentTimeMillis()))

    fun observeBookmarks(articleId: String) = dao.observeBookmarks(articleId)
    suspend fun addBookmark(userId: String, articleId: String, para: Int, ch: Int, snippet: String, percent: Float) {
        val now = System.currentTimeMillis()
        dao.upsertBookmark(BookmarkEntity(UUID.randomUUID().toString(), userId, articleId, para, ch, snippet, percent, now, now))
    }
    suspend fun deleteBookmarks(ids: List<String>) { val now = System.currentTimeMillis(); ids.forEach { dao.deleteBookmark(it, now) } }

    fun observeHighlights(articleId: String) = dao.observeHighlights(articleId)
    suspend fun deleteHighlight(id: String) = dao.deleteHighlight(id, System.currentTimeMillis())
    suspend fun setNote(id: String, note: String?) = dao.setNote(id, note?.trim()?.takeIf { it.isNotEmpty() }, System.currentTimeMillis())

    /**
     * Highlights `[start, end)` of paragraph `para`. Highlights that overlap the new one are cut back (or removed if fully covered),
     * so two colours never sit on the same characters. Re-colouring the exact same range keeps its note.
     */
    suspend fun highlight(userId: String, articleId: String, para: Int, start: Int, end: Int, text: String, color: Int) {
        if (end <= start) return
        val now = System.currentTimeMillis()
        db.withTransaction {
            val old = dao.overlapping(articleId, para, start, end)
            val rows = mutableListOf<HighlightEntity>()
            for (h in old) {
                val left = h.startCh < start
                val right = h.endCh > end
                when {
                    !left && !right -> dao.deleteHighlight(h.id, now)
                    left && !right -> rows += h.copy(endCh = start, text = h.text.take(start - h.startCh), updatedAt = now)
                    !left && right -> rows += h.copy(startCh = end, text = h.text.drop(end - h.startCh), updatedAt = now)
                    else -> {   // the new highlight sits inside an old one: keep both sides
                        rows += h.copy(endCh = start, text = h.text.take(start - h.startCh), updatedAt = now)
                        rows += h.copy(id = UUID.randomUUID().toString(), startCh = end, text = h.text.drop(end - h.startCh), note = null, createdAt = now, updatedAt = now)
                    }
                }
            }
            val sameRange = old.firstOrNull { it.startCh == start && it.endCh == end }
            rows += HighlightEntity(UUID.randomUUID().toString(), userId, articleId, para, start, end, color, text, sameRange?.note, now, now)
            dao.upsertHighlights(rows)
        }
    }
}
