package app.fluvour.reading

import app.fluvour.data.AnalyzedVideoDao
import app.fluvour.data.IndexQueueDao
import app.fluvour.data.IndexQueueEntity
import app.fluvour.media.VideoHit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts candidate videos into the background library queue. Keeps only clips of 30 s to 5 min, drops repeats and videos the library
 * already has (measured, without subtitles, or unavailable), and leaves links already queued alone.
 */
@Singleton
class ClipSeeder @Inject constructor(private val analyzed: AnalyzedVideoDao, private val queue: IndexQueueDao) {

    /** [source] is "related", "search" or "manual". Returns how many links were offered to the queue. */
    suspend fun seed(candidates: List<VideoHit>, lang: String, source: String, priority: Int): Int {
        val cut = ClipPlan.cheapCut(candidates, { it.url }, { it.durationMs }, emptySet(), IndexPlan.SEED_PER_VIDEO)
        if (cut.isEmpty()) return 0
        val have = analyzed.knownUrls(cut.map { it.url }).toHashSet()
        val now = System.currentTimeMillis()
        val rows = cut.filter { it.url !in have }.map { IndexQueueEntity(url = it.url, lang = lang, source = source, priority = priority, addedAt = now) }
        if (rows.isEmpty()) return 0
        queue.addAll(rows)
        return rows.size
    }
}
