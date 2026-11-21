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

/**
 * Word Power study: roots, prefixes and suffixes unlock in chapter order (a few new items a day), words unlock once the elements in
 * their parts have graduated, all scheduled with the app's FSRS module. The progress lives in one private file on the phone, apart
 * from your normal cards, so none of the deck's text is ever synced or exported. An item counts as "known" once it has graduated to
 * the review phase. Same shape as [HeisigStudyRepository], which it copies on purpose so Heisig is untouched.
 */
@Singleton
class WpmeStudyRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val deck: WpmeRepository,
    private val links: WpmeLinksRepository,
    private val scheduler: Scheduler,
) {
    companion object { const val DEFAULT_NEW_PER_DAY = 10; const val MAX_NEW_PER_DAY = 50 }

    private val prefs = ctx.getSharedPreferences("wpme", Context.MODE_PRIVATE)
    private val _newPerDay = MutableStateFlow(prefs.getInt("new_per_day", DEFAULT_NEW_PER_DAY).coerceIn(0, MAX_NEW_PER_DAY))
    /** How many new items unlock each day (0 pauses new items). Elements and words share it. */
    val newPerDay: StateFlow<Int> = _newPerDay.asStateFlow()

    fun setNewPerDay(n: Int) {
        val v = n.coerceIn(0, MAX_NEW_PER_DAY)
        prefs.edit().putInt("new_per_day", v).apply()
        _newPerDay.value = v
    }

    private val lock = Mutex()
    private var progress: MutableMap<String, WpmeProgress>? = null
    private val file get() = File(ctx.filesDir, WpmeProgressData.FILE)

    private fun zone() = ZoneId.systemDefault()
    private fun tz(now: Long) = zone().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds * 1000L
    private fun startOfToday() = LocalDate.now().atStartOfDay(zone()).toInstant().toEpochMilli()
    private fun endOfToday() = LocalDate.now().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun load(): MutableMap<String, WpmeProgress> = progress ?: run {
        val m = if (file.exists()) runCatching { file.bufferedReader().useLines { WpmeProgressData.decode(it) } }.getOrDefault(LinkedHashMap()) else LinkedHashMap()
        progress = m
        m
    }

    private fun save(m: Map<String, WpmeProgress>) {
        val tmp = File(ctx.filesDir, WpmeProgressData.FILE + ".tmp")
        tmp.writeText(WpmeProgressData.encode(m))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun introducedToday(m: Map<String, WpmeProgress>): Int { val t = startOfToday(); return m.values.count { it.introducedAt >= t } }
    private fun newLeft(m: Map<String, WpmeProgress>) = (_newPerDay.value - introducedToday(m)).coerceAtLeast(0)

    suspend fun stats(): WpmeStats = withContext(Dispatchers.IO) {
        val items = deck.all(); val lk = links.all(); val unlock = WpmeUnlock(items)
        lock.withLock {
            val m = load(); val now = System.currentTimeMillis(); val end = endOfToday()
            val started = m.values
            WpmeStats(
                total = items.size,
                learned = started.count { it.state.phase == Phase.REVIEW },
                learning = started.count { it.state.phase == Phase.LEARNING || it.state.phase == Phase.RELEARNING },
                due = started.count { scheduler.isDue(it.state, now, end) },
                newLeftToday = minOf(WpmeStudyPlan.fresh(items, lk, unlock, m).size, newLeft(m)),
                waiting = WpmeStudyPlan.waiting(items, lk, unlock, m),
            )
        }
    }

    /** Studied items that are due, soonest first, then today's share of new items: elements first, then words whose parts are known. */
    suspend fun session(): List<WpmeItem> = withContext(Dispatchers.IO) {
        val items = deck.all(); val lk = links.all(); val unlock = WpmeUnlock(items)
        lock.withLock {
            val m = load(); val now = System.currentTimeMillis(); val end = endOfToday()
            WpmeStudyPlan.session(items, lk, unlock, m, newLeft(m)) { scheduler.isDue(it, now, end) }
        }
    }

    /** The next `n` items nobody has studied yet that can be started, ignoring the daily limit ("learn more"). */
    suspend fun moreNew(n: Int): List<WpmeItem> = withContext(Dispatchers.IO) {
        val items = deck.all(); val lk = links.all(); val unlock = WpmeUnlock(items)
        lock.withLock { WpmeStudyPlan.fresh(items, lk, unlock, load()).take(n) }
    }

    /** The parts of a word card with the deck element each stands for (for the card back). Empty for elements and plain words. */
    suspend fun parts(key: String): List<WpmePartInfo> = withContext(Dispatchers.IO) {
        val link = links.get(key) ?: return@withContext emptyList()
        val unlock = WpmeUnlock(deck.all())
        link.parts.map { p -> WpmePartInfo(p, unlock.elementsFor(p).firstOrNull()) }
    }

    suspend fun preview(key: String): Map<Rating, Long> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val state = lock.withLock { load()[key]?.state } ?: CardState()
        scheduler.preview(state, now, tz(now))
    }

    suspend fun review(key: String, rating: Rating): CardState = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = load(); val now = System.currentTimeMillis()
            val before = m[key]
            val after = scheduler.review(before?.state ?: CardState(), rating, now, tz(now))
            m[key] = WpmeProgress(after, before?.introducedAt ?: now)
            save(m)
            after
        }
    }

    /** Keys of items that have graduated to the review phase. */
    suspend fun known(): Set<String> = withContext(Dispatchers.IO) { lock.withLock { WpmeStudyPlan.known(load()) } }

    suspend fun reset() = withContext(Dispatchers.IO) { lock.withLock { progress = LinkedHashMap(); file.delete() } }
}
