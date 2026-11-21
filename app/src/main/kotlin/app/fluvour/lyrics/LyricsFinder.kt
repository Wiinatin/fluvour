package app.fluvour.lyrics

import app.fluvour.media.SubLine
import app.fluvour.media.SubWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A lyric that passed every check. */
class Found(val source: String, val lines: List<SubLine>, val wordSynced: Boolean)

/** Switch for the finder, set from Settings (same pattern as ArtPrefs). */
object LyricsPrefs { @Volatile var enabled: Boolean = true }

/**
 * Looks for word-synced lyrics for a video in several places at once and keeps the best one that is verifiably the same song.
 * Order of preference: verified word-sync from anywhere, then (only if YouTube has no lines) verified line-sync.
 */
class LyricsFinder(
    private val cacheDir: File?,
    private val http: LyricsHttp = PlainHttp,
    private val providers: List<LyricsProvider> = listOf(SimpMusicProvider, BetterLyricsProvider, LyricsPlusProvider, KugouProvider, LrclibProvider),
    private val deadlineMs: Long = 6000,
    private val now: () -> Long = System::currentTimeMillis
) {
    /**
     * [ref]: the lines YouTube already has (may be empty). Returns every lyric that passed the checks, best first: word-synced ones in
     * provider order, then line-synced ones. Empty when nothing trustworthy was found.
     */
    fun findAll(videoId: String, rawTitle: String, uploader: String?, videoSec: Int, lang: String, ref: List<SubLine>): List<Found> {
        val cacheFile = cacheDir?.let { File(it, "lyrics2-${videoId.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }}-${lang.substringBefore('-')}.json") }
        cacheFile?.let { f -> readCache(f)?.let { return it } }
        val q = Query(videoId, LyricsText.guess(rawTitle, uploader), videoSec)
        if (q.guess.title.isBlank()) return emptyList()
        val pool = Executors.newFixedThreadPool(providers.size)
        val verified = ArrayList<Pair<Int, Found>>()
        var complete = true
        try {
            val futures = providers.map { p -> pool.submit(Callable { runCatching { p.find(q, http) }.getOrDefault(emptyList()) }) }
            var end = now() + deadlineMs
            for ((i, f) in futures.withIndex()) {
                val left = end - now()
                val cands = try { f.get(maxOf(left, 1), TimeUnit.MILLISECONDS) } catch (_: Exception) { complete = false; f.cancel(true); emptyList() }
                for (c in cands) verify(c, q, lang, ref)?.let { verified += i to it }
                // a word-synced answer is enough to show; the others get a short grace period to fill the source picker
                if (verified.any { it.second.wordSynced }) end = minOf(end, now() + 1500)
            }
        } finally { pool.shutdownNow() }
        val result = verified.sortedWith(compareBy({ !it.second.wordSynced }, { it.first })).map { it.second }
            .distinctBy { it.source }
        // A timeout is not an answer: do not remember "nothing" for a result that never arrived.
        if (cacheFile != null && (result.isNotEmpty() || complete)) writeCache(cacheFile, result)
        return result
    }

    private fun verify(c: Cand, q: Query, lang: String, ref: List<SubLine>): Found? {
        if (!c.idKeyed && q.guess.all().none { LyricsText.accepts(c.title, c.artist, c.durSec, it, q.videoSec) }) return null
        if (!LyricsText.scriptFits(lang, c.lines) || !LyricsText.durationFits(c.lines, q.videoSec * 1000L)) return null
        var lines = c.lines
        if (ref.isNotEmpty()) {
            val a = LyricsText.agreement(lines, ref)
            val enough = a.pairs >= 4 && a.ratio >= 0.25
            if (!enough && !c.idKeyed) return null
            if (!enough && c.idKeyed && a.pairs >= 2 && a.spreadMs > 4000) return null
            if (a.pairs >= 4 && a.spreadMs <= 1500 && kotlin.math.abs(a.offsetMs) in 350..40_000) lines = shift(lines, a.offsetMs)
            else if (a.pairs >= 4 && a.spreadMs > 3000) return null
        } else if (!c.idKeyed && LyricsText.delta(c.durSec, q.videoSec) > 8) return null
        return Found(c.source, lines, LyricsParsers.wordSynced(lines))
    }

    private fun shift(lines: List<SubLine>, by: Long) = LyricsParsers.shift(lines, by)

    // Cache: found lyrics keep 60 days, "nothing found" 3 days ---------------------------------------------------------------------
    private fun readCache(f: File): List<Found>? = try {
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val age = now() - o.optLong("at", 0)
            if (o.optBoolean("none", false)) { if (age < 3L * 86_400_000) emptyList() else null }
            else if (age < 60L * 86_400_000) {
                val items = o.getJSONArray("items")
                (0 until items.length()).map { k ->
                    val it = items.getJSONObject(k)
                    val arr = it.getJSONArray("lines")
                    val lines = (0 until arr.length()).map { i ->
                        val l = arr.getJSONArray(i)
                        val w = l.getJSONArray(3)
                        SubLine(l.getLong(0), l.getLong(1), l.getString(2), (0 until w.length()).map { j -> SubWord(w.getJSONArray(j).getLong(0), w.getJSONArray(j).getString(1)) })
                    }
                    Found(it.getString("source"), lines, it.getBoolean("word"))
                }
            } else null
        }
    } catch (_: Exception) { null }

    private fun writeCache(f: File, r: List<Found>) {
        try {
            val o = JSONObject().put("at", now())
            if (r.isEmpty()) o.put("none", true) else {
                val items = JSONArray()
                for (x in r) {
                    val arr = JSONArray()
                    for (l in x.lines) arr.put(JSONArray().put(l.startMs).put(l.endMs).put(l.text).put(JSONArray().also { w -> l.words.forEach { w.put(JSONArray().put(it.startMs).put(it.text)) } }))
                    items.put(JSONObject().put("source", x.source).put("word", x.wordSynced).put("lines", arr))
                }
                o.put("items", items)
            }
            f.parentFile?.mkdirs(); f.writeText(o.toString())
        } catch (_: Exception) {}
    }
}
