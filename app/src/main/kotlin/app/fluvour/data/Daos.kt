package app.fluvour.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface DeckDao {
    @Query("SELECT * FROM decks WHERE languageCode = :lang AND deleted = 0 ORDER BY name")
    fun observe(lang: String): Flow<List<DeckEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(deck: DeckEntity)
    @Query("SELECT * FROM decks WHERE updatedAt > :since") suspend fun changedSince(since: Long): List<DeckEntity>
    @Query("SELECT * FROM decks WHERE id = :id") suspend fun get(id: String): DeckEntity?
}

@Dao interface CardDao {
    @Query("SELECT * FROM cards WHERE languageCode = :lang AND deleted = 0 ORDER BY createdAt DESC")
    fun observe(lang: String): Flow<List<CardEntity>>
    // Due = new cards, step cards whose time has come, and review cards due any time today.
    @Query("SELECT COUNT(*) FROM cards WHERE languageCode = :lang AND deleted = 0 AND (phase = 'NEW' OR (phase IN ('LEARNING','RELEARNING') AND dueAt <= :now) OR (phase = 'REVIEW' AND dueAt <= :endOfToday))")
    fun observeDueCount(lang: String, now: Long, endOfToday: Long): Flow<Int>
    @Query("SELECT * FROM cards WHERE languageCode = :lang AND deleted = 0 AND (phase = 'NEW' OR (phase IN ('LEARNING','RELEARNING') AND dueAt <= :now) OR (phase = 'REVIEW' AND dueAt <= :endOfToday)) ORDER BY CASE WHEN phase = 'NEW' THEN 1 ELSE 0 END, dueAt LIMIT :limit")
    suspend fun due(lang: String, now: Long, endOfToday: Long, limit: Int): List<CardEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(card: CardEntity)
    @Query("SELECT * FROM cards WHERE updatedAt > :since") suspend fun changedSince(since: Long): List<CardEntity>
    @Query("SELECT * FROM cards WHERE id = :id") suspend fun get(id: String): CardEntity?
    /** Words you have graduated past the learning steps; feeds known-word coverage (Phase 2A step 6). */
    @Query("SELECT word FROM cards WHERE languageCode = :lang AND deleted = 0 AND phase = 'REVIEW'")
    fun observeKnownWords(lang: String): Flow<List<String>>
    @Query("SELECT * FROM cards WHERE deleted = 0 ORDER BY languageCode, createdAt")
    suspend fun allActive(): List<CardEntity>
    @Query("UPDATE cards SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}

@Dao interface ReviewLogDao {
    @Insert suspend fun insert(log: ReviewLogEntity)
    @Query("SELECT * FROM review_log WHERE reviewedAt > :since") suspend fun changedSince(since: Long): List<ReviewLogEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAllIgnore(logs: List<ReviewLogEntity>)
    @Query("SELECT reviewedAt FROM review_log WHERE reviewedAt >= :since")
    fun observeSince(since: Long): Flow<List<Long>>   // feeds streak + heatmap in Phase 1
}

/** One row of the Read tab's saved list: a single article, or the first chapter of a book. Bodies are not loaded. */
data class ReadItem(
    val id: String, val sourceId: String, val title: String, val preview: String, val chapters: Int,
    val bookTitle: String?, val kind: String?, val meta: String?
)

/** A chapter in a book's contents list. */
data class ChapterRef(val id: String, val title: String, val ordinal: Int)

@Dao interface ArticleDao {
    @Query("SELECT a.id AS id, a.sourceId AS sourceId, a.title AS title, substr(a.body, 1, 90) AS preview, " +
        "(SELECT COUNT(*) FROM articles c WHERE c.sourceId = a.sourceId AND c.deleted = 0) AS chapters, " +
        "s.title AS bookTitle, s.kind AS kind, s.meta AS meta " +
        "FROM articles a LEFT JOIN sources s ON s.id = a.sourceId " +
        "WHERE a.languageCode = :lang AND a.deleted = 0 " +
        "AND a.ordinal = (SELECT MIN(m.ordinal) FROM articles m WHERE m.sourceId = a.sourceId AND m.deleted = 0) " +
        "ORDER BY a.createdAt DESC")
    fun observeItems(lang: String): Flow<List<ReadItem>>
    @Query("SELECT id, title, ordinal FROM articles WHERE sourceId = :sourceId AND deleted = 0 ORDER BY ordinal")
    fun observeChapters(sourceId: String): Flow<List<ChapterRef>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(rows: List<ArticleEntity>)
    @Query("UPDATE articles SET deleted = 1, updatedAt = :now WHERE sourceId = :sourceId") suspend fun softDeleteBySource(sourceId: String, now: Long)
    @Query("SELECT * FROM articles WHERE languageCode = :lang AND deleted = 0 ORDER BY createdAt DESC")
    fun observe(lang: String): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM articles WHERE id = :id") fun observeOne(id: String): Flow<ArticleEntity?>
    @Query("SELECT * FROM articles WHERE url = :url AND deleted = 0 LIMIT 1") suspend fun byUrl(url: String): ArticleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(a: ArticleEntity)
    @Query("UPDATE articles SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)
}

@Dao interface SourceDao {
    @Query("SELECT * FROM sources WHERE id = :id") fun observeOne(id: String): Flow<SourceEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: SourceEntity)
    @Query("SELECT * FROM sources WHERE updatedAt > :since") suspend fun changedSince(since: Long): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun get(id: String): SourceEntity?
    @Query("UPDATE sources SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)
}

@Dao interface LookupCacheDao {
    @Query("SELECT * FROM lookup_cache WHERE `key` = :key") suspend fun get(key: String): LookupCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: LookupCacheEntity)
}

@Dao interface SentenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: SentenceEntity)
    @Query("SELECT * FROM sentences WHERE id = :id") suspend fun get(id: String): SentenceEntity?
    /** The same text already mined from the same source (a null source matches only other null-source rows). */
    @Query("SELECT * FROM sentences WHERE deleted = 0 AND text = :text AND languageCode = :lang AND ((:sourceId IS NULL AND sourceId IS NULL) OR sourceId = :sourceId) LIMIT 1")
    suspend fun find(lang: String, text: String, sourceId: String?): SentenceEntity?
    @Query("SELECT s.*, src.title AS sourceTitle FROM sentences s LEFT JOIN sources src ON src.id = s.sourceId WHERE s.languageCode = :lang AND s.deleted = 0 ORDER BY s.createdAt DESC LIMIT :limit")
    fun observeRecent(lang: String, limit: Int): Flow<List<SentenceWithSource>>
    /** Every mined sentence in a language, for building quizzes (Phase 3). */
    @Query("SELECT * FROM sentences WHERE languageCode = :lang AND deleted = 0")
    suspend fun allForLang(lang: String): List<SentenceEntity>
    @Query("SELECT text FROM sentences WHERE sourceId = :sourceId AND deleted = 0")
    fun observeTextsForSource(sourceId: String): Flow<List<String>>
    @Query("SELECT * FROM sentences WHERE updatedAt > :since") suspend fun changedSince(since: Long): List<SentenceEntity>
    @Query("UPDATE sentences SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDelete(id: String, now: Long)
}

@Dao interface DictDao {
    @Query("SELECT * FROM dictionaries ORDER BY languageCode, priority") fun observeAll(): kotlinx.coroutines.flow.Flow<List<DictionaryEntity>>
    @Query("SELECT COUNT(*) FROM dictionaries WHERE languageCode = :lang") suspend fun countFor(lang: String): Int
    @Query("SELECT * FROM dictionaries WHERE languageCode = :lang AND title = :title AND revision = :rev LIMIT 1") suspend fun find(lang: String, title: String, rev: String): DictionaryEntity?
    @Insert suspend fun insertDictionary(d: DictionaryEntity)
    @Insert suspend fun insertTerms(rows: List<DictTermEntity>)
    @Insert suspend fun insertMeta(rows: List<DictMetaEntity>)
    @Query("UPDATE dictionaries SET enabled = :on WHERE id = :id") suspend fun setEnabled(id: String, on: Boolean)
    @Query("DELETE FROM dict_terms WHERE dictId = :id") suspend fun deleteTerms(id: String)
    @Query("DELETE FROM dict_meta WHERE dictId = :id") suspend fun deleteMeta(id: String)
    @Query("DELETE FROM dictionaries WHERE id = :id") suspend fun deleteDictionary(id: String)
    @Query("SELECT t.*, d.title AS dictTitle FROM dict_terms t JOIN dictionaries d ON d.id = t.dictId WHERE t.term IN (:forms) AND d.languageCode = :lang AND d.enabled = 1 ORDER BY d.priority, t.score DESC LIMIT 40")
    suspend fun terms(lang: String, forms: List<String>): List<TermHit>
    @Query("SELECT m.*, d.title AS dictTitle FROM dict_meta m JOIN dictionaries d ON d.id = m.dictId WHERE m.term IN (:forms) AND d.languageCode = :lang AND d.enabled = 1 ORDER BY d.priority LIMIT 60")
    suspend fun meta(lang: String, forms: List<String>): List<MetaHit>
}

@Dao interface ReaderDao {
    @Query("SELECT * FROM reading_progress WHERE articleId = :id") suspend fun progress(id: String): ReadingProgressEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(p: ReadingProgressEntity)

    @Query("SELECT * FROM bookmarks WHERE articleId = :id AND deleted = 0 ORDER BY para, ch")
    fun observeBookmarks(id: String): Flow<List<BookmarkEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBookmark(b: BookmarkEntity)
    @Query("UPDATE bookmarks SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun deleteBookmark(id: String, now: Long)

    @Query("SELECT * FROM highlights WHERE articleId = :id AND deleted = 0 ORDER BY para, startCh")
    fun observeHighlights(id: String): Flow<List<HighlightEntity>>
    @Query("SELECT * FROM highlights WHERE articleId = :id AND para = :para AND deleted = 0 AND startCh < :end AND endCh > :start")
    suspend fun overlapping(id: String, para: Int, start: Int, end: Int): List<HighlightEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertHighlights(rows: List<HighlightEntity>)
    @Query("UPDATE highlights SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun deleteHighlight(id: String, now: Long)
    @Query("UPDATE highlights SET note = :note, updatedAt = :now WHERE id = :id") suspend fun setNote(id: String, note: String?, now: Long)
}

@Dao interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC LIMIT :n") fun observe(n: Int): Flow<List<WatchHistoryEntity>>
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC LIMIT 1") suspend fun latest(): WatchHistoryEntity?
    @Query("SELECT * FROM watch_history WHERE id = :id") suspend fun get(id: String): WatchHistoryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: WatchHistoryEntity)
    /** Only touches a row that still exists, so a video you removed from history stays removed while it plays. */
    @Query("UPDATE watch_history SET positionMs = :pos, durationMs = CASE WHEN :dur > 0 THEN :dur ELSE durationMs END, lastWatchedAt = :now WHERE id = :id")
    suspend fun progress(id: String, pos: Long, dur: Long, now: Long)
    @Query("DELETE FROM watch_history WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM watch_history WHERE id NOT IN (SELECT id FROM watch_history ORDER BY lastWatchedAt DESC LIMIT :keep)") suspend fun trim(keep: Int)
}
