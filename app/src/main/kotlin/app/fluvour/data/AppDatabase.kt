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
        ReviewLogEntity::class, SpeakingTopicEntity::class, SpeakingVersionEntity::class, ArticleEntity::class, LookupCacheEntity::class,
        DictionaryEntity::class, DictTermEntity::class, DictMetaEntity::class,
        ReadingProgressEntity::class, BookmarkEntity::class, HighlightEntity::class, WatchHistoryEntity::class],
    version = 7, exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decks(): DeckDao
    abstract fun cards(): CardDao
    abstract fun reviews(): ReviewLogDao
    abstract fun articles(): ArticleDao
    abstract fun sources(): SourceDao
    abstract fun sentences(): SentenceDao
    abstract fun lookupCache(): LookupCacheDao
    abstract fun dictionaries(): DictDao
    abstract fun reader(): ReaderDao
    abstract fun watchHistory(): WatchHistoryDao
}

@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "fluvour.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
    @Provides fun decks(db: AppDatabase) = db.decks()
    @Provides fun cards(db: AppDatabase) = db.cards()
    @Provides fun reviews(db: AppDatabase) = db.reviews()
    @Provides fun articles(db: AppDatabase) = db.articles()
    @Provides fun sources(db: AppDatabase) = db.sources()
    @Provides fun sentences(db: AppDatabase) = db.sentences()
    @Provides fun lookupCache(db: AppDatabase) = db.lookupCache()
    @Provides fun dictionaries(db: AppDatabase) = db.dictionaries()
    @Provides fun reader(db: AppDatabase) = db.reader()
    @Provides fun watchHistory(db: AppDatabase) = db.watchHistory()
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
