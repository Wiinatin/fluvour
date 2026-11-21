package app.fluvour.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The measured level of one article from the Articles tab, keyed by its web address. Local only, never synced:
 * the synced `sources` rows stay as they are. A row can exist for an article that was opened and later deleted from Saved.
 * `levelCode` is a code on the language's scale (see ClipLevels), or null when the text was too short to measure.
 */
@Entity(tableName = "article_levels", indices = [Index("lang", "levelCode")])
data class ArticleLevelEntity(
    @PrimaryKey val url: String, val lang: String, val source: String, val title: String,
    val levelCode: String?, val tokenTotal: Int, val measuredAt: Long
)

@Dao interface ArticleLevelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ArticleLevelEntity)
    @Query("SELECT * FROM article_levels WHERE url = :url") suspend fun get(url: String): ArticleLevelEntity?
    /** Pass at most a few hundred urls at a time. */
    @Query("SELECT * FROM article_levels WHERE url IN (:urls)") suspend fun byUrls(urls: List<String>): List<ArticleLevelEntity>
    @Query("DELETE FROM article_levels WHERE url = :url") suspend fun delete(url: String)
}
