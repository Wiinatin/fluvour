package app.fluvour.data

import androidx.room.ColumnInfo
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
    // Legacy: the state this card had before facets (learning path). The source of truth is now the WORD facet in card_facets;
    // queries lay it over these fields (CARD_WITH_WORD), so a CardEntity read from the DAO carries the facet's state. Reviews no longer write here.
    val phase: String = Phase.NEW.name, val stability: Double = 0.0, val difficulty: Double = 0.0,
    val step: Int = 0, val lastReviewAt: Long? = null, val dueAt: Long? = null, val scheduledDays: Int = 0,
    val reps: Int = 0, val lapses: Int = 0,
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false,
    // Arabic/Malay (v17), all optional; old cards keep null. variantWord/variantExample = the Sarawak form and sentence (Malay only); root/lemma = Arabic only.
    val variantWord: String? = null, val variantExample: String? = null, val root: String? = null, val lemma: String? = null,
    // v18, optional: what the Sarawak example sentence means (Malay only)
    val variantExampleMeaning: String? = null
) {
    fun srsState() = CardState(Phase.valueOf(phase), stability, difficulty, step, lastReviewAt, dueAt, scheduledDays, reps, lapses)
    fun withState(s: CardState, now: Long) = copy(
        phase = s.phase.name, stability = s.stability, difficulty = s.difficulty, step = s.step,
        lastReviewAt = s.lastReviewMillis, dueAt = s.dueMillis, scheduledDays = s.scheduledDays,
        reps = s.reps, lapses = s.lapses, updatedAt = now
    )
}

@Entity(tableName = "review_log", indices = [Index("cardId"), Index("reviewedAt"), Index("facetId")])
data class ReviewLogEntity(
    @PrimaryKey val id: String, val userId: String, val cardId: String, val rating: Int,
    val reviewedAt: Long, val elapsedDays: Double, val stabilityAfter: Double, val difficultyAfter: Double,
    val facetId: String? = null   // which facet was reviewed; old rows are backfilled to the card's WORD facet
)

/**
 * One way of studying a card, with its own memory state (learning path step 2). [kind] is a [app.fluvour.srs.FacetKind] name.
 * [unlockedAt] null means locked: the facet exists but is not studied yet. Id = facetId(cardId, kind), so devices never duplicate it.
 */
@Entity(tableName = "card_facets", indices = [Index("cardId", "kind", unique = true), Index("languageCode", "kind", "dueAt")])
data class CardFacetEntity(
    @PrimaryKey val id: String, val userId: String, val cardId: String, val languageCode: String, val kind: String,
    val phase: String = Phase.NEW.name, val stability: Double = 0.0, val difficulty: Double = 0.0,
    val step: Int = 0, val lastReviewAt: Long? = null, val dueAt: Long? = null, val scheduledDays: Int = 0,
    val reps: Int = 0, val lapses: Int = 0,
    val unlockedAt: Long? = null, val updatedAt: Long, val deleted: Boolean = false
) {
    fun srsState() = CardState(Phase.valueOf(phase), stability, difficulty, step, lastReviewAt, dueAt, scheduledDays, reps, lapses)
    fun withState(s: CardState, now: Long) = copy(
        phase = s.phase.name, stability = s.stability, difficulty = s.difficulty, step = s.step,
        lastReviewAt = s.lastReviewMillis, dueAt = s.dueMillis, scheduledDays = s.scheduledDays,
        reps = s.reps, lapses = s.lapses, updatedAt = now
    )
    val unlocked get() = unlockedAt != null
}

/** The facets a card starts with. WORD inherits the card's saved state; MEANING starts locked; CLIP (cards from a video) starts open. */
object FacetSeed {
    fun word(card: CardEntity, now: Long) = CardFacetEntity(
        id = app.fluvour.srs.facetId(card.id, app.fluvour.srs.FacetKind.WORD), userId = card.userId, cardId = card.id,
        languageCode = card.languageCode, kind = app.fluvour.srs.FacetKind.WORD.name,
        phase = card.phase, stability = card.stability, difficulty = card.difficulty, step = card.step,
        lastReviewAt = card.lastReviewAt, dueAt = card.dueAt, scheduledDays = card.scheduledDays, reps = card.reps, lapses = card.lapses,
        unlockedAt = card.createdAt, updatedAt = now, deleted = card.deleted
    )
    fun meaning(card: CardEntity, now: Long) = CardFacetEntity(
        id = app.fluvour.srs.facetId(card.id, app.fluvour.srs.FacetKind.MEANING), userId = card.userId, cardId = card.id,
        languageCode = card.languageCode, kind = app.fluvour.srs.FacetKind.MEANING.name, unlockedAt = null, updatedAt = now, deleted = card.deleted
    )
    /** Sarawak recognition facet: open from creation (not locked), so both Malay forms are learned together. */
    fun sarawak(card: CardEntity, now: Long) = CardFacetEntity(
        id = app.fluvour.srs.facetId(card.id, app.fluvour.srs.FacetKind.SARAWAK), userId = card.userId, cardId = card.id,
        languageCode = card.languageCode, kind = app.fluvour.srs.FacetKind.SARAWAK.name, unlockedAt = card.createdAt, updatedAt = now, deleted = card.deleted
    )
    fun clip(card: CardEntity, now: Long) = CardFacetEntity(
        id = app.fluvour.srs.facetId(card.id, app.fluvour.srs.FacetKind.CLIP), userId = card.userId, cardId = card.id,
        languageCode = card.languageCode, kind = app.fluvour.srs.FacetKind.CLIP.name, unlockedAt = now, updatedAt = now, deleted = card.deleted
    )
}

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
    val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false,
    /** Phase 5: the words of `transcript` that were not in the target language, as a JSON array of text. */
    val substituted: String? = null,
    /** Phase 5: length of the recording in seconds (0 when unknown). */
    @androidx.room.ColumnInfo(defaultValue = "0") val durationSec: Int = 0,
    /** Phase 5: optional JSON of the countable measures (distinct words and so on). */
    val metrics: String? = null
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
    val sourceTitle: String?,
    val sourceKind: String? = null,
    val sourceUrl: String? = null
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

/** Watch history: one row per video link, with where you stopped. Links and details only, never the video itself. Local only, never synced. [lang] is the language you were learning when you watched it, so every language has its own Recent list (empty only on rows from before that, which are given to the first language the app sees). */
@Entity(tableName = "watch_history", indices = [Index("lastWatchedAt")])
data class WatchHistoryEntity(
    @PrimaryKey val id: String, val url: String, val title: String, val uploader: String? = null,
    val thumbUrl: String? = null, val durationMs: Long = 0L, val positionMs: Long = 0L, val lastWatchedAt: Long,
    @ColumnInfo(defaultValue = "''") val lang: String = ""
)

/** Liked videos: the heart in the player. Kept apart from watch history, so clearing Recent never removes a liked video. Links and details only. Local only, never synced. [lang] is the language being learned when it was liked, so every language has its own list. */
@Entity(tableName = "liked_videos", indices = [Index("lang", "likedAt")])
data class LikedVideoEntity(
    @PrimaryKey val id: String, val url: String, val title: String, val uploader: String?,
    val thumbUrl: String?, val durationMs: Long, val likedAt: Long, val lang: String
)

/**
 * Clip recommendations (roadmap 4.2): one row per YouTube video whose subtitles were read and measured. Links, details and word counts only,
 * never the video or its subtitle text. Local only, never synced. Phase 2 (background library) builds on this table, so keep the fields simple and stable.
 * `wordCounts` is a JSON object of word to count (see [WordCountsJson]); it holds every counted word so the percentages can be recomputed
 * when the user learns words or changes level, without reading the subtitles again. `levelCode` is the video's level on the language's scale
 * (see ClipLevels) and does not depend on the user. `subtitleKind` is "manual" or "auto". `unavailable` is set when the video fails to open.
 */
@Entity(tableName = "analyzed_videos", indices = [Index("lang"), Index("analyzedAt"), Index("lang", "levelCode")])
data class AnalyzedVideoEntity(
    @PrimaryKey val url: String, val lang: String, val title: String, val uploader: String? = null,
    val durationMs: Long = 0L, val thumbUrl: String? = null, val analyzedAt: Long, val subtitleKind: String,
    val tokenTotal: Int, val uniqueCount: Int, val levelCode: String? = null, val wordCounts: String,
    val unavailable: Boolean = false,
    /** Phase 2: the video opened but has no readable subtitles. Kept as a row (tokenTotal 0, wordCounts "{}", subtitleKind "none") so it is never read again, and never recommended. */
    @ColumnInfo(defaultValue = "0") val noSubtitles: Boolean = false,
    /** Phase 2: when the video was last shown in the clips list (0 = never). Pruning removes the oldest never-shown rows first. */
    @ColumnInfo(defaultValue = "0") val shownAt: Long = 0L
)

/**
 * Phase 2: videos waiting to be read by the background library job. One row per video link. `source` is "related" (found next to a
 * well-fitting clip), "search" (left over from a clips search) or "manual". Higher `priority` is read first. A row is deleted once the
 * video is measured; a failure adds to `attempts` and keeps the message in `lastError`.
 */
@Entity(tableName = "index_queue", indices = [Index("lang", "priority", "addedAt")])
data class IndexQueueEntity(
    @PrimaryKey val url: String, val lang: String, val source: String, val priority: Int = 0,
    val addedAt: Long, val attempts: Int = 0, val lastError: String? = null
)

/** The compact form of `AnalyzedVideoEntity.wordCounts`: `{"word":count,...}`. */
object WordCountsJson {
    fun encode(counts: Map<String, Int>): String {
        val o = org.json.JSONObject()
        for ((w, n) in counts) o.put(w, n)
        return o.toString()
    }
    fun decode(json: String): Map<String, Int> = try {
        val o = org.json.JSONObject(json)
        val out = HashMap<String, Int>(o.length() * 2)
        for (k in o.keys()) out[k] = o.optInt(k, 0)
        out
    } catch (e: Exception) { emptyMap() }
}

/**
 * A book in the Read library: on the shelf (heart), downloaded for offline, or both. Only books Win hearted or downloaded are stored; search results are not.
 * `id` is "gutenberg:<n>" or "file:<sourceId>". `sourceId` links to the reader source once the EPUB is imported (null = not downloaded).
 * `coverPath` is a file under filesDir/covers. Local only, never synced: the reader's own `sources` rows are what sync.
 */
@Entity(tableName = "library_books", indices = [Index("lang", "shelvedAt"), Index("sourceId")])
data class LibraryBookEntity(
    @PrimaryKey val id: String, val origin: String, val lang: String,
    val title: String, val author: String,
    val coverUrl: String?, val coverPath: String?, val epubUrl: String?,
    val tags: String?, val about: String?, val sourceId: String?,
    val shelved: Boolean, val shelvedAt: Long, val downloadedAt: Long?, val addedAt: Long
)

/**
 * Learning path step 6: progress on one curriculum unit. A row exists once the unit has a result; [graduatedAt] null means not graduated yet.
 * Unit ids are unique across paths (g1-01 ...), so [unitId] is the key. Local only for now (not synced).
 */
@Entity(tableName = "path_units", indices = [Index("languageCode")])
data class PathUnitEntity(
    @PrimaryKey val unitId: String, val languageCode: String,
    val graduatedAt: Long? = null, val bestScore: Int = 0, val updatedAt: Long
)
