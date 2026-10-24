package app.fluvour.media

import app.fluvour.extractor.api.HttpRequest
import app.fluvour.extractor.api.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One search result card: enough to show it and to open it later. */
data class VideoHit(val url: String, val title: String, val uploader: String?, val durationMs: Long, val thumbUrl: String?)

/**
 * YouTube through NewPipeExtractor (via ExtractorManager, which keeps the extractor up to date by itself):
 * real stream URLs and subtitle tracks, played in Fluvour's own player. Personal use only (see the decision record in the roadmap).
 */
@Singleton
class YouTubeSource @Inject constructor(private val extractor: ExtractorManager) : MediaSource {

    override suspend fun load(input: String, lang: String): Result<Playable> = withContext(Dispatchers.IO) {
        withUpdateRetry { attempt(input, lang) }
    }

    /** Video search through NewPipe (no API key). Uses YouTube's unofficial interfaces, so it can break like the stream loading does. */
    suspend fun search(query: String): Result<List<VideoHit>> = withContext(Dispatchers.IO) {
        withUpdateRetry {
            try {
                Result.success(extractor.backend().search(query).map { v ->
                    // a mid-size thumbnail is plenty for a 112 dp card
                    val thumb = v.thumbnails.filter { it.height >= 180 }.minByOrNull { it.height }?.url ?: v.thumbnails.lastOrNull()?.url
                    VideoHit(v.url, v.title, v.uploader, v.durationSeconds.coerceAtLeast(0L) * 1000L, thumb)
                })
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    /** YouTube changes are what break extractors. Before giving up, look for a newer one and try once more. */
    private suspend fun <T> withUpdateRetry(block: () -> Result<T>): Result<T> {
        val first = block()
        if (first.isSuccess) return first
        return if (extractor.checkForUpdate(force = true) is UpdateResult.Updated) block() else first
    }

    private fun attempt(input: String, lang: String): Result<Playable> {
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
            val tracks = info.subtitles.filter { it.language.substringBefore('-').lowercase() == want && it.suffix?.lowercase() in order }
                .sortedWith(compareBy({ it.isAuto }, { order.indexOf(it.suffix?.lowercase()) }))
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
            } else if (used.isAuto) note = "Automatic subtitles: they can contain mistakes."
            val thumb = info.thumbnails.maxByOrNull { it.height }?.url
            return Result.success(Playable(info.title, info.uploader, stream.url, lines, note, url, audioUrl = audio?.url, thumbUrl = thumb, durationMs = info.durationSeconds.coerceAtLeast(0L) * 1000L))
        } catch (e: Exception) { return Result.failure(e) }
    }
}
