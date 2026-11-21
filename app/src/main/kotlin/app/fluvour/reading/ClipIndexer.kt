package app.fluvour.reading

import app.fluvour.data.AnalyzedVideoEntity
import app.fluvour.data.WordCountsJson
import app.fluvour.media.VideoHit
import app.fluvour.media.YouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads one video's subtitles and turns them into a library row. Shared by the clips list (SuggestViewModel) and the background
 * library job (ClipIndexWorker), so both measure exactly the same way. It does not save anything: the caller saves the row.
 */
@Singleton
class ClipIndexer @Inject constructor(private val source: YouTubeSource) {

    /**
     * [related] are the videos YouTube lists next to this one (only filled when the row has subtitles).
     * [row] is null when the video could not be opened ([error] says why). A video that opened but has no readable subtitles
     * gives a row with `noSubtitles` set, so it can be saved and never read again.
     */
    class Outcome(val row: AnalyzedVideoEntity?, val error: String?, val related: List<VideoHit> = emptyList()) { val failed: Boolean get() = row == null }

    suspend fun measure(h: VideoHit, lang: String, table: LevelTable?): Outcome {
        val subs = source.subtitlesOnly(h.url, lang).getOrElse { return Outcome(null, it.message ?: it.javaClass.simpleName) }
            ?: return Outcome(noSubtitles(h, lang, h.title, h.uploader, h.durationMs, h.thumbUrl), null)
        val counts = withContext(Dispatchers.Default) { ClipMeasurer.count(subs.texts, lang) }
        val title = subs.title.ifBlank { h.title }
        val uploader = subs.uploader ?: h.uploader
        val durationMs = subs.durationMs.takeIf { it > 0 } ?: h.durationMs
        val thumb = subs.thumbUrl ?: h.thumbUrl
        if (counts.tokenTotal == 0) return Outcome(noSubtitles(h, lang, title, uploader, durationMs, thumb), null)
        return Outcome(
            AnalyzedVideoEntity(
                url = h.url, lang = lang, title = title, uploader = uploader, durationMs = durationMs, thumbUrl = thumb,
                analyzedAt = System.currentTimeMillis(), subtitleKind = if (subs.auto) "auto" else "manual",
                tokenTotal = counts.tokenTotal, uniqueCount = counts.uniqueCount,
                levelCode = ClipMeasurer.levelCode(counts, lang, table), wordCounts = WordCountsJson.encode(counts.counts)
            ), null, subs.related
        )
    }

    private fun noSubtitles(h: VideoHit, lang: String, title: String, uploader: String?, durationMs: Long, thumb: String?) = AnalyzedVideoEntity(
        url = h.url, lang = lang, title = title, uploader = uploader, durationMs = durationMs, thumbUrl = thumb,
        analyzedAt = System.currentTimeMillis(), subtitleKind = "none", tokenTotal = 0, uniqueCount = 0, levelCode = null,
        wordCounts = "{}", noSubtitles = true
    )
}
