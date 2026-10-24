package app.fluvour.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.fluvour.srs.CardState
import app.fluvour.srs.Phase

// Postgres-style schema (see backend/schema.sql): device-generated UUID ids as strings,
// a userId owner column on every table, soft delete, and text-only storage.

@Entity(tableName = "decks", indices = [Index("languageCode")])
data class DeckEntity(
    @PrimaryKey val id: String, val userId: String, val languageCode: String, val name: String,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
)

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String, val userId: String,
    val kind: String,            // article | video | comic | speaking | manual
    val title: String, val url: String? = null, val meta: String? = null,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
)

@Entity(tableName = "sentences", indices = [Index("languageCode"), Index("sourceId")])
data class SentenceEntity(
    @PrimaryKey val id: String, val userId: String, val languageCode: String, val text: String,
    val translation: String? = null, val sourceId: String? = null, val position: String? = null,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
)

@Entity(tableName = "cards", indices = [Index("deckId"), Index("languageCode"), Index("dueAt")])
data class CardEntity(
    @PrimaryKey val id: String, val userId: String, val deckId: String, val languageCode: String,
    val word: String, val meaning: String, val example: String? = null, val notes: String? = null,
    val imagePrompt: String? = null, val imageUrl: String? = null,   // prompt/URL only, never image bytes
    val sentenceId: String? = null,
    // spaced repetition state (mirrors app.fluvour.srs.CardState)
    val phase: String = Phase.NEW.name, val stability: Double = 0.0, val difficulty: Double = 0.0,
    val step: Int = 0, val lastReviewAt: Long? = null, val dueAt: Long? = null, val scheduledDays: Int = 0,
    val reps: Int = 0, val lapses: Int = 0,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
) {
    fun srsState() = CardState(Phase.valueOf(phase), stability, difficulty, step, lastReviewAt, dueAt, scheduledDays, reps, lapses)
    fun withState(s: CardState, now: Long) = copy(
        phase = s.phase.name, stability = s.stability, difficulty = s.difficulty, step = s.step,
        lastReviewAt = s.lastReviewMillis, dueAt = s.dueMillis, scheduledDays = s.scheduledDays,
        reps = s.reps, lapses = s.lapses, updatedAt = now
    )
}

@Entity(tableName = "review_log", indices = [Index("cardId"), Index("reviewedAt")])
data class ReviewLogEntity(
    @PrimaryKey val id: String, val userId: String, val cardId: String, val rating: Int,
    val reviewedAt: Long, val elapsedDays: Double, val stabilityAfter: Double, val difficultyAfter: Double
)

@Entity(tableName = "speaking_topics", indices = [Index("languageCode")])
data class SpeakingTopicEntity(
    @PrimaryKey val id: String, val userId: String, val languageCode: String, val title: String,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
)

/** One recording attempt ("version") of a topic: what was said, the improved text, new words. */
@Entity(tableName = "speaking_versions", indices = [Index("topicId")])
data class SpeakingVersionEntity(
    @PrimaryKey val id: String, val userId: String, val topicId: String, val versionNo: Int,
    val transcript: String, val improved: String? = null, val newWords: String? = null, // JSON array as text
    val audioPath: String? = null,  // device-local only; never synced
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
)

/** A saved, sourced article (Phase 2A), or one chapter of an imported book (step 8). Text only; the full body is re-fetchable from `url`. Local to the device for now. */
@Entity(tableName = "articles", indices = [Index("languageCode"), Index("sourceId")])
data class ArticleEntity(
    @PrimaryKey val id: String, val userId: String, val sourceId: String, val languageCode: String,
    val title: String, val body: String, val url: String, val license: String,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false,
    /** Chapter number inside its source (0 for a single article). Imported books are saved as one row per chapter, all sharing one source. */
    @androidx.room.ColumnInfo(defaultValue = "0") val ordinal: Int = 0
)

/** Cached word lookups for the reader popup (Phase 2A). `kind` is dict or ai. Local only, never synced; can be rebuilt from the network. */
@Entity(tableName = "lookup_cache")
data class LookupCacheEntity(
    @PrimaryKey val key: String, val userId: String, val kind: String, val languageCode: String,
    val word: String, val text: String, val createdAt: Long
)

/** A mined sentence together with the title of its source, for lists. Not a table. */
data class SentenceWithSource(
    @androidx.room.Embedded val sentence: SentenceEntity,
    val sourceTitle: String?
)

/** Phase 2A step 9: an imported Yomitan dictionary. `priority` orders dictionaries in the popup (import order). Local only, never synced. */
@Entity(tableName = "dictionaries", indices = [Index("languageCode")])
data class DictionaryEntity(
    @PrimaryKey val id: String, val languageCode: String, val title: String, val revision: String, val author: String?,
    val attribution: String?, val terms: Int, val metaCount: Int, val enabled: Boolean, val priority: Int, val importedAt: Long
)

/** One word entry of an imported dictionary. `glossary` is plain text, one meaning per line. */
@Entity(tableName = "dict_terms", indices = [Index("term"), Index("dictId")])
data class DictTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val dictId: String, val term: String, val reading: String,
    val tags: String, val score: Int, val glossary: String, val sequence: Int
)

/** Frequency, pitch accent or IPA for a word of an imported dictionary (`kind` = freq, pitch, ipa). */
@Entity(tableName = "dict_meta", indices = [Index("term"), Index("dictId")])
data class DictMetaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val dictId: String, val term: String, val kind: String,
    val reading: String, val value: String, val num: Double?
)

data class TermHit(@androidx.room.Embedded val t: DictTermEntity, val dictTitle: String)
data class MetaHit(@androidx.room.Embedded val m: DictMetaEntity, val dictTitle: String)

/** Where the reader left off in one text: paragraph number and character inside it, plus how far through the text that is (0..1). One row per article. */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val articleId: String, val userId: String,
    val para: Int, val ch: Int, val percent: Float, val updatedAt: Long
)

/** A saved place in a text. `snippet` is the first words at that place, for the bookmarks list. */
@Entity(tableName = "bookmarks", indices = [Index("articleId")])
data class BookmarkEntity(
    @PrimaryKey val id: String, val userId: String, val articleId: String,
    val para: Int, val ch: Int, val snippet: String, val percent: Float,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
)

/** A coloured highlight inside one paragraph (`startCh` inclusive, `endCh` exclusive). `color` is an ARGB int; `note` is optional. */
@Entity(tableName = "highlights", indices = [Index("articleId")])
data class HighlightEntity(
    @PrimaryKey val id: String, val userId: String, val articleId: String,
    val para: Int, val startCh: Int, val endCh: Int, val color: Int, val text: String, val note: String?,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false
)

/** Watch history: one row per video link, with where you stopped. Links and details only, never the video itself. Local only, never synced. */
@Entity(tableName = "watch_history", indices = [Index("lastWatchedAt")])
data class WatchHistoryEntity(
    @PrimaryKey val id: String, val url: String, val title: String, val uploader: String? = null,
    val thumbUrl: String? = null, val durationMs: Long = 0L, val positionMs: Long = 0L, val lastWatchedAt: Long
)
