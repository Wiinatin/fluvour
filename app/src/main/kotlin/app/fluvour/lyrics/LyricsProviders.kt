package app.fluvour.lyrics

import app.fluvour.media.SubLine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One lyric found by a provider, not yet checked against the video. [idKeyed]: found by the YouTube video id itself, so it is the same recording. */
class Cand(val source: String, val lines: List<SubLine>, val title: String, val artist: String, val durSec: Int, val idKeyed: Boolean) {
    val wordSynced: Boolean get() = LyricsParsers.wordSynced(lines)
}

/** What a provider needs to know about the video. */
class Query(val videoId: String, val guess: LyricsText.Guess, val videoSec: Int)

interface LyricsProvider {
    val name: String
    fun find(q: Query, http: LyricsHttp): List<Cand>
}

fun interface LyricsHttp { fun get(url: String): String? }

object PlainHttp : LyricsHttp {
    override fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Fluvour/1.0 (https://github.com/Wiinatin/fluvour)")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else null
    } catch (_: Exception) { null }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Community database keyed by YouTube video id; has richSync (word times) for many songs. */
object SimpMusicProvider : LyricsProvider {
    override val name = "SimpMusic"
    override fun find(q: Query, http: LyricsHttp): List<Cand> {
        val body = http.get("https://api-lyrics.simpmusic.org/v1/${enc(q.videoId)}") ?: return emptyList()
        val arr = try { JSONObject(body).optJSONArray("data") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Cand>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dur = o.optInt("duration", 0)
            if (q.videoSec > 0 && dur > 0 && LyricsText.delta(dur, q.videoSec) > 10) continue
            for (key in listOf("richSyncLyrics", "syncedLyrics")) {
                val t = o.optString(key, "")
                if (t.isBlank() || t == "null") continue
                val lines = LyricsParsers.parseLrc(t)
                if (lines.size >= 4) { out += Cand(name, lines, "", "", dur, true); break }
            }
        }
        return out
    }
}

/** lrclib.net: open database of synced lyrics; sometimes the synced text is enhanced LRC with word times. */
object LrclibProvider : LyricsProvider {
    override val name = "LRCLIB"
    private fun fromJson(o: JSONObject): Cand? {
        if (o.optBoolean("instrumental", false)) return null
        val t = o.optString("syncedLyrics", "")
        if (t.isBlank() || t == "null") return null
        val lines = LyricsParsers.parseLrc(t)
        if (lines.size < 4) return null
        return Cand(name, lines, o.optString("trackName", o.optString("name", "")), o.optString("artistName", ""), o.optDouble("duration", 0.0).toInt(), false)
    }

    override fun find(q: Query, http: LyricsHttp): List<Cand> {
        val out = ArrayList<Cand>()
        for (g in q.guess.all()) {
            if (g.title.isBlank()) continue
            val dur = if (q.videoSec > 0) "&duration=${q.videoSec}" else ""
            val body = http.get("https://lrclib.net/api/get?track_name=${enc(g.title)}&artist_name=${enc(g.artist)}$dur") ?: continue
            val c = try { fromJson(JSONObject(body)) } catch (_: Exception) { null } ?: continue
            out += c
        }
        if (out.isNotEmpty()) return out
        val g = q.guess
        val query = "${g.artist} ${g.title}".trim()
        val body = http.get("https://lrclib.net/api/search?q=${enc(query)}") ?: return out
        val arr = try { JSONArray(body) } catch (_: Exception) { return out }
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.optJSONObject(i) ?: continue
            fromJson(o)?.let { out += it }
        }
        return out
    }
}

/** Kugou: large catalogue (Chinese, Japanese, Korean and Western pop) with word-timed KRC lyrics. */
object KugouProvider : LyricsProvider {
    override val name = "Kugou"
    override fun find(q: Query, http: LyricsHttp): List<Cand> {
        val g = q.guess
        val kw = "${g.artist} - ${g.title}".trim(' ', '-')
        val ms = if (q.videoSec > 0) q.videoSec * 1000 else 0
        val body = http.get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(kw)}&duration=$ms&hash=") ?: return emptyList()
        val arr = try { JSONObject(body).optJSONArray("candidates") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Cand>()
        for (i in 0 until minOf(arr.length(), 5)) {
            val o = arr.optJSONObject(i) ?: continue
            val song = o.optString("song"); val singer = o.optString("singer"); val dur = o.optInt("duration", 0) / 1000
            if (!q.guess.all().any { LyricsText.accepts(song, singer, dur, it, q.videoSec) }) continue
            val id = o.optString("id"); val key = o.optString("accesskey")
            if (id.isBlank() || key.isBlank()) continue
            val dl = http.get("https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(key)}&fmt=krc&charset=utf8") ?: continue
            val content = try { JSONObject(dl).optString("content", "") } catch (_: Exception) { "" }
            if (content.isBlank()) continue
            val krc = LyricsParsers.krcDecode(content) ?: continue
            val lines = LyricsParsers.parseKrc(krc)
            if (lines.size >= 4) out += Cand(name, lines, song, singer, dur, false)
            if (out.size >= 2) break
        }
        return out
    }
}

/** BetterLyrics: Apple-style TTML with per-syllable times. Free only for songs already in its cache (others answer 401), so a miss is normal. */
object BetterLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"
    override fun find(q: Query, http: LyricsHttp): List<Cand> {
        val out = ArrayList<Cand>()
        for (g in q.guess.all()) {
            if (g.title.isBlank() || g.artist.isBlank()) continue
            val d = if (q.videoSec > 0) "&d=${q.videoSec}" else ""
            for (path in listOf("getLyrics", "ttml/getLyrics")) {
                val body = http.get("https://lyrics-api.boidu.dev/$path?s=${enc(g.title)}&a=${enc(g.artist)}$d") ?: continue
                val doc = unwrap(body) ?: continue
                val lines = LyricsParsers.parseTtml(doc)
                if (lines.size >= 4) { out += Cand(name, lines, g.title, g.artist, q.videoSec, false); return out }
            }
        }
        return out
    }

    private fun unwrap(body: String): String? {
        val t = body.trim()
        if (!t.startsWith("{")) return t.takeIf { "<tt" in it }
        return try { val o = JSONObject(t); o.optString("ttml", "").ifBlank { o.optString("lyrics", "") }.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }
}

/** LyricsPlus: syllable-timed lyrics from volunteer mirrors, so all mirrors are asked at once and the first real answer wins. */
object LyricsPlusProvider : LyricsProvider {
    override val name = "LyricsPlus"
    private val MIRRORS = listOf(
        "https://lyricsplus.prjktla.my.id", "https://lyricsplus.atomix.one", "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev", "https://lyricsplus-seven.vercel.app", "https://lyrics-plus-backend.vercel.app"
    )

    override fun find(q: Query, http: LyricsHttp): List<Cand> {
        val g = q.guess.all().firstOrNull { it.title.isNotBlank() && it.artist.isNotBlank() } ?: return emptyList()
        val dur = if (q.videoSec > 0) "&duration=${q.videoSec}" else ""
        val path = "/v2/lyrics/get?title=${enc(g.title)}&artist=${enc(g.artist)}$dur"
        val pool = java.util.concurrent.Executors.newFixedThreadPool(MIRRORS.size)
        try {
            val done = java.util.concurrent.ExecutorCompletionService<List<SubLine>?>(pool)
            for (m in MIRRORS) done.submit { parse(http.get(m + path)) }
            val end = System.currentTimeMillis() + 5000
            repeat(MIRRORS.size) {
                val left = end - System.currentTimeMillis()
                val f = done.poll(maxOf(left, 1), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return emptyList()
                val lines = try { f.get() } catch (_: Exception) { null }
                if (lines != null && lines.size >= 4) return listOf(Cand(name, lines, g.title, g.artist, q.videoSec, false))
            }
        } finally { pool.shutdownNow() }
        return emptyList()
    }

    private fun parse(body: String?): List<SubLine>? {
        if (body == null) return null
        return try {
            val arr = JSONObject(body).optJSONArray("lyrics") ?: return null
            val rows = ArrayList<Triple<Long, String, List<LyricsParsers.Syl>>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val start = o.optLong("time", -1); if (start < 0) continue
                val sy = o.optJSONArray("syllabus")
                val syl = ArrayList<LyricsParsers.Syl>()
                if (sy != null) for (j in 0 until sy.length()) {
                    val s = sy.optJSONObject(j) ?: continue
                    val t = s.optLong("time", -1); if (t < 0) continue
                    syl += LyricsParsers.Syl(t, s.optString("text", ""))
                }
                rows += Triple(start, o.optString("text", ""), syl)
            }
            LyricsParsers.fromRows(rows)
        } catch (_: Exception) { null }
    }
}
