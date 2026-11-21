package app.fluvour.media

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.reading.ClipRef
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@EntryPoint @InstallIn(SingletonComponent::class)
interface ClipAheadEntryPoint {
    fun cards(): CardRepository
    fun settings(): SettingsRepository
    fun source(): YouTubeSource
}

/**
 * Saves the few seconds of every due clip card ahead of time (see [ClipCache]), so studying them sends no YouTube request. Only when the
 * card front is set to clip. One lookup per video (shared with the Review screen's cache), at most [MAX_VIDEOS] videos per run, and it stops
 * at the first sign of a YouTube block. Runs on any network, and while charging even at a low battery (not on a low battery that is not charging).
 */
class ClipAheadWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!running.compareAndSet(false, true)) return Result.success()
        try { return run() } finally { running.set(false) }
    }

    private suspend fun run(): Result {
        runCatching { ClipCache.trim(ctx) }   // keeps the saved clips under their size cap even when nothing is fetched
        if (!ClipCache.aheadOn(ctx)) return Result.success()
        if (!powerOk() || YouTubeGuard.coolingDown()) return Result.success()
        val e = EntryPointAccessors.fromApplication(applicationContext, ClipAheadEntryPoint::class.java)
        val st = e.settings().settings.first()
        val byVideo = LinkedHashMap<String, MutableList<ClipRef>>()
        // clips of the due CLIP facets (the saved "front" setting this used to check is gone: the mode row replaced it)
        for (item in e.cards().dueItems(st.targetLang, app.fluvour.srs.FacetKind.CLIP, 150)) {
            val ref = e.cards().clipFor(item.card) ?: continue
            byVideo.getOrPut(ref.url) { mutableListOf() }.add(ref)
        }
        val started = SystemClock.elapsedRealtime()
        var videos = 0
        for ((url, refs) in byVideo) {
            if (isStopped || SystemClock.elapsedRealtime() - started > RUN_MS || !powerOk() || YouTubeGuard.coolingDown()) break
            val todo = refs.filter { ClipCache.readyItag(ctx, it) == null }
            if (todo.isEmpty()) continue
            if (++videos > MAX_VIDEOS) break
            val item = e.source().load(url, st.targetLang, st.uiLang).getOrNull() ?: continue
            for (ref in todo) { if (isStopped) break; ClipCache.fetch(ctx, ref, item.streamUrl) }
        }
        return Result.success()
    }

    /** Charging at any level, or not charging with at least 20%. */
    private fun powerOk(): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 || pct >= 20
    }

    private companion object {
        val running = AtomicBoolean(false)
        const val RUN_MS = 8 * 60_000L
        const val MAX_VIDEOS = 6
    }
}

/** Starts and stops the background clip job. Safe to call again and again. */
object ClipAheadScheduler {
    private const val PERIODIC = "clip-ahead"
    private const val NOW = "clip-ahead-now"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<ClipAheadWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A run soon: when the app opens. */
    fun kick(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<ClipAheadWorker>().setConstraints(constraints()).setInitialDelay(30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC); WorkManager.getInstance(ctx).cancelUniqueWork(NOW) }
}
