package app.fluvour.media

/** One subtitle line. Times are in milliseconds from the start of the video. */
data class SubLine(val startMs: Long, val endMs: Long, val text: String)

/** Everything the player screen needs. It does not know where this came from (YouTube, a local file, audio). */
data class Playable(
    val title: String, val uploader: String?, val streamUrl: String, val lines: List<SubLine>,
    val subtitleNote: String?, val sourceUrl: String,
    /** Sound-only stream, if the source has one: used for audio-only mode and the waveform. */
    val audioUrl: String? = null,
    val thumbUrl: String? = null, val durationMs: Long = 0L
)

/** The player talks only to this. YouTube is one implementation; local files and audio can be others. */
interface MediaSource {
    suspend fun load(input: String, lang: String): Result<Playable>
}
