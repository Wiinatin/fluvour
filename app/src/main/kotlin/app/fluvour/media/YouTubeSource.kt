package app.fluvour.media

import app.fluvour.extractor.api.HttpRequest
import app.fluvour.extractor.api.SubtitleTrack
import app.fluvour.reading.ClipMeasurer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One search result card: enough to show it and to open it later. */
data class VideoHit(val url: String, val title: String, val uploader: String?, val durationMs: Long, val thumbUrl: String?)

/**
 * What clip recommendations need from one video (roadmap 4.2): its details and the subtitle text, nothing else.
 * [texts] has one entry per subtitle line and is already free of auto-caption repeats, so counting words from it is safe.
 */
class ClipSubtitles(val title: String, val uploader: String?, val durationMs: Long, val thumbUrl: String?, val auto: Boolean, val texts: List<String>,
    /** Videos YouTube lists next to this one (empty if none). Candidates for the background library. */
    val related: List<VideoHit> = emptyList())

/**
 * YouTube through NewPipeExtractor (via ExtractorManager, which keeps the extractor up to date by itself):
 * real stream URLs and subtitle tracks, played in Fluvour's own player. Personal use only (see the decision record in the roadmap).
 */
@Singleton
class YouTubeSource @Inject constructor(private val extractor: ExtractorManager, @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context) : MediaSource {

    init { YouTubeGuard.attach(context) }

    override suspend fun load(input: String, lang: String, nativeLang: String?): Result<Playable> = withContext(Dispatchers.IO) {
        withUpdateRetry(lookupKey(input, lang, nativeLang), LOOKUP_CACHE_MS) { attempt(input, lang, nativeLang) }
    }

    /** True when this video's lookup is saved and fresh, so loading it sends no request. */
    fun isLookupCached(input: String, lang: String, nativeLang: String?) = YouTubeGuard.isCached(lookupKey(input, lang, nativeLang), LOOKUP_CACHE_MS)

    /** Forgets the saved lookup of one video (its stream address failed to play), so the next [load] asks YouTube again. */
    fun forgetLookup(input: String, lang: String, nativeLang: String?) = YouTubeGuard.forget(lookupKey(input, lang, nativeLang))

    /** Video search through NewPipe (no API key). Uses YouTube's unofficial interfaces, so it can break like the stream loading does. */
    suspend fun search(query: String): Result<List<VideoHit>> = withContext(Dispatchers.IO) {
        withUpdateRetry("s:" + query.trim().lowercase()) {
            try {
                Result.success(extractor.backend().search(query).map { v ->
                    // a mid-size thumbnail is plenty for a 112 dp card
                    val thumb = v.thumbnails.filter { it.height >= 180 }.minByOrNull { it.height }?.url ?: v.thumbnails.lastOrNull()?.url
                    VideoHit(v.url, v.title, v.uploader, v.durationSeconds.coerceAtLeast(0L) * 1000L, thumb)
                })
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    /**
     * Reads only what the clip list needs: no stream choice, no second language, no Playable. The extractor still opens the whole video page
     * (the backend has no lighter call), so this costs about one normal load. Null inside the result = the video opened but has no readable
     * subtitles in [lang]; a failure = YouTube or the network did not answer.
     * Auto captions are tried as json3 first (the other formats repeat lines in a rolling window) and are de-duplicated before they are returned.
     */
    suspend fun subtitlesOnly(url: String, lang: String): Result<ClipSubtitles?> = withContext(Dispatchers.IO) {
        withUpdateRetryThrottled("v:" + url.trim()) { readSubtitles(url, lang) }
    }

    /** Like [withUpdateRetry], but a forced update check runs at most once every few minutes, so a batch of broken videos does not trigger one per video. */
    @Volatile private var lastForcedCheck = 0L
    private suspend fun <T> withUpdateRetryThrottled(key: String?, block: () -> Result<T>): Result<T> {
        val first = YouTubeGuard.run(key, block)
        if (first.isSuccess || YouTubeGuard.isBlock(first.exceptionOrNull()) || first.exceptionOrNull() is YouTubeCooldownException) return first
        val now = System.currentTimeMillis()
        if (now - lastForcedCheck < UPDATE_CHECK_GAP_MS) return first
        lastForcedCheck = now
        return if (extractor.checkForUpdate(force = true) is UpdateResult.Updated) YouTubeGuard.run(key, block) else first
    }

    private fun readSubtitles(input: String, lang: String): Result<ClipSubtitles?> {
        try {
            val info = extractor.backend().extract(input.trim())
            val want = lang.substringBefore('-').lowercase()
            val tracks = info.subtitles.filter { it.language.substringBefore('-').lowercase() == want && it.suffix?.lowercase() in FORMATS_MANUAL }
                .sortedWith(compareBy({ it.isAuto }, { (if (it.isAuto) FORMATS_AUTO else FORMATS_MANUAL).indexOf(it.suffix?.lowercase()) }))
            val thumb = info.thumbnails.filter { it.height >= 180 }.minByOrNull { it.height }?.url ?: info.thumbnails.lastOrNull()?.url
            val durationMs = info.durationSeconds.coerceAtLeast(0L) * 1000L
            val join = if (want in NO_SPACE_LANGS) "" else " "
            for (t in tracks.take(2)) {   // the best track, and one fallback if it cannot be read
                val body = runCatching { AppHttpFetcher.execute(HttpRequest("GET", t.url, emptyMap(), null)).body }.getOrNull() ?: continue
                val parsed = runCatching { SubtitleParser.parse(body) }.getOrDefault(emptyList())
                if (parsed.isEmpty()) continue
                val raw = parsed.map { it.text.replace(LINE_BREAK, join).trim() }
                val texts = if (t.isAuto) ClipMeasurer.dedupeRolling(raw) else raw.filter { it.isNotEmpty() }
                if (texts.isEmpty()) continue
                val related = info.related.map { v ->
                    val th = v.thumbnails.filter { it.height >= 180 }.minByOrNull { it.height }?.url ?: v.thumbnails.lastOrNull()?.url
                    VideoHit(v.url, v.title, v.uploader, v.durationSeconds.coerceAtLeast(0L) * 1000L, th)
                }
                return Result.success(ClipSubtitles(info.title, info.uploader, durationMs, thumb, t.isAuto, texts, related))
            }
            return Result.success(null)
        } catch (e: Exception) { return Result.failure(e) }
    }

    /** YouTube changes are what break extractors. Before giving up, look for a newer one and try once more. */
    private suspend fun <T> withUpdateRetry(key: String?, ttlMs: Long = 20L * 60_000, block: () -> Result<T>): Result<T> {
        val first = YouTubeGuard.run(key, block, ttlMs)
        // A bot check or cooldown is not an extractor bug: a newer extractor cannot fix it, and a second request only makes it worse.
        if (first.isSuccess || YouTubeGuard.isBlock(first.exceptionOrNull()) || first.exceptionOrNull() is YouTubeCooldownException) return first
        return if (extractor.checkForUpdate(force = true) is UpdateResult.Updated) YouTubeGuard.run(key, block, ttlMs) else first
    }

    private fun attempt(input: String, lang: String, nativeLang: String?): Result<Playable> {
        try {
            val url = input.trim()
            val info = extractor.backend().extract(url)

            // Video with sound in one stream, the best one up to 720p (bigger files buffer slowly on a phone).
            val streams = info.videoStreams.filter { !it.isVideoOnly }
            val stream = streams.filter { it.height in 1..720 }.maxByOrNull { it.height } ?: streams.firstOrNull()
                ?: return Result.failure(Exception("No playable stream found for this video."))

            // Sound-only stream for audio-only mode and the waveform: m4a (AAC) first because every phone decodes it.
            val audios = info.audioStreams
            android.util.Log.d("FluvourAudio", "extractor=${extractor.version()}, audioStreams usable=${audios.size}, videoStreams=${info.videoStreams.size}")
            val audio = audios.filter { it.suffix?.lowercase() == "m4a" }.maxByOrNull { it.bitrate } ?: audios.maxByOrNull { it.bitrate }

            // Subtitles in the learning language: written ones first, then automatic; VTT, then SRT.
            val want = lang.substringBefore('-').lowercase()
            val order = listOf("vtt", "srt", "ttml", "srv3", "json3", "srv1", "srv2", "sbv", "ass", "ssa", "lrc")
            // automatic captions: srv3 first (then json3), they say when each word is spoken
            val tracks = info.subtitles.filter { it.language.substringBefore('-').lowercase() == want && it.suffix?.lowercase() in order }
                .sortedWith(compareBy({ it.isAuto }, { (if (it.isAuto) FORMATS_AUTO else order).indexOf(it.suffix?.lowercase()) }))
            var lines = emptyList<SubLine>()
            var note: String? = null
            var used: SubtitleTrack? = null
            // Try the best track first; if it cannot be read, try the next one.
            for (t in tracks) {
                val parsed = SubtitleParser.parse(AppHttpFetcher.execute(HttpRequest("GET", t.url, emptyMap(), null)).body)
                if (parsed.isNotEmpty()) { lines = parsed; used = t; break }
            }
            if (used == null) {
                val all = info.subtitles.joinToString { "${it.language} (${it.suffix ?: "?"}${if (it.isAuto) ", auto" else ""})" }
                note = when {
                    info.subtitles.isEmpty() -> "YouTube returned no subtitle tracks for this video."
                    tracks.isEmpty() -> "No readable subtitles for \"$lang\". Tracks found: $all"
                    else -> "Found ${tracks.size} subtitle track(s) for \"$lang\" but could not read them (${tracks.joinToString { it.suffix ?: "?" }})."
                }
            } else if (used.isAuto) note = "Automatic subtitles: they can contain mistakes." + wordTimeProbe(info.subtitles, want, used)
            // Subtitles in the person's own language, for the bilingual switch. A failure here never stops the video.
            var translation = emptyList<SubLine>()
            val native = nativeLang?.substringBefore('-')?.lowercase()
            if (lines.isNotEmpty() && native != null && native != want) {
                val nTracks = info.subtitles.filter { it.language.substringBefore('-').lowercase() == native && it.suffix?.lowercase() in order }
                    .sortedWith(compareBy({ it.isAuto }, { order.indexOf(it.suffix?.lowercase()) }))
                for (t in nTracks.take(2)) {
                    val parsed = runCatching { SubtitleParser.parse(AppHttpFetcher.execute(HttpRequest("GET", t.url, emptyMap(), null)).body) }.getOrDefault(emptyList())
                    if (parsed.isNotEmpty()) { translation = parsed; break }
                }
            }
            val thumb = info.thumbnails.maxByOrNull { it.height }?.url
            return Result.success(Playable(info.title, info.uploader, stream.url, lines, note, url, audioUrl = audio?.url, thumbUrl = thumb, durationMs = info.durationSeconds.coerceAtLeast(0L) * 1000L, translation = translation))
        } catch (e: Exception) { return Result.failure(e) }
    }

    /**
     * TEST ONLY (word-timing handoff, step 2): asks for the automatic captions as srv3 and says whether they carry a start time for each word.
     * The answer goes to the log (tag FluvourWords) and to the end of the subtitle note on screen. Remove it in step 4.
     */
    private fun wordTimeProbe(all: List<SubtitleTrack>, want: String, used: SubtitleTrack): String {
        val srv3 = all.firstOrNull { it.isAuto && it.suffix?.lowercase() == "srv3" && it.language.substringBefore('-').lowercase() == want }
        val url = srv3?.url ?: used.url.replace(Regex("([?&])fmt=[^&]*"), "\$1fmt=srv3")
        val from = if (srv3 != null) "extractor" else "rewritten url"
        return try {
            val r = AppHttpFetcher.execute(HttpRequest("GET", url, emptyMap(), null))
            val lines = Regex("<p\\b").findAll(r.body).count()
            val timed = Regex("<s\\b[^>]*\\bt=\"").findAll(r.body).count()
            android.util.Log.d("FluvourWords", "srv3 ($from): http ${r.code}, ${r.body.length} chars, $lines lines, $timed timed words")
            if (timed > 0) " Word times: yes ($timed)." else " Word times: no (http ${r.code}, $lines lines)."
        } catch (e: Exception) {
            android.util.Log.d("FluvourWords", "srv3 check failed: ${e.message}")
            " Word times: check failed."
        }
    }

    private companion object {
        /** Saved video lookups are reused for 2 hours (stream addresses normally last about 6; a failing one is retried fresh by the Review screen). */
        const val LOOKUP_CACHE_MS = 2L * 60 * 60_000
        fun lookupKey(input: String, lang: String, nativeLang: String?) = "l:" + input.trim() + "|" + lang + "|" + (nativeLang ?: "")
        const val UPDATE_CHECK_GAP_MS = 10 * 60_000L
        /** Same order as [attempt]. Auto captions put srv3 first, then json3: both say when each word is spoken. */
        val FORMATS_MANUAL = listOf("vtt", "srt", "ttml", "srv3", "json3", "srv1", "srv2", "sbv", "ass", "ssa", "lrc")
        val FORMATS_AUTO = listOf("srv3", "json3") + FORMATS_MANUAL.filter { it != "srv3" && it != "json3" }
        val NO_SPACE_LANGS = setOf("ja", "zh", "th")
        val LINE_BREAK = Regex("\\s*\\n\\s*")
    }
}
