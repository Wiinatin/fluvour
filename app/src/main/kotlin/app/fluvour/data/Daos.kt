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

/**
 * A card with the WORD facet's memory state laid over the card's own (legacy) columns, so every reader of a CardEntity sees the
 * facet's phase, due time and so on. The facet is the source of truth; the card's columns are only the fallback for a card with no facet.
 */
internal const val CARD_WITH_WORD = "SELECT c.id, c.userId, c.deckId, c.languageCode, c.word, c.meaning, c.example, c.notes, c.imagePrompt, c.imageUrl, c.sentenceId, " +
    "COALESCE(f.phase, c.phase) AS phase, COALESCE(f.stability, c.stability) AS stability, COALESCE(f.difficulty, c.difficulty) AS difficulty, COALESCE(f.step, c.step) AS step, " +
    "CASE WHEN f.id IS NULL THEN c.lastReviewAt ELSE f.lastReviewAt END AS lastReviewAt, CASE WHEN f.id IS NULL THEN c.dueAt ELSE f.dueAt END AS dueAt, " +
    "COALESCE(f.scheduledDays, c.scheduledDays) AS scheduledDays, COALESCE(f.reps, c.reps) AS reps, COALESCE(f.lapses, c.lapses) AS lapses, " +
    "c.createdAt, c.updatedAt, c.deleted, c.variantWord, c.variantExample, c.root, c.lemma, c.variantExampleMeaning FROM cards c LEFT JOIN card_facets f ON f.cardId = c.id AND f.kind = 'WORD' "

/** Due = new facets, step facets whose time has come, and review facets due any time today (WORD facets only until the mode row, step 4). */
internal const val WORD_DUE = "f.deleted = 0 AND (f.phase = 'NEW' OR (f.phase IN ('LEARNING','RELEARNING') AND f.dueAt <= :now) OR (f.phase = 'REVIEW' AND f.dueAt <= :endOfToday))"

@Dao interface CardDao {
    @Query(CARD_WITH_WORD + "WHERE c.languageCode = :lang AND c.deleted = 0 ORDER BY c.createdAt DESC")
    fun observe(lang: String): Flow<List<CardEntity>>
    @Query("SELECT COUNT(*) FROM cards c JOIN card_facets f ON f.cardId = c.id WHERE c.languageCode = :lang AND c.deleted = 0 AND f.unlockedAt IS NOT NULL AND " + WORD_DUE)
    fun observeDueCount(lang: String, now: Long, endOfToday: Long): Flow<Int>
    @Query(CARD_WITH_WORD + "WHERE c.languageCode = :lang AND c.deleted = 0 AND f.id IS NOT NULL AND " + WORD_DUE + " ORDER BY CASE WHEN f.phase = 'NEW' THEN 1 ELSE 0 END, f.dueAt LIMIT :limit")
    suspend fun due(lang: String, now: Long, endOfToday: Long, limit: Int): List<CardEntity>
    @Query(CARD_WITH_WORD + "WHERE c.languageCode = :lang AND c.deleted = 0 AND f.phase != 'NEW' ORDER BY f.dueAt LIMIT :limit")
    suspend fun upcoming(lang: String, limit: Int): List<CardEntity>   // learned cards by due time, for the voices made ahead
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(card: CardEntity)
    @Query(CARD_WITH_WORD + "WHERE c.updatedAt > :since") suspend fun changedSince(since: Long): List<CardEntity>
    @Query(CARD_WITH_WORD + "WHERE c.id = :id") suspend fun get(id: String): CardEntity?
    @Query(CARD_WITH_WORD + "WHERE c.id IN (:ids)") suspend fun getMany(ids: List<String>): List<CardEntity>
    /** Words you have graduated past the learning steps; feeds known-word coverage (Phase 2A step 6). */
    @Query("SELECT c.word FROM cards c JOIN card_facets f ON f.cardId = c.id AND f.kind = 'WORD' WHERE c.languageCode = :lang AND c.deleted = 0 AND f.phase = 'REVIEW'")
    fun observeKnownWords(lang: String): Flow<List<String>>
    /** Words that have a card but have not graduated yet; feeds the "learning" colour in subtitles. */
    @Query("SELECT c.word FROM cards c JOIN card_facets f ON f.cardId = c.id AND f.kind = 'WORD' WHERE c.languageCode = :lang AND c.deleted = 0 AND f.phase != 'REVIEW'")
    fun observeLearningWords(lang: String): Flow<List<String>>
    /** One-shot version of [observeKnownWords], for clip recommendations (roadmap 4.2). */
    @Query("SELECT c.word FROM cards c JOIN card_facets f ON f.cardId = c.id AND f.kind = 'WORD' WHERE c.languageCode = :lang AND c.deleted = 0 AND f.phase = 'REVIEW'")
    suspend fun knownWordsOnce(lang: String): List<String>
    /** Words still being learned, newest activity first, for the clip suggestion prompt. */
    @Query("SELECT c.word FROM cards c JOIN card_facets f ON f.cardId = c.id AND f.kind = 'WORD' WHERE c.languageCode = :lang AND c.deleted = 0 AND f.phase != 'REVIEW' ORDER BY f.updatedAt DESC LIMIT :limit")
    suspend fun recentLearningWords(lang: String, limit: Int): List<String>
    @Query(CARD_WITH_WORD + "WHERE c.deleted = 0 ORDER BY c.languageCode, c.createdAt")
    suspend fun allActive(): List<CardEntity>
    @Query("UPDATE cards SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
    /** Speaking (Phase 5): a live card of this language with this word, ignoring upper and lower case, or null. */
    @Query(CARD_WITH_WORD + "WHERE c.languageCode = :lang AND c.word = :word COLLATE NOCASE AND c.deleted = 0 LIMIT 1")
    suspend fun findWord(lang: String, word: String): CardEntity?
}

/** Due facets of one kind (the mode row). */
data class KindCount(val kind: String, val n: Int)

/** Learning path step 2: one row per way of studying a card. */
@Dao interface CardFacetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(facet: CardFacetEntity)
    /** Used when a card is created or pulled: never overwrites a facet that already has state. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(facets: List<CardFacetEntity>)
    @Query("SELECT * FROM card_facets WHERE cardId = :cardId AND kind = :kind") suspend fun get(cardId: String, kind: String): CardFacetEntity?
    @Query("SELECT * FROM card_facets WHERE cardId = :cardId") suspend fun forCard(cardId: String): List<CardFacetEntity>
    @Query("SELECT * FROM card_facets WHERE updatedAt > :since") suspend fun changedSince(since: Long): List<CardFacetEntity>
    @Query("UPDATE card_facets SET deleted = 1, updatedAt = :now WHERE cardId = :cardId") suspend fun softDeleteFor(cardId: String, now: Long)
    /** Open facets that are due (new, steps whose time has come, reviews due today) in a language; [kind] null means every kind (Mixed). */
    @Query("SELECT f.* FROM card_facets f JOIN cards c ON c.id = f.cardId WHERE c.languageCode = :lang AND c.deleted = 0 AND f.unlockedAt IS NOT NULL AND (:kind IS NULL OR f.kind = :kind) AND " + WORD_DUE + " ORDER BY CASE WHEN f.phase = 'NEW' THEN 1 ELSE 0 END, f.dueAt LIMIT :limit")
    suspend fun due(lang: String, kind: String?, now: Long, endOfToday: Long, limit: Int): List<CardFacetEntity>
    /** Due count of each kind; feeds the badges on the mode row. */
    @Query("SELECT f.kind AS kind, COUNT(*) AS n FROM card_facets f JOIN cards c ON c.id = f.cardId WHERE c.languageCode = :lang AND c.deleted = 0 AND f.unlockedAt IS NOT NULL AND " + WORD_DUE + " GROUP BY f.kind")
    fun observeDueByKind(lang: String, now: Long, endOfToday: Long): Flow<List<KindCount>>
    /** Learning path: the cards of a language whose word is one of [words] (a unit's target words). */
    @Query("SELECT c.id AS id, c.word AS word FROM cards c WHERE c.languageCode = :lang AND c.deleted = 0 AND c.word IN (:words)")
    suspend fun cardsForWords(lang: String, words: List<String>): List<CardWord>
    /** Learning path: open facets that are due on those cards (the review-count nudge on a unit). */
    @Query("SELECT COUNT(*) FROM card_facets f JOIN cards c ON c.id = f.cardId WHERE c.languageCode = :lang AND c.deleted = 0 AND c.word IN (:words) AND f.unlockedAt IS NOT NULL AND " + WORD_DUE)
    suspend fun dueForWords(lang: String, words: List<String>, now: Long, endOfToday: Long): Int
    /** Learning path step 8: the WORD facet's memory of each of those words (what the older-item selector scores). */
    @Query("SELECT c.word AS word, f.phase AS phase, f.stability AS stability, f.lapses AS lapses, f.dueAt AS dueAt, f.lastReviewAt AS lastReviewAt FROM cards c JOIN card_facets f ON f.cardId = c.id AND f.kind = 'WORD' WHERE c.languageCode = :lang AND c.deleted = 0 AND f.deleted = 0 AND c.word IN (:words)")
    suspend fun memoryForWords(lang: String, words: List<String>): List<WordMemoryRow>
    /** Learning path: words whose WORD facet is in review with an interval of [minDays] days or more (what a unit test may use). */
    @Query("SELECT DISTINCT c.word FROM cards c JOIN card_facets f ON f.cardId = c.id AND f.kind = 'WORD' WHERE c.languageCode = :lang AND c.deleted = 0 AND f.deleted = 0 AND f.unlockedAt IS NOT NULL AND f.phase = 'REVIEW' AND f.scheduledDays >= :minDays")
    suspend fun matureWords(lang: String, minDays: Int): List<String>
    /** Learning path step 8: a word missed in the unit test is due for review today (only if its review is scheduled later). */
    @Query("UPDATE card_facets SET dueAt = :now, updatedAt = :now WHERE cardId = :cardId AND kind = 'WORD' AND deleted = 0 AND unlockedAt IS NOT NULL AND phase = 'REVIEW' AND dueAt IS NOT NULL AND dueAt > :now")
    suspend fun pullDue(cardId: String, now: Long)
}

data class CardWord(val id: String, val word: String)
data class WordMemoryRow(val word: String, val phase: String, val stability: Double, val lapses: Int, val dueAt: Long?, val lastReviewAt: Long?)

/** Learning path step 6: which units are graduated. */
@Dao interface PathUnitDao {
    @Query("SELECT unitId FROM path_units WHERE languageCode = :lang AND graduatedAt IS NOT NULL")
    fun observeGraduated(lang: String): Flow<List<String>>
    @Query("SELECT * FROM path_units WHERE unitId = :unitId") suspend fun get(unitId: String): PathUnitEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: PathUnitEntity)
}

@Dao interface ReviewLogDao {
    @Insert suspend fun insert(log: ReviewLogEntity)
    /** For undo: the most recent review of a card, and removing it. */
    @Query("SELECT id FROM review_log WHERE cardId = :cardId ORDER BY reviewedAt DESC LIMIT 1") suspend fun latestId(cardId: String): String?
    @Query("SELECT id FROM review_log WHERE facetId = :facetId ORDER BY reviewedAt DESC LIMIT 1") suspend fun latestIdForFacet(facetId: String): String?
    /** Reviews of one facet rated above Again; feeds the unlock rule. */
    @Query("SELECT COUNT(*) FROM review_log WHERE facetId = :facetId AND rating > 1") suspend fun passedCount(facetId: String): Int
    @Query("DELETE FROM review_log WHERE id = :id") suspend fun delete(id: String)
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
    @Query("SELECT s.*, src.title AS sourceTitle, src.kind AS sourceKind, src.url AS sourceUrl FROM sentences s LEFT JOIN sources src ON src.id = s.sourceId WHERE s.languageCode = :lang AND s.deleted = 0 ORDER BY s.createdAt DESC LIMIT :limit")
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

@Dao interface LikedVideoDao {
    /** Newest liked first, of one language. */
    @Query("SELECT * FROM liked_videos WHERE lang = :lang ORDER BY likedAt DESC") fun observe(lang: String): Flow<List<LikedVideoEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: LikedVideoEntity)
    @Query("DELETE FROM liked_videos WHERE id = :id") suspend fun delete(id: String)
}

@Dao interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE lang = :lang ORDER BY lastWatchedAt DESC LIMIT :n") fun observe(lang: String, n: Int): Flow<List<WatchHistoryEntity>>
    @Query("SELECT * FROM watch_history WHERE lang = :lang ORDER BY lastWatchedAt DESC LIMIT 1") suspend fun latest(lang: String): WatchHistoryEntity?
    /** Rows from before history was kept per language have no language yet: give them to [lang]. Does nothing once none are left. */
    @Query("UPDATE watch_history SET lang = :lang WHERE lang = ''") suspend fun claimUnassigned(lang: String)
    @Query("SELECT * FROM watch_history WHERE id = :id") suspend fun get(id: String): WatchHistoryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: WatchHistoryEntity)
    /** Only touches a row that still exists, so a video you removed from history stays removed while it plays. */
    @Query("UPDATE watch_history SET positionMs = :pos, durationMs = CASE WHEN :dur > 0 THEN :dur ELSE durationMs END, lastWatchedAt = :now WHERE id = :id")
    suspend fun progress(id: String, pos: Long, dur: Long, now: Long)
    @Query("DELETE FROM watch_history WHERE id = :id") suspend fun delete(id: String)
    /** Keeps the newest [keep] rows of one language; other languages are not touched. */
    @Query("DELETE FROM watch_history WHERE lang = :lang AND id NOT IN (SELECT id FROM watch_history WHERE lang = :lang ORDER BY lastWatchedAt DESC LIMIT :keep)") suspend fun trim(lang: String, keep: Int)
}

@Dao interface AnalyzedVideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: AnalyzedVideoEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<AnalyzedVideoEntity>)
    @Query("SELECT * FROM analyzed_videos WHERE url = :url") suspend fun get(url: String): AnalyzedVideoEntity?
    /** Rows already measured for these links, so a video is never read twice. SQLite allows about 999 values, call it with batches. */
    @Query("SELECT * FROM analyzed_videos WHERE url IN (:urls) AND unavailable = 0") suspend fun byUrls(urls: List<String>): List<AnalyzedVideoEntity>
    /** Candidates for one language and clip length, not marked unavailable. Recommending filters watch history and ranks in memory. */
    @Query("SELECT * FROM analyzed_videos WHERE lang = :lang AND unavailable = 0 AND noSubtitles = 0 AND durationMs BETWEEN :minMs AND :maxMs ORDER BY analyzedAt DESC LIMIT :limit")
    suspend fun candidates(lang: String, minMs: Long, maxMs: Long, limit: Int): List<AnalyzedVideoEntity>
    @Query("SELECT COUNT(*) FROM analyzed_videos WHERE lang = :lang AND unavailable = 0 AND noSubtitles = 0") suspend fun count(lang: String): Int
    @Query("UPDATE analyzed_videos SET unavailable = 1 WHERE url = :url") suspend fun markUnavailable(url: String)
    /**
     * Library pool for the clips list: one language, the given video levels, clip length, not marked unavailable or without subtitles, not shown
     * since `shownBefore` (pass Long.MAX_VALUE to allow every row). A random sample, so the same rows do not win every time. Ranking happens in memory.
     */
    @Query("SELECT * FROM analyzed_videos WHERE lang = :lang AND unavailable = 0 AND noSubtitles = 0 AND levelCode IN (:levels) AND durationMs BETWEEN :minMs AND :maxMs AND shownAt < :shownBefore ORDER BY RANDOM() LIMIT :limit")
    suspend fun pool(lang: String, levels: List<String>, minMs: Long, maxMs: Long, shownBefore: Long, limit: Int): List<AnalyzedVideoEntity>
    /** Which of these links already have a row of any kind (measured, no subtitles, unavailable). Used before queueing. Call with batches under 999. */
    @Query("SELECT url FROM analyzed_videos WHERE url IN (:urls)") suspend fun knownUrls(urls: List<String>): List<String>
    @Query("UPDATE analyzed_videos SET shownAt = :at WHERE url IN (:urls)") suspend fun markShown(urls: List<String>, at: Long)
    /** Every row, any language and kind. */
    @Query("SELECT COUNT(*) FROM analyzed_videos") suspend fun countAll(): Int
    /** Clips that can be recommended (any language): what the library cap counts. Rows without subtitles or marked unavailable are only markers. */
    @Query("SELECT COUNT(*) FROM analyzed_videos WHERE unavailable = 0 AND noSubtitles = 0") suspend fun countUsable(): Int
    /** The Settings line: usable clips of one language, live. */
    @Query("SELECT COUNT(*) FROM analyzed_videos WHERE lang = :lang AND unavailable = 0 AND noSubtitles = 0") fun observeCount(lang: String): Flow<Int>
    /** Pruning, first choice: usable clips of this language whose level is outside `keepLevels` (they are never recommended), never-shown and oldest first. */
    @Query("DELETE FROM analyzed_videos WHERE url IN (SELECT url FROM analyzed_videos WHERE lang = :lang AND unavailable = 0 AND noSubtitles = 0 AND (levelCode IS NULL OR levelCode NOT IN (:keepLevels)) ORDER BY shownAt ASC, analyzedAt ASC LIMIT :limit)")
    suspend fun deleteOutsideLevels(lang: String, keepLevels: List<String>, limit: Int)
    /** Pruning, second choice: usable clips of any language, never-shown first (shownAt 0), then least recently shown, then oldest. */
    @Query("DELETE FROM analyzed_videos WHERE url IN (SELECT url FROM analyzed_videos WHERE unavailable = 0 AND noSubtitles = 0 ORDER BY shownAt ASC, analyzedAt ASC LIMIT :limit)")
    suspend fun deleteOldest(limit: Int)
    /** Keeps only the newest `keep` marker rows (no subtitles, or unavailable). */
    @Query("DELETE FROM analyzed_videos WHERE (unavailable = 1 OR noSubtitles = 1) AND url NOT IN (SELECT url FROM analyzed_videos WHERE unavailable = 1 OR noSubtitles = 1 ORDER BY analyzedAt DESC LIMIT :keep)")
    suspend fun trimMarkers(keep: Int)
}

/** Phase 2: the queue read by the background library job. */
@Dao interface IndexQueueDao {
    /** Adds links; one already queued is left as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addAll(list: List<IndexQueueEntity>)
    /** Next links to read: highest priority first, oldest first, skipping ones that failed `maxAttempts` times. */
    @Query("SELECT * FROM index_queue WHERE lang = :lang AND attempts < :maxAttempts ORDER BY priority DESC, addedAt ASC LIMIT :limit")
    suspend fun next(lang: String, maxAttempts: Int, limit: Int): List<IndexQueueEntity>
    @Query("DELETE FROM index_queue WHERE url = :url") suspend fun remove(url: String)
    @Query("UPDATE index_queue SET attempts = attempts + 1, lastError = :error WHERE url = :url") suspend fun fail(url: String, error: String?)
    /** Drops links that failed too often. */
    @Query("DELETE FROM index_queue WHERE attempts >= :maxAttempts") suspend fun dropFailed(maxAttempts: Int)
    @Query("SELECT COUNT(*) FROM index_queue WHERE lang = :lang AND attempts < :maxAttempts") suspend fun pending(lang: String, maxAttempts: Int): Int
    /** Keeps the best `keep` rows of a language (priority, then newest) so the queue cannot grow without limit. */
    @Query("DELETE FROM index_queue WHERE lang = :lang AND url NOT IN (SELECT url FROM index_queue WHERE lang = :lang ORDER BY priority DESC, addedAt DESC LIMIT :keep)")
    suspend fun trim(lang: String, keep: Int)
}

/** A topic with the numbers the topic list shows. */
data class SpeakingTopicRow(
    @Embedded val topic: SpeakingTopicEntity,
    val versionCount: Int,
    val lastAt: Long?
)

/** Phase 5: speaking topics and their recording versions. Soft delete, like the other tables. */
@Dao interface SpeakingDao {
    @Query("SELECT t.*, (SELECT COUNT(*) FROM speaking_versions v WHERE v.topicId = t.id AND v.deleted = 0) AS versionCount, (SELECT MAX(v.createdAt) FROM speaking_versions v WHERE v.topicId = t.id AND v.deleted = 0) AS lastAt FROM speaking_topics t WHERE t.languageCode = :lang AND t.deleted = 0 ORDER BY COALESCE(lastAt, t.createdAt) DESC")
    fun observeTopics(lang: String): Flow<List<SpeakingTopicRow>>
    @Query("SELECT * FROM speaking_topics WHERE id = :id") suspend fun topic(id: String): SpeakingTopicEntity?
    @Query("SELECT * FROM speaking_versions WHERE topicId = :topicId AND deleted = 0 ORDER BY versionNo")
    fun observeVersions(topicId: String): Flow<List<SpeakingVersionEntity>>
    @Query("SELECT v.improved AS text, t.languageCode AS lang FROM speaking_versions v JOIN speaking_topics t ON t.id = v.topicId WHERE t.languageCode = :lang AND v.deleted = 0 AND t.deleted = 0 AND v.improved IS NOT NULL AND v.improved != '' ORDER BY v.updatedAt DESC LIMIT :limit")
    suspend fun improvedTexts(lang: String, limit: Int): List<ImprovedText>
    @Query("SELECT * FROM speaking_versions WHERE id = :id") suspend fun version(id: String): SpeakingVersionEntity?
    /** Next version number. Counts deleted rows too, so a number is never reused. */
    @Query("SELECT COALESCE(MAX(versionNo), 0) + 1 FROM speaking_versions WHERE topicId = :topicId") suspend fun nextVersionNo(topicId: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTopic(t: SpeakingTopicEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertVersion(v: SpeakingVersionEntity)
    @Update suspend fun updateVersion(v: SpeakingVersionEntity)
    @Query("UPDATE speaking_versions SET audioPath = NULL, updatedAt = :now WHERE id = :id") suspend fun clearAudioPath(id: String, now: Long)
    @Query("UPDATE speaking_versions SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun deleteVersion(id: String, now: Long)
    @Query("UPDATE speaking_versions SET deleted = 1, updatedAt = :now WHERE topicId = :topicId") suspend fun deleteVersionsOfTopic(topicId: String, now: Long)
    @Query("UPDATE speaking_topics SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun deleteTopicRow(id: String, now: Long)
    /** Deletes a topic and its versions together. */
    @Transaction suspend fun deleteTopic(id: String, now: Long) { deleteVersionsOfTopic(id, now); deleteTopicRow(id, now) }
}

@Dao interface LibraryBookDao {
    /** The shelf of one language, newest heart first. */
    @Query("SELECT * FROM library_books WHERE shelved = 1 AND lang = :lang ORDER BY shelvedAt DESC") fun observeShelf(lang: String): Flow<List<LibraryBookEntity>>
    /** Books downloaded for offline, newest first. */
    @Query("SELECT * FROM library_books WHERE sourceId IS NOT NULL AND lang = :lang ORDER BY downloadedAt DESC") fun observeDownloaded(lang: String): Flow<List<LibraryBookEntity>>
    /** Ids on the shelf, to fill the heart on search results. */
    @Query("SELECT id FROM library_books WHERE shelved = 1 AND lang = :lang") fun observeShelfIds(lang: String): Flow<List<String>>
    @Query("SELECT * FROM library_books WHERE id = :id") fun observe(id: String): Flow<LibraryBookEntity?>
    @Query("SELECT * FROM library_books WHERE id = :id") suspend fun get(id: String): LibraryBookEntity?
    @Query("SELECT * FROM library_books WHERE sourceId = :sourceId LIMIT 1") suspend fun getBySource(sourceId: String): LibraryBookEntity?
    @Query("UPDATE library_books SET coverPath = :path WHERE id = :id") suspend fun setCoverPath(id: String, path: String)
    @Query("DELETE FROM library_books WHERE id = :id") suspend fun deleteById(id: String)
    /** Adds the row only when it is new, so a repeat never wipes the shelf flag or the download link. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(e: LibraryBookEntity): Long
    @Query("UPDATE library_books SET shelved = :on, shelvedAt = :now WHERE id = :id") suspend fun setShelved(id: String, on: Boolean, now: Long)
    /** Keeps an existing cover when `coverPath` is null. */
    @Query("UPDATE library_books SET sourceId = :sourceId, coverPath = COALESCE(:coverPath, coverPath), downloadedAt = :now WHERE id = :id")
    suspend fun setDownloaded(id: String, sourceId: String, coverPath: String?, now: Long)
    /** Drops rows that are neither on the shelf nor downloaded. A downloaded book is never removed here. */
    @Query("DELETE FROM library_books WHERE shelved = 0 AND sourceId IS NULL") suspend fun pruneIdle()
    /** The user removed the offline copy: forget the reader link, keep the row (and its heart) until pruned. */
    @Query("UPDATE library_books SET sourceId = NULL, downloadedAt = NULL WHERE id = :id") suspend fun clearDownloaded(id: String)
    /** The latest-read chapter of every downloaded book that has been opened. LibraryMath.continueList picks one per book. */
    @Query("SELECT lb.id AS bookId, a.id AS articleId, p.percent AS percent, p.updatedAt AS updatedAt, " +
        "(SELECT COUNT(*) FROM articles c WHERE c.sourceId = a.sourceId AND c.deleted = 0) AS chapters, " +
        "(SELECT COUNT(*) FROM articles c WHERE c.sourceId = a.sourceId AND c.deleted = 0 AND c.ordinal < a.ordinal) AS chaptersBefore " +
        "FROM library_books lb JOIN articles a ON a.sourceId = lb.sourceId JOIN reading_progress p ON p.articleId = a.id " +
        "WHERE lb.lang = :lang AND a.deleted = 0 AND p.updatedAt = (SELECT MAX(p2.updatedAt) FROM reading_progress p2 " +
        "JOIN articles a2 ON a2.id = p2.articleId WHERE a2.sourceId = lb.sourceId AND a2.deleted = 0)")
    fun observeProgress(lang: String): Flow<List<app.fluvour.reading.BookProgressRow>>
}

/** An improved version of your Output with its language, for the voices made ahead. */
data class ImprovedText(val text: String, val lang: String)
