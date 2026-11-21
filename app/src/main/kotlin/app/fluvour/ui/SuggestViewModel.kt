package app.fluvour.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.ClipPrompt
import app.fluvour.ai.ClipQuery
import app.fluvour.ai.ClipSuggester
import app.fluvour.data.AnalyzedVideoDao
import app.fluvour.data.AnalyzedVideoEntity
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.data.WatchHistoryDao
import app.fluvour.data.WordCountsJson
import app.fluvour.language.Languages
import app.fluvour.media.VideoHit
import app.fluvour.media.YouTubeSource
import app.fluvour.reading.ClipCounts
import app.fluvour.reading.ClipIndexer
import app.fluvour.reading.ClipLevels
import app.fluvour.reading.ClipLibrary
import app.fluvour.reading.ClipMeasurer
import app.fluvour.reading.ClipPlan
import app.fluvour.reading.ClipRanking
import app.fluvour.reading.ClipScore
import app.fluvour.reading.ClipSeeder
import app.fluvour.reading.LevelRepository
import app.fluvour.reading.LevelTable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/** One clip in the list: what `VideoRow` shows, plus the muted tag line (`C1 · C2 knows 98% · you know 94%`). */
data class ClipItem(val url: String, val title: String, val uploader: String?, val durationMs: Long, val thumbUrl: String?, val tags: String)

/** The quiet row on the idle Watch page. [Hidden] shows nothing at all (no error, no spinner). */
sealed interface ClipsRow {
    data object Hidden : ClipsRow
    /** No level set for this language: the row asks for one and opens the level picker. */
    data object NeedsLevel : ClipsRow
    /** [words]: the learner words Gemini aimed its phrases at, for `Clips for 遅刻, 締め切り and 13 more`. May be empty. */
    data class Ready(val words: List<String>) : ClipsRow
}

/**
 * What the screen shows. [clips] grows batch by batch while [loading] is true. While loading, the progress line reads
 * `Reading subtitles, $done of $total`. [note] is one muted line (no red) for a partly failed or empty run.
 */
data class ClipsUi(
    val row: ClipsRow = ClipsRow.Hidden,
    val clips: List<ClipItem> = emptyList(),
    val loading: Boolean = false,
    val done: Int = 0, val total: Int = 0,
    val note: String? = null
)

/**
 * Clip recommendations on the Watch page (roadmap 4.2, Phase 1).
 *
 * Gemini writes search phrases, NewPipe finds real videos, their real subtitles are measured, and the best fits are shown.
 * Everything measured is saved in `analyzed_videos` (Phase 2 builds on that table).
 *
 * The row only needs Gemini's phrases. They are asked for quietly (one call, at most once a day for the same level, count and language),
 * and no NewPipe search or subtitle read starts until the clips view is opened ([openClips]) or reshuffled ([reshuffle]).
 */
@HiltViewModel
class SuggestViewModel @Inject constructor(
    @ApplicationContext ctx: Context,
    private val settingsRepo: SettingsRepository,
    private val cards: CardRepository,
    private val suggester: ClipSuggester,
    private val source: YouTubeSource,
    private val indexer: ClipIndexer,
    private val seeder: ClipSeeder,
    private val library: ClipLibrary,
    private val levelRepo: LevelRepository,
    private val analyzed: AnalyzedVideoDao,
    private val historyDao: WatchHistoryDao
) : ViewModel() {

    private val prefs = ctx.getSharedPreferences("clips", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(ClipsUi())
    val ui: StateFlow<ClipsUi> = _ui.asStateFlow()

    private var watching = false
    private var runJob: Job? = null

    /** What the current clip choice depends on. A change makes the old choice stale. */
    private data class Ctx(val lang: String, val level: String?, val count: Int)

    /** The saved clip choice: the phrases, and the chosen links once a run has finished. */
    private data class Saved(val key: String, val at: Long, val queries: List<ClipQuery>, val urls: List<String>?)

    /** Everything needed to turn a stored row into a number and a tag, for one user. */
    private class Ranker(
        val lang: String, val table: LevelTable?, val scale: ClipLevels.Scale, val declared: Int, val known: Set<String>
    ) {
        private val code get() = scale.codes[declared]
        private val label get() = scale.labels[declared]

        private fun scored(rows: List<AnalyzedVideoEntity>): List<Pair<AnalyzedVideoEntity, ClipScore>> = rows.mapNotNull { r ->
            ClipMeasurer.score(ClipCounts(r.tokenTotal, WordCountsJson.decode(r.wordCounts)), lang, table, code, known, r.durationMs)?.let { r to it }
        }

        private fun item(r: AnalyzedVideoEntity, s: ClipScore) = ClipItem(r.url, r.title, r.uploader, r.durationMs, r.thumbUrl, ClipMeasurer.tagLine(s, label))

        /** True when the row is at the learner's level (the 85 to 95% band, see [ClipRanking.atLevel]). Such clips seed the background library. */
        fun fits(r: AnalyzedVideoEntity): Boolean {
            val s = ClipMeasurer.score(ClipCounts(r.tokenTotal, WordCountsJson.decode(r.wordCounts)), lang, table, code, known, r.durationMs) ?: return false
            return ClipRanking.atLevel(s, declared, scale)
        }

        /** Best fit first (see [ClipRanking]). Rows without words are dropped. */
        fun rank(rows: List<AnalyzedVideoEntity>): List<ClipItem> =
            ClipRanking.rank(scored(rows), declared, scale) { it.second }.map { item(it.first, it.second) }

        /** Same order as given, numbers fresh (the learned words may have changed since the choice was saved). */
        fun keepOrder(rows: List<AnalyzedVideoEntity>): List<ClipItem> = scored(rows).map { item(it.first, it.second) }
    }

    // ---- Row ----

    /** The level picker opened from the "Set your level" row. The row follows the new level by itself. */
    fun setLevel(lang: String, code: String) { viewModelScope.launch { settingsRepo.setLevel(lang, code) } }

    /**
     * Called when the Watch page is shown. Starts once: from then on the row follows the language, the level and the clip count.
     * Safe to call again and again.
     */
    fun onWatchShown() {
        if (watching) return
        watching = true
        viewModelScope.launch {
            settingsRepo.settings
                .map { Ctx(it.targetLang, it.levels[it.targetLang], it.clipCount.coerceIn(1, 30)) }
                .distinctUntilChanged()
                .collectLatest { c ->
                    runJob?.cancel()
                    _ui.value = ClipsUi()
                    prepare(c)
                }
        }
    }

    /** Decides what the row shows. Reads the cache; asks Gemini once when there is none. Never touches NewPipe. */
    private suspend fun prepare(c: Ctx) {
        val level = c.level
        if (level == null || level !in ClipLevels.scale(c.lang).codes) { setRow(ClipsRow.NeedsLevel); return }
        val known = knownLower(c.lang)
        val learning = cards.recentLearningWords(c.lang, ClipPlan.LEARNED_WORDS)
        if (known.size + learning.size < ClipPlan.MIN_CARDS) { setRow(ClipsRow.Hidden); return }
        val key = ClipPlan.cacheKey(c.lang, level, c.count)
        val now = System.currentTimeMillis()
        val saved = loadCache()?.takeIf { ClipPlan.cacheValid(it.at, now, it.key, key) }
        if (saved != null) { setRow(ready(saved.queries)); return }
        val learned = (learning + known).distinct().take(ClipPlan.LEARNED_WORDS)
        suggester.suggest(c.lang, Languages.byCode(c.lang).name, level, learned, c.count).fold(
            onSuccess = { q -> saveCache(Saved(key, now, q, null)); setRow(ready(q)) },
            onFailure = { setRow(ClipsRow.Hidden) }   // Gemini out of quota or offline: no text, no spinner
        )
    }

    private fun ready(q: List<ClipQuery>) = ClipsRow.Ready(ClipPlan.rowWords(q.map { it.words }))
    private fun setRow(r: ClipsRow) { _ui.update { it.copy(row = r) } }

    // ---- Clips view ----

    /** The row was tapped. Starts the run unless clips are already there or a run is going. */
    fun openClips() {
        if (_ui.value.row !is ClipsRow.Ready) return
        if (runJob?.isActive == true || _ui.value.clips.isNotEmpty()) return
        start()
    }

    /** The reload button: forget the saved choice and everything shown, ask Gemini again, search again. */
    fun reshuffle() {
        runJob?.cancel()
        prefs.edit().remove(CACHE).apply()
        _ui.update { it.copy(clips = emptyList(), note = null, done = 0, total = 0) }
        start()
    }

    private fun start() {
        runJob?.cancel()
        runJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, note = null, done = 0, total = 0) }
            try { run() } finally { _ui.update { it.copy(loading = false) } }
            runCatching { settingsRepo.settings.first().let { library.prune(it.targetLang, it.levels[it.targetLang]) } }   // keeps the library under its cap even when the background job is off
        }
    }

    private suspend fun run() {
        val st = settingsRepo.settings.first()
        val lang = st.targetLang
        val n = st.clipCount.coerceIn(1, 30)
        val level = st.levels[lang] ?: return
        val scale = ClipLevels.scale(lang)
        val declared = scale.codes.indexOf(level)
        if (declared < 0) return
        val known = knownLower(lang)
        val table = levelRepo.get(lang)
        val ranker = Ranker(lang, table, scale, declared, known)
        val key = ClipPlan.cacheKey(lang, level, n)
        val now = System.currentTimeMillis()
        val histKeys = historyKeys(lang)

        // 1. The phrases: from the cache, or one Gemini call.
        var saved = loadCache()?.takeIf { ClipPlan.cacheValid(it.at, now, it.key, key) }
        if (saved == null) {
            val learning = cards.recentLearningWords(lang, ClipPlan.LEARNED_WORDS)
            val learned = (learning + known).distinct().take(ClipPlan.LEARNED_WORDS)
            val q = suggester.suggest(lang, Languages.byCode(lang).name, level, learned, n).getOrElse {
                setRow(ClipsRow.Hidden); return
            }
            saved = Saved(key, now, q, null)
            saveCache(saved)
            setRow(ready(q))
        }

        // 2. The same choice as a few hours ago: show it again from the saved rows, with fresh numbers.
        saved.urls?.let { urls ->
            val rows = withContext(Dispatchers.IO) { analyzed.byUrls(urls) }.associateBy { it.url }
            val items = withContext(Dispatchers.Default) {
                ranker.keepOrder(urls.mapNotNull { rows[it] }.filter { ClipPlan.videoKey(it.url) !in histKeys })
            }
            if (items.isNotEmpty()) { _ui.update { it.copy(clips = items) }; return }
        }

        // 3. The local library first: clips measured earlier (by searches and by the background job) show at once, with no network.
        val lib = loadLibrary(lang, scale, declared, histKeys, n, now)
        val libUrls = lib.mapTo(HashSet()) { it.url }
        val libItems = if (lib.isEmpty()) emptyList() else withContext(Dispatchers.Default) { ranker.rank(lib).take(n) }
        if (libItems.isNotEmpty()) _ui.update { it.copy(clips = libItems) }

        // 4. Fresh results through NewPipe (the Gemini phrases), then the cheap cut. They are ranked together with the library at the end.
        val (hits, searchFailed) = searchAll(saved.queries, histKeys, ClipPrompt.poolSize(n))
        if (hits.isEmpty()) {
            if (libItems.isNotEmpty()) { finish(saved, libItems, now); return }   // no network or no hits: the library alone is enough
            _ui.update { it.copy(note = if (searchFailed == saved.queries.size) "Could not reach YouTube. Try again later." else "No clips found right now. Try the reload button.") }
            return
        }
        _ui.update { it.copy(total = hits.size) }

        // 5. Videos measured before are not read again. The rest are read in batches; the list grows with every batch.
        val have = withContext(Dispatchers.IO) { analyzed.byUrls(hits.map { it.url }) }
        val haveUrls = have.mapTo(HashSet()) { it.url }
        val toRead = hits.filter { it.url !in haveUrls }
        val all = ArrayList(have)
        var pending: List<AnalyzedVideoEntity> = have.filter { it.url !in libUrls }   // the ones already in the list from the library are not added twice
        var shown = libItems
        var done = have.size
        var failed = 0

        suspend fun publish() {
            if (pending.isEmpty()) { _ui.update { it.copy(done = done) }; return }
            val batchItems = withContext(Dispatchers.Default) { ranker.rank(pending) }   // sorted within the batch, appended after the earlier ones
            pending = emptyList()
            shown = shown + batchItems
            _ui.update { it.copy(clips = shown, done = done) }
        }

        if (toRead.isEmpty()) publish()
        for (batch in toRead.chunked(ClipPlan.BATCH)) {
            val r = readBatch(batch, lang, table, ranker)
            all += r.rows
            pending = pending + r.rows
            failed += r.failed
            done += batch.size
            publish()
        }

        // 6. Done: the best N of library plus fresh results, saved so the next visit within 24 h needs no searching.
        val pool = LinkedHashMap<String, AnalyzedVideoEntity>()
        for (r in lib) pool[r.url] = r
        for (r in all) pool[r.url] = r
        val top = withContext(Dispatchers.Default) { ranker.rank(pool.values.toList()) }.take(n)
        finish(saved, top, now, if (failed + searchFailed > 0) "Some clips could not be loaded." else null)
    }

    /** Shows the final list, remembers it for 24 h and marks the clips as shown so the next reload prefers other library rows. */
    private suspend fun finish(saved: Saved, top: List<ClipItem>, now: Long, problem: String? = null) {
        _ui.update { it.copy(clips = top) }
        if (top.isNotEmpty()) {
            saveCache(saved.copy(urls = top.map { it.url }))
            withContext(Dispatchers.IO) { analyzed.markShown(top.map { it.url }, now) }
        }
        _ui.update { it.copy(note = if (top.isEmpty()) "No clips found right now. Try the reload button." else problem) }
    }

    /**
     * A random sample of library rows near the learner's level, not in the watch history, not shown in the last few days.
     * When too few are left the cool-down is dropped so the list is never empty just because it was seen before.
     */
    private suspend fun loadLibrary(lang: String, scale: ClipLevels.Scale, declared: Int, histKeys: Set<String>, n: Int, now: Long): List<AnalyzedVideoEntity> =
        withContext(Dispatchers.IO) {
            val levels = ClipPlan.libraryLevels(scale, declared)
            if (levels.isEmpty()) return@withContext emptyList()
            suspend fun load(shownBefore: Long) = analyzed.pool(lang, levels, ClipRanking.MIN_MS, ClipRanking.MAX_MS, shownBefore, ClipPlan.LIBRARY_POOL)
                .filter { ClipPlan.videoKey(it.url) !in histKeys }
            val fresh = load(now - ClipPlan.SHOWN_COOLDOWN_MS)
            if (fresh.size >= n) fresh else load(Long.MAX_VALUE)
        }

    /** All phrases searched, 3 at a time. Returns the pool (cheap cut applied, round-robin over the phrases) and how many searches failed. */
    private suspend fun searchAll(queries: List<ClipQuery>, skip: Set<String>, pool: Int): Pair<List<VideoHit>, Int> = coroutineScope {
        val gate = Semaphore(ClipPlan.SEARCH_PARALLEL)
        val results = queries.map { q -> async { gate.withPermit { source.search(q.query) } } }.awaitAll()
        val lists = results.map { r -> ClipPlan.cheapCut(r.getOrNull().orEmpty(), { it.url }, { it.durationMs }, skip, ClipPlan.HITS_PER_QUERY) }
        ClipPlan.cheapCut(ClipPlan.interleave(lists), { it.url }, { it.durationMs }, skip, pool) to results.count { it.isFailure }
    }

    private class Batch(val rows: List<AnalyzedVideoEntity>, val failed: Int)

    /** Reads and measures a batch, 3 videos at a time, and saves every measured video to the library table. */
    private suspend fun readBatch(batch: List<VideoHit>, lang: String, table: LevelTable?, ranker: Ranker): Batch = coroutineScope {
        val gate = Semaphore(ClipPlan.READ_PARALLEL)
        val out = batch.map { h -> async { gate.withPermit { indexer.measure(h, lang, table) } } }.awaitAll()
        val rows = out.mapNotNull { it.row }   // includes videos without subtitles: saved so they are never read again, but not shown
        if (rows.isNotEmpty()) withContext(Dispatchers.IO) { analyzed.upsertAll(rows) }
        // Clips that fit the learner lead to more of the same kind: their related videos wait in the library queue.
        for (o in out) {
            val r = o.row ?: continue
            if (!r.noSubtitles && o.related.isNotEmpty() && ranker.fits(r)) runCatching { seeder.seed(o.related, lang, "related", 1) }
        }
        Batch(rows.filter { !it.noSubtitles }, out.count { it.failed })
    }

    // ---- Small helpers ----

    /** Lower-cased words of the learner's graduated cards (the "you know" part of the numbers). */
    private suspend fun knownLower(lang: String): Set<String> = cards.knownWordSet(lang).mapTo(HashSet()) { it.lowercase() }

    /** Videos already in the watch history of this language, by [ClipPlan.videoKey]. The history keeps the last 50 per language. */
    private suspend fun historyKeys(lang: String): Set<String> = historyDao.observe(lang, 50).first().mapTo(HashSet()) { ClipPlan.videoKey(it.url) }

    // ---- Cache (SharedPreferences, one entry) ----

    private fun saveCache(s: Saved) {
        val qs = JSONArray()
        for (q in s.queries) qs.put(JSONObject().put("query", q.query).put("words", JSONArray(q.words)))
        val o = JSONObject().put("key", s.key).put("at", s.at).put("queries", qs)
        s.urls?.let { o.put("urls", JSONArray(it)) }
        prefs.edit().putString(CACHE, o.toString()).apply()
    }

    private fun loadCache(): Saved? = try {
        val text = prefs.getString(CACHE, null)
        if (text == null) null else {
            val o = JSONObject(text)
            val qs = o.getJSONArray("queries")
            val queries = (0 until qs.length()).map { i ->
                val q = qs.getJSONObject(i)
                val w = q.getJSONArray("words")
                ClipQuery(q.getString("query"), (0 until w.length()).map { w.getString(it) })
            }
            val u = o.optJSONArray("urls")
            Saved(o.getString("key"), o.getLong("at"), queries, u?.let { a -> (0 until a.length()).map { a.getString(it) } })
        }
    } catch (e: Exception) { null }

    private companion object { const val CACHE = "cache" }
}
