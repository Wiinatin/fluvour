package app.fluvour.reading

import android.content.Context
import app.fluvour.srs.CardState
import app.fluvour.srs.Phase
import app.fluvour.srs.Rating
import app.fluvour.srs.Scheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** FSRS memory state of one Heisig kanji, plus when it was first studied (for the daily limit of new kanji). */
data class HeisigProgress(val state: CardState, val introducedAt: Long)

data class HeisigStats(val total: Int, val learned: Int, val learning: Int, val due: Int, val newLeftToday: Int)

/** The progress file: one line per studied kanji. Pure, so it can be checked without Android. */
object HeisigProgressData {
    fun encode(m: Map<String, HeisigProgress>): String = m.entries.joinToString("\n") { (ch, p) ->
        val s = p.state
        listOf(ch, s.phase.name, s.stability, s.difficulty, s.step, s.lastReviewMillis ?: "", s.dueMillis ?: "", s.scheduledDays, s.reps, s.lapses, p.introducedAt).joinToString("\t")
    }

    fun decode(lines: Sequence<String>): MutableMap<String, HeisigProgress> {
        val m = LinkedHashMap<String, HeisigProgress>()
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 11) continue
            val phase = runCatching { Phase.valueOf(p[1]) }.getOrNull() ?: continue
            val state = CardState(
                phase, p[2].toDoubleOrNull() ?: continue, p[3].toDoubleOrNull() ?: continue, p[4].toIntOrNull() ?: 0,
                p[5].toLongOrNull(), p[6].toLongOrNull(), p[7].toIntOrNull() ?: 0, p[8].toIntOrNull() ?: 0, p[9].toIntOrNull() ?: 0
            )
            m[p[0]] = HeisigProgress(state, p[10].toLongOrNull() ?: 0L)
        }
        return m
    }
}

/**
 * Heisig study: kanji unlock in frame order (a few new ones a day), scheduled with the app's FSRS module.
 * The progress lives in one private file on the phone, apart from your normal cards, so none of the deck's text
 * is ever synced or exported. A kanji counts as "known" once it has graduated to the review phase.
 */
@Singleton
class HeisigStudyRepository @Inject constructor(
    @ApplicationContext private val ctx: Context, private val heisig: HeisigRepository, private val scheduler: Scheduler
) {
    companion object { const val DEFAULT_NEW_PER_DAY = 10; const val MAX_NEW_PER_DAY = 50 }

    private val prefs = ctx.getSharedPreferences("heisig", Context.MODE_PRIVATE)
    private val _newPerDay = MutableStateFlow(prefs.getInt("new_per_day", DEFAULT_NEW_PER_DAY).coerceIn(0, MAX_NEW_PER_DAY))
    /** How many new kanji unlock each day (0 pauses new kanji). Set in Settings > Heisig deck. */
    val newPerDay: StateFlow<Int> = _newPerDay.asStateFlow()

    fun setNewPerDay(n: Int) {
        val v = n.coerceIn(0, MAX_NEW_PER_DAY)
        prefs.edit().putInt("new_per_day", v).apply()
        _newPerDay.value = v
    }

    private val _practiceTrace = MutableStateFlow(prefs.getBoolean("practice_trace", true))
    /** Practice page: show the dimmed kanji to trace. On by default, remembered. Only this switch is stored, never a drawing. */
    val practiceTrace: StateFlow<Boolean> = _practiceTrace.asStateFlow()
    fun setPracticeTrace(on: Boolean) { prefs.edit().putBoolean("practice_trace", on).apply(); _practiceTrace.value = on }

    private val _practiceStory = MutableStateFlow(prefs.getBoolean("practice_story", true))
    /** Practice page: show the Heisig story under the keyword. On by default, remembered. */
    val practiceStory: StateFlow<Boolean> = _practiceStory.asStateFlow()
    fun setPracticeStory(on: Boolean) { prefs.edit().putBoolean("practice_story", on).apply(); _practiceStory.value = on }

    private val _maturityColors = MutableStateFlow(prefs.getBoolean("maturity_colors", true))
    /** Settings: colour kanji by maturity in the reading and watch pages. On by default. */
    val maturityColors: StateFlow<Boolean> = _maturityColors.asStateFlow()
    fun setMaturityColors(on: Boolean) { prefs.edit().putBoolean("maturity_colors", on).apply(); _maturityColors.value = on }

    private val _submits = MutableStateFlow(prefs.all.filterKeys { it.startsWith("submits_") }.mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix("submits_") to it } }.toMap())
    /** Practice page: how many times each kanji was submitted, kept across visits. Only a count; it never changes the maturity tier. */
    val submits: StateFlow<Map<String, Int>> = _submits.asStateFlow()
    fun addSubmit(ch: String) {
        val n = (_submits.value[ch] ?: 0) + 1
        prefs.edit().putInt("submits_$ch", n).apply()
        _submits.value = _submits.value + (ch to n)
    }

    private val lock = Mutex()
    private var progress: MutableMap<String, HeisigProgress>? = null
    private val file get() = File(ctx.filesDir, "heisig-progress.tsv")

    private fun zone() = ZoneId.systemDefault()
    private fun tz(now: Long) = zone().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds * 1000L
    private fun startOfToday() = LocalDate.now().atStartOfDay(zone()).toInstant().toEpochMilli()
    private fun endOfToday() = LocalDate.now().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun load(): MutableMap<String, HeisigProgress> = progress ?: run {
        val m = if (file.exists()) runCatching { file.bufferedReader().useLines { HeisigProgressData.decode(it) } }.getOrDefault(LinkedHashMap()) else LinkedHashMap()
        progress = m
        m
    }

    private fun save(m: Map<String, HeisigProgress>) {
        val tmp = File(ctx.filesDir, "heisig-progress.tsv.tmp")
        tmp.writeText(HeisigProgressData.encode(m))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun introducedToday(m: Map<String, HeisigProgress>): Int { val t = startOfToday(); return m.values.count { it.introducedAt >= t } }

    suspend fun stats(): HeisigStats = withContext(Dispatchers.IO) {
        val all = heisig.all()
        lock.withLock {
            val m = load(); val now = System.currentTimeMillis(); val end = endOfToday()
            val started = m.values
            val unstarted = all.count { it.ch !in m }
            HeisigStats(
                total = all.size,
                learned = started.count { it.state.phase == Phase.REVIEW },
                learning = started.count { it.state.phase == Phase.LEARNING || it.state.phase == Phase.RELEARNING },
                due = started.count { scheduler.isDue(it.state, now, end) },
                newLeftToday = minOf(unstarted, (_newPerDay.value - introducedToday(m)).coerceAtLeast(0))
            )
        }
    }

    /** Maturity of every studied kanji (never studied = missing = PLAIN). Read from the FSRS state, nothing new is stored. */
    suspend fun tiers(): Map<String, Tier> = withContext(Dispatchers.IO) {
        lock.withLock { load().mapValues { (_, p) -> tierOf(true, p.state.phase, p.state.stability) }.filterValues { it != Tier.PLAIN } }
    }

    /** Learning path: kanji in review with an interval of [minDays] days or more (what a unit test may write). */
    suspend fun maturedKanji(minDays: Int): Set<Char> = withContext(Dispatchers.IO) {
        lock.withLock { load().filterValues { it.state.phase == Phase.REVIEW && it.state.scheduledDays >= minDays }.keys.mapNotNull { it.firstOrNull() }.toSet() }
    }

    /** Studied kanji that are due today (same test as the Study queue). For the small dot on the practice grid. */
    suspend fun dueToday(): Set<String> = withContext(Dispatchers.IO) {
        lock.withLock { val now = System.currentTimeMillis(); val end = endOfToday(); load().filterValues { scheduler.isDue(it.state, now, end) }.keys.toSet() }
    }

    suspend fun tier(ch: String): Tier = withContext(Dispatchers.IO) {
        lock.withLock { load()[ch]?.let { tierOf(true, it.state.phase, it.state.stability) } ?: Tier.PLAIN }
    }

    /** Studied kanji that are due, soonest first, then today's share of new kanji in frame order. */
    suspend fun session(): List<HeisigEntry> = withContext(Dispatchers.IO) {
        val all = heisig.all()
        lock.withLock {
            val m = load(); val now = System.currentTimeMillis(); val end = endOfToday()
            val byCh = all.associateBy { it.ch }
            val due = m.entries.filter { scheduler.isDue(it.value.state, now, end) }.sortedBy { it.value.state.dueMillis ?: 0L }.mapNotNull { byCh[it.key] }
            val fresh = all.filter { it.ch !in m }.take((_newPerDay.value - introducedToday(m)).coerceAtLeast(0))
            due + fresh
        }
    }

    /** The next `n` kanji nobody has studied yet, ignoring the daily limit ("learn more"). */
    suspend fun moreNew(n: Int): List<HeisigEntry> = withContext(Dispatchers.IO) {
        val all = heisig.all()
        lock.withLock { val m = load(); all.filter { it.ch !in m }.take(n) }
    }

    suspend fun preview(ch: String): Map<Rating, Long> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val state = lock.withLock { load()[ch]?.state } ?: CardState()
        scheduler.preview(state, now, tz(now))
    }

    suspend fun review(ch: String, rating: Rating): CardState = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = load(); val now = System.currentTimeMillis()
            val before = m[ch]
            val after = scheduler.review(before?.state ?: CardState(), rating, now, tz(now))
            m[ch] = HeisigProgress(after, before?.introducedAt ?: now)
            save(m)
            after
        }
    }

    /** Kanji that have graduated to the review phase: the base for marking kanji as known in videos. */
    suspend fun known(): Set<String> = withContext(Dispatchers.IO) {
        lock.withLock { load().filterValues { it.state.phase == Phase.REVIEW }.keys.toSet() }
    }

    suspend fun reset() = withContext(Dispatchers.IO) { lock.withLock { progress = LinkedHashMap(); file.delete() } }
}
