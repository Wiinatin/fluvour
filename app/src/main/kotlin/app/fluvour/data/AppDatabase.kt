package app.fluvour.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [DeckEntity::class, SourceEntity::class, SentenceEntity::class, CardEntity::class,
        ReviewLogEntity::class, CardFacetEntity::class, SpeakingTopicEntity::class, SpeakingVersionEntity::class, ArticleEntity::class, LookupCacheEntity::class,
        DictionaryEntity::class, DictTermEntity::class, DictMetaEntity::class,
        ReadingProgressEntity::class, BookmarkEntity::class, HighlightEntity::class, WatchHistoryEntity::class, AnalyzedVideoEntity::class, IndexQueueEntity::class, LikedVideoEntity::class, LibraryBookEntity::class, ArticleLevelEntity::class, PathUnitEntity::class],
    version = 18, exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decks(): DeckDao
    abstract fun cards(): CardDao
    abstract fun reviews(): ReviewLogDao
    abstract fun facets(): CardFacetDao
    abstract fun pathUnits(): PathUnitDao
    abstract fun articles(): ArticleDao
    abstract fun sources(): SourceDao
    abstract fun sentences(): SentenceDao
    abstract fun lookupCache(): LookupCacheDao
    abstract fun dictionaries(): DictDao
    abstract fun reader(): ReaderDao
    abstract fun watchHistory(): WatchHistoryDao
    abstract fun analyzedVideos(): AnalyzedVideoDao
    abstract fun indexQueue(): IndexQueueDao
    abstract fun likedVideos(): LikedVideoDao
    abstract fun libraryBooks(): LibraryBookDao
    abstract fun articleLevels(): ArticleLevelDao
    abstract fun speaking(): SpeakingDao
}

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "fluvour.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18).build()
    @Provides fun decks(db: AppDatabase) = db.decks()
    @Provides fun cards(db: AppDatabase) = db.cards()
    @Provides fun reviews(db: AppDatabase) = db.reviews()
    @Provides fun facets(db: AppDatabase) = db.facets()
    @Provides fun pathUnits(db: AppDatabase) = db.pathUnits()
    @Provides fun articles(db: AppDatabase) = db.articles()
    @Provides fun sources(db: AppDatabase) = db.sources()
    @Provides fun sentences(db: AppDatabase) = db.sentences()
    @Provides fun lookupCache(db: AppDatabase) = db.lookupCache()
    @Provides fun dictionaries(db: AppDatabase) = db.dictionaries()
    @Provides fun reader(db: AppDatabase) = db.reader()
    @Provides fun watchHistory(db: AppDatabase) = db.watchHistory()
    @Provides fun analyzedVideos(db: AppDatabase) = db.analyzedVideos()
    @Provides fun indexQueue(db: AppDatabase) = db.indexQueue()
    @Provides fun likedVideos(db: AppDatabase) = db.likedVideos()
    @Provides fun libraryBooks(db: AppDatabase) = db.libraryBooks()
    @Provides fun articleLevels(db: AppDatabase) = db.articleLevels()
    @Provides fun speaking(db: AppDatabase) = db.speaking()
    @Provides @Singleton fun scheduler(): app.fluvour.srs.Scheduler = app.fluvour.srs.Scheduler()
}

/** Phase 2A: adds the articles table. Existing data is untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `articles` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `languageCode` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `url` TEXT NOT NULL, `license` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_languageCode` ON `articles` (`languageCode`)")
    }
}

/** Phase 2A step 2: adds the word-lookup cache. Existing data is untouched. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `lookup_cache` (`key` TEXT NOT NULL, `userId` TEXT NOT NULL, `kind` TEXT NOT NULL, `languageCode` TEXT NOT NULL, `word` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
    }
}

/** Phase 2A step 8: chapters of imported books. Adds a chapter number to articles (existing rows get 0) and an index on the source. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `articles` ADD COLUMN `ordinal` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_sourceId` ON `articles` (`sourceId`)")
    }
}

/** Phase 2A step 9: imported Yomitan dictionaries. Three new tables; existing data is untouched. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `dictionaries` (`id` TEXT NOT NULL, `languageCode` TEXT NOT NULL, `title` TEXT NOT NULL, `revision` TEXT NOT NULL, `author` TEXT, `attribution` TEXT, `terms` INTEGER NOT NULL, `metaCount` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dictionaries_languageCode` ON `dictionaries` (`languageCode`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `dict_terms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dictId` TEXT NOT NULL, `term` TEXT NOT NULL, `reading` TEXT NOT NULL, `tags` TEXT NOT NULL, `score` INTEGER NOT NULL, `glossary` TEXT NOT NULL, `sequence` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dict_terms_term` ON `dict_terms` (`term`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dict_terms_dictId` ON `dict_terms` (`dictId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `dict_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dictId` TEXT NOT NULL, `term` TEXT NOT NULL, `kind` TEXT NOT NULL, `reading` TEXT NOT NULL, `value` TEXT NOT NULL, `num` REAL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dict_meta_term` ON `dict_meta` (`term`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dict_meta_dictId` ON `dict_meta` (`dictId`)")
    }
}

/** Reader upgrade: saved reading position, bookmarks and highlights. Three new tables; existing data is untouched. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `reading_progress` (`articleId` TEXT NOT NULL, `userId` TEXT NOT NULL, `para` INTEGER NOT NULL, `ch` INTEGER NOT NULL, `percent` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`articleId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `articleId` TEXT NOT NULL, `para` INTEGER NOT NULL, `ch` INTEGER NOT NULL, `snippet` TEXT NOT NULL, `percent` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_articleId` ON `bookmarks` (`articleId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `highlights` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `articleId` TEXT NOT NULL, `para` INTEGER NOT NULL, `startCh` INTEGER NOT NULL, `endCh` INTEGER NOT NULL, `color` INTEGER NOT NULL, `text` TEXT NOT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_articleId` ON `highlights` (`articleId`)")
    }
}

/** Watch history: one new table. Existing data is untouched. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `watch_history` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `uploader` TEXT, `thumbUrl` TEXT, `durationMs` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `lastWatchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_history_lastWatchedAt` ON `watch_history` (`lastWatchedAt`)")
    }
}

/** Clip recommendations (roadmap 4.2): one new table of measured videos. Existing data is untouched. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `analyzed_videos` (`url` TEXT NOT NULL, `lang` TEXT NOT NULL, `title` TEXT NOT NULL, `uploader` TEXT, `durationMs` INTEGER NOT NULL, `thumbUrl` TEXT, `analyzedAt` INTEGER NOT NULL, `subtitleKind` TEXT NOT NULL, `tokenTotal` INTEGER NOT NULL, `uniqueCount` INTEGER NOT NULL, `levelCode` TEXT, `wordCounts` TEXT NOT NULL, `unavailable` INTEGER NOT NULL, PRIMARY KEY(`url`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyzed_videos_lang` ON `analyzed_videos` (`lang`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyzed_videos_analyzedAt` ON `analyzed_videos` (`analyzedAt`)")
    }
}

/** Watch history per language: one new column. Existing rows keep their data and get an empty language, which the app fills in on first start. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `watch_history` ADD COLUMN `lang` TEXT NOT NULL DEFAULT ''")
    }
}

/** Clip library (roadmap 4.2 phase 2): a queue table, two new columns on analyzed_videos and one new index. Existing data is untouched. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `analyzed_videos` ADD COLUMN `noSubtitles` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `analyzed_videos` ADD COLUMN `shownAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyzed_videos_lang_levelCode` ON `analyzed_videos` (`lang`, `levelCode`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `index_queue` (`url` TEXT NOT NULL, `lang` TEXT NOT NULL, `source` TEXT NOT NULL, `priority` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`url`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_index_queue_lang_priority_addedAt` ON `index_queue` (`lang`, `priority`, `addedAt`)")
    }
}

/** Liked videos (the heart in the player): one new table and one index. Existing data is untouched. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `liked_videos` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `uploader` TEXT, `thumbUrl` TEXT, `durationMs` INTEGER NOT NULL, `likedAt` INTEGER NOT NULL, `lang` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_liked_videos_lang_likedAt` ON `liked_videos` (`lang`, `likedAt`)")
    }
}

/** Speaking (Phase 5): three new columns on speaking_versions. Existing rows keep their data (empty marks, 0 seconds, no measures). */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `speaking_versions` ADD COLUMN `substituted` TEXT")
        db.execSQL("ALTER TABLE `speaking_versions` ADD COLUMN `durationSec` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `speaking_versions` ADD COLUMN `metrics` TEXT")
    }
}

/** Read library: adds the library_books table (shelf, downloads, covers). Existing data is untouched. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `library_books` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `lang` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `coverUrl` TEXT, `coverPath` TEXT, `epubUrl` TEXT, `tags` TEXT, `about` TEXT, `sourceId` TEXT, `shelved` INTEGER NOT NULL, `shelvedAt` INTEGER NOT NULL, `downloadedAt` INTEGER, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_books_lang_shelvedAt` ON `library_books` (`lang`, `shelvedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_books_sourceId` ON `library_books` (`sourceId`)")
    }
}

/** Articles tab: adds the article_levels table (measured level of each fetched article, local only). Existing data is untouched. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `article_levels` (`url` TEXT NOT NULL, `lang` TEXT NOT NULL, `source` TEXT NOT NULL, `title` TEXT NOT NULL, `levelCode` TEXT, `tokenTotal` INTEGER NOT NULL, `measuredAt` INTEGER NOT NULL, PRIMARY KEY(`url`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_article_levels_lang_levelCode` ON `article_levels` (`lang`, `levelCode`)")
    }
}

/**
 * Learning path step 2: card_facets plus review_log.facetId. Every card gets a WORD facet that inherits its whole saved state, a locked
 * MEANING facet, and (cards mined from a video) an open CLIP facet. Old review rows are linked to the card's WORD facet. Nothing is
 * removed: the card's own state columns stay as they are. Facet ids come from facetId(), so two devices never make different ones.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `card_facets` (`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `cardId` TEXT NOT NULL, `languageCode` TEXT NOT NULL, `kind` TEXT NOT NULL, `phase` TEXT NOT NULL, `stability` REAL NOT NULL, `difficulty` REAL NOT NULL, `step` INTEGER NOT NULL, `lastReviewAt` INTEGER, `dueAt` INTEGER, `scheduledDays` INTEGER NOT NULL, `reps` INTEGER NOT NULL, `lapses` INTEGER NOT NULL, `unlockedAt` INTEGER, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_facets_cardId_kind` ON `card_facets` (`cardId`, `kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_facets_languageCode_kind_dueAt` ON `card_facets` (`languageCode`, `kind`, `dueAt`)")
        db.execSQL("ALTER TABLE `review_log` ADD COLUMN `facetId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_log_facetId` ON `review_log` (`facetId`)")
        val now = System.currentTimeMillis()
        val insert = "INSERT OR IGNORE INTO card_facets (id, userId, cardId, languageCode, kind, phase, stability, difficulty, step, lastReviewAt, dueAt, scheduledDays, reps, lapses, unlockedAt, updatedAt, deleted) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        class Row(val id: String, val user: String, val lang: String, val phase: String, val st: Double, val diff: Double, val step: Int,
                  val last: Long?, val due: Long?, val days: Int, val reps: Int, val lapses: Int, val created: Long, val deleted: Int)
        val rows = ArrayList<Row>()
        db.query("SELECT id, userId, languageCode, phase, stability, difficulty, step, lastReviewAt, dueAt, scheduledDays, reps, lapses, createdAt, deleted FROM cards").use { c ->
            while (c.moveToNext()) rows.add(Row(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5), c.getInt(6),
                if (c.isNull(7)) null else c.getLong(7), if (c.isNull(8)) null else c.getLong(8), c.getInt(9), c.getInt(10), c.getInt(11), c.getLong(12), c.getInt(13)))
        }
        for (r in rows) {
            val word = app.fluvour.srs.FacetKind.WORD; val meaning = app.fluvour.srs.FacetKind.MEANING
            db.execSQL(insert, arrayOf<Any?>(app.fluvour.srs.facetId(r.id, word), r.user, r.id, r.lang, word.name, r.phase, r.st, r.diff, r.step,
                r.last, r.due, r.days, r.reps, r.lapses, r.created, now, r.deleted))
            db.execSQL(insert, arrayOf<Any?>(app.fluvour.srs.facetId(r.id, meaning), r.user, r.id, r.lang, meaning.name, "NEW", 0.0, 0.0, 0,
                null, null, 0, 0, 0, null, now, r.deleted))
        }
        // cards mined from a video moment get an open CLIP facet
        val clipRows = ArrayList<Row>()
        db.query("SELECT c.id FROM cards c JOIN sentences s ON s.id = c.sentenceId JOIN sources src ON src.id = s.sourceId WHERE src.kind = 'video' AND src.deleted = 0 AND src.url IS NOT NULL AND src.url != '' AND s.position IS NOT NULL").use { c ->
            val ids = HashSet<String>(); while (c.moveToNext()) ids.add(c.getString(0))
            rows.filterTo(clipRows) { it.id in ids }
        }
        for (r in clipRows) {
            val clip = app.fluvour.srs.FacetKind.CLIP
            db.execSQL(insert, arrayOf<Any?>(app.fluvour.srs.facetId(r.id, clip), r.user, r.id, r.lang, clip.name, "NEW", 0.0, 0.0, 0,
                null, null, 0, 0, 0, now, now, r.deleted))
        }
        db.execSQL("UPDATE review_log SET facetId = (SELECT f.id FROM card_facets f WHERE f.cardId = review_log.cardId AND f.kind = 'WORD')")
    }
}

/** Learning path step 6: adds path_units (which curriculum units are graduated). Existing data is untouched. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `path_units` (`unitId` TEXT NOT NULL, `languageCode` TEXT NOT NULL, `graduatedAt` INTEGER, `bestScore` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`unitId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_path_units_languageCode` ON `path_units` (`languageCode`)")
    }
}

/** Arabic + Malay step 2: four optional columns on cards (Sarawak word/sentence, Arabic root/lemma). Old cards keep null; nothing is rewritten. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cards` ADD COLUMN `variantWord` TEXT")
        db.execSQL("ALTER TABLE `cards` ADD COLUMN `variantExample` TEXT")
        db.execSQL("ALTER TABLE `cards` ADD COLUMN `root` TEXT")
        db.execSQL("ALTER TABLE `cards` ADD COLUMN `lemma` TEXT")
    }
}

/** Malay: one optional column on cards, the meaning of the Sarawak example sentence. Old cards keep null; nothing is rewritten. */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cards` ADD COLUMN `variantExampleMeaning` TEXT")
    }
}
