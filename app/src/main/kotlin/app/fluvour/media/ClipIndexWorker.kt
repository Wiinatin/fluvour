package app.fluvour.media

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fluvour.data.AnalyzedVideoDao
import app.fluvour.data.CardRepository
import app.fluvour.data.WordCountsJson
import app.fluvour.data.IndexQueueDao
import app.fluvour.data.SettingsRepository
import app.fluvour.reading.ClipCounts
import app.fluvour.reading.ClipIndexer
import app.fluvour.reading.ClipLibrary
import app.fluvour.reading.ClipLevels
import app.fluvour.reading.ClipMeasurer
import app.fluvour.reading.ClipRanking
import app.fluvour.reading.ClipSeeder
import app.fluvour.reading.IndexPlan
import app.fluvour.reading.LevelRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** How the worker reaches the app's singletons (WorkManager creates the worker itself, so there is no constructor injection). */
@EntryPoint @InstallIn(SingletonComponent::class)
interface ClipIndexEntryPoint {
    fun indexer(): ClipIndexer
    fun queue(): IndexQueueDao
    fun analyzed(): AnalyzedVideoDao
    fun levels(): LevelRepository
    fun settings(): SettingsRepository
    fun cards(): CardRepository
    fun seeder(): ClipSeeder
    fun library(): ClipLibrary
}

/**
 * Grows the local clip library in the background (roadmap 4.2, phase 2): takes links from `index_queue`, reads and measures each one
 * like the clips list does, saves the row and removes the link. Slow on purpose: one video at a time, a random pause between videos,
 * a daily cap and a stop after a run of failures. Only runs while charging and on an unmetered network (see [ClipIndexScheduler]).
 * Indexes the current target language only. The queue is filled by other steps (related videos), an empty queue ends the run at once.
 */
class ClipIndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val e = EntryPointAccessors.fromApplication(applicationContext, ClipIndexEntryPoint::class.java)
        val queue = e.queue()
        val analyzed = e.analyzed()
        val indexer = e.indexer()
        val settings = e.settings().settings.first()
        val lang = settings.targetLang
        if (!settings.clipLibrary) return Result.success()   // turned off in Settings (the schedule is cancelled too, this covers a run already queued)
        val declared = settings.levels[lang]   // without a declared level nothing can be judged, so no related videos are queued

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = IndexPlan.dayKey(System.currentTimeMillis())
        var doneToday = if (prefs.getLong(K_DAY, -1L) == today) prefs.getInt(K_DONE, 0) else 0

        e.library().prune(lang, declared)
        queue.dropFailed(IndexPlan.MAX_ATTEMPTS)
        queue.trim(lang, IndexPlan.QUEUE_CAP)
        val table = e.levels().get(lang)
        val scale = ClipLevels.scale(lang)
        val declaredIndex = declared?.let { scale.codes.indexOf(it) } ?: -1
        val known = if (declaredIndex >= 0) e.cards().knownWordSet(lang).mapTo(HashSet()) { it.lowercase() } else emptySet()

        val started = SystemClock.elapsedRealtime()
        var streak = 0
        while (!isStopped) {
            if (YouTubeGuard.coolingDown()) return Result.success()   // YouTube refused this IP recently: send nothing until the cooldown ends
            if (IndexPlan.remainingToday(doneToday) == 0) break
            if (IndexPlan.roomInLibrary(analyzed.countUsable()) == 0) break
            if (SystemClock.elapsedRealtime() - started > IndexPlan.RUN_MS) break

            val item = queue.next(lang, IndexPlan.MAX_ATTEMPTS, 1).firstOrNull() ?: break
            if (analyzed.knownUrls(listOf(item.url)).isNotEmpty()) { queue.remove(item.url); continue }

            val out = indexer.measure(VideoHit(item.url, "", null, 0L, null), lang, table)
            val row = out.row
            if (row == null) {
                if (YouTubeGuard.coolingDown()) return Result.success()   // a block answer is not this video's fault: it keeps its attempts
                queue.fail(item.url, out.error)
                if (++streak >= IndexPlan.FAIL_STREAK) return Result.retry()   // YouTube or the network is not answering: back off
            } else {
                analyzed.upsert(row)
                queue.remove(item.url)
                if (declaredIndex >= 0 && !row.noSubtitles && out.related.isNotEmpty()) {
                    val score = ClipMeasurer.score(ClipCounts(row.tokenTotal, WordCountsJson.decode(row.wordCounts)), lang, table, declared!!, known, row.durationMs)
                    if (score != null && ClipRanking.atLevel(score, declaredIndex, scale)) runCatching { e.seeder().seed(out.related, lang, "related", 1) }
                }
                streak = 0
                doneToday++
                prefs.edit().putLong(K_DAY, today).putInt(K_DONE, doneToday).apply()
            }
            delay(IndexPlan.delayMs(Random.nextDouble()))
        }
        return Result.success()
    }

    private companion object {
        const val PREFS = "clip_index"
        const val K_DAY = "day"
        const val K_DONE = "done"
    }
}

/** Starts and stops the background library job. Safe to call again and again. */
object ClipIndexScheduler {
    private const val NAME = "clip-index"

    fun schedule(ctx: Context) {
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).build()
        val request = PeriodicWorkRequestBuilder<ClipIndexWorker>(IndexPlan.PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) }
}
