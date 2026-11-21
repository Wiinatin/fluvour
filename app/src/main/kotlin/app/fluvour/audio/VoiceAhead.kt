package app.fluvour.audio

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
import app.fluvour.data.CardDao
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.data.SpeakingDao
import app.fluvour.path.PathRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How the worker reaches the app's singletons (WorkManager creates the worker itself). */
@EntryPoint @InstallIn(SingletonComponent::class)
interface VoiceAheadEntryPoint {
    fun tts(): TtsManager
    fun path(): PathRepository
    fun cards(): CardRepository
    fun cardDao(): CardDao
    fun speakingDao(): SpeakingDao
    fun settings(): SettingsRepository
}

/** What to voice, nearest need first. Texts already saved are skipped later; the same text is listed once. */
object VoicePlan {
    class Item(val text: String, val lang: String, val keep: Boolean = false)

    suspend fun build(lang: String, cards: CardRepository, cardDao: CardDao, speaking: SpeakingDao, lesson: List<String> = emptyList()): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun add(t: String?, l: String, keep: Boolean = false) {
            val s = t?.trim().orEmpty(); if (s.isEmpty()) return
            val old = out[s]
            if (old == null) out[s] = Item(s, l, keep) else if (keep && !old.keep) out[s] = Item(s, l, true)
        }
        lesson.forEach { add(it, lang) }                                         // 0. the sentences of the lessons you opened (learning path)
        val due = cards.dueItems(lang, null, 150).map { it.card }.distinctBy { it.id }   // due facets of every kind share the card's voice
        val soon = cardDao.upcoming(lang, 300)
        due.forEach { add(it.word, lang) }                                       // 1. the words of today's cards (cards and quizzes)
        speaking.improvedTexts(lang, 60).forEach { add(it.text, it.lang, keep = true) }      // 2. the improved versions of your Output
        due.forEach { add(it.example, lang) }                                    // 3. today's example sentences
        soon.forEach { add(it.word, lang) }                                      // 4. the next days' words
        soon.forEach { add(it.example, lang) }                                   // 5. the next days' sentences
        return out.values.toList()
    }
}

/**
 * Makes the Gemini voice for cards, quizzes and improved outputs ahead of time, so the speaker button plays at once. Runs on Wi-Fi or
 * mobile data, and while charging even at a low battery (not on a low battery that is not charging). Slow on purpose: one request at a
 * time with a pause, within the daily allowance of [VoiceBudget]; a "limit" answer is retried once after a minute (per-minute limit),
 * and a second refusal ends the day. The current learning language only.
 */
class VoiceAheadWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!running.compareAndSet(false, true)) return Result.success()   // a run is already going
        try { return run() } finally { running.set(false) }
    }

    private suspend fun run(): Result {
        val e = EntryPointAccessors.fromApplication(applicationContext, VoiceAheadEntryPoint::class.java)
        val tts = e.tts()
        val budget = tts.budget
        if (!budget.enabled || !tts.aiVoice.value || !powerOk()) return Result.success()
        val lang = e.settings().settings.first().targetLang
        val lesson = runCatching { e.path().lessonSentences(lang) }.getOrDefault(emptyList())
        if (lesson.isNotEmpty() && budget.room() > 0) tts.makeBatch(lesson, lang)   // one request for the lesson sentences; a mismatch leaves them to the one-by-one loop below
        val plan = VoicePlan.build(lang, e.cards(), e.cardDao(), e.speakingDao(), lesson).filter { tts.wantsGemini(it.lang) }

        val started = SystemClock.elapsedRealtime()
        var fails = 0
        for (item in plan) {
            if (isStopped || SystemClock.elapsedRealtime() - started > RUN_MS || !powerOk()) break
            if (item.keep && tts.keepIfCached(item.text)) continue   // improved versions are kept for good: a saved sound is copied to the kept folder
            if (tts.isCached(item.text)) continue
            if (budget.room() == 0) break
            var r = tts.makeAhead(item.text, item.keep, item.lang)
            if (r == TtsManager.Ahead.LIMITED) {          // the per-minute limit or the daily one? wait a minute and ask once more
                delay(65_000)
                r = tts.makeAhead(item.text, item.keep, item.lang)
                if (r == TtsManager.Ahead.LIMITED) { budget.noteLimited(); break }
                if (r == TtsManager.Ahead.MADE) budget.gapMs = budget.gapMs * 2   // it was the per-minute limit: go slower
            }
            if (r == TtsManager.Ahead.FAILED) { if (++fails >= 3) break } else fails = 0   // no connection or an odd answer: stop and try at the next run
            if (r == TtsManager.Ahead.MADE) delay(budget.gapMs)
        }
        budget.saveProgress(plan.count { tts.isCached(it.text) }, plan.size)
        return Result.success()
    }

    /** Charging at any level, or not charging with at least 20%. */
    private fun powerOk(): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return charging || pct >= 20
    }

    private companion object {
        val running = AtomicBoolean(false)
        const val RUN_MS = 8 * 60_000L
    }
}

/** Starts and stops the background voice job. Safe to call again and again. */
object VoiceAheadScheduler {
    private const val PERIODIC = "voice-ahead"
    private const val NOW = "voice-ahead-now"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()   // Wi-Fi or mobile data

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<VoiceAheadWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A run soon: when the app opens, the cards open, or an improved version was just made. */
    fun kick(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<VoiceAheadWorker>().setConstraints(constraints()).setInitialDelay(5, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC); WorkManager.getInstance(ctx).cancelUniqueWork(NOW) }
}
