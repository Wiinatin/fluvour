package app.fluvour.reading

import app.fluvour.data.SentenceDao
import app.fluvour.data.SentenceEntity
import app.fluvour.data.SentenceWithSource
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sentence mining (Phase 2A step 4): saves a sentence with its source and where it sits in that source.
 * Saving the same sentence from the same source twice returns the row that already exists.
 */
@Singleton
class SentenceRepository @Inject constructor(private val sentences: SentenceDao) {
    fun observeRecent(lang: String, limit: Int = 50): Flow<List<SentenceWithSource>> = sentences.observeRecent(lang, limit)
    fun observeTextsForSource(sourceId: String): Flow<List<String>> = sentences.observeTextsForSource(sourceId)

    /** Returns the id of the saved sentence (new or existing). `position` is built with [position]. */
    suspend fun mine(userId: String, lang: String, text: String, sourceId: String?, position: String?): String {
        val clean = text.trim()
        sentences.find(lang, clean, sourceId)?.let { return it.id }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        sentences.upsert(SentenceEntity(id, userId, lang, clean, null, sourceId, position, now, now))
        return id
    }

    suspend fun delete(id: String) = sentences.softDelete(id, System.currentTimeMillis())

    companion object {
        /**
         * Where a sentence sits in an article: (for a book, the chapter number from 0, then) paragraph number and character range inside that paragraph (start inclusive,
         * end exclusive). Stored as text so it syncs; other sources (video, comic) will use their own keys.
         */
        fun position(paragraph: Int, start: Int, end: Int, chapter: Int? = null): String =
            JSONObject().apply { if (chapter != null) put("chapter", chapter) }.put("paragraph", paragraph).put("start", start).put("end", end).toString()

        /** Where a sentence sits in a video: the subtitle line's start and end, in milliseconds from the start of the video. */
        fun videoPosition(startMs: Long, endMs: Long): String = JSONObject().put("startMs", startMs).put("endMs", endMs).toString()
    }
}
