package app.fluvour.media

/** One timed piece of a subtitle line: when it starts (ms from the start of the video) and its text. It ends where the next piece starts. */
data class SubWord(val startMs: Long, val text: String)

/**
 * One subtitle line. Times are in milliseconds from the start of the video. [words] is only filled when the source says when each piece
 * of the line is spoken (YouTube's automatic captions in json3); otherwise it is empty.
 */
data class SubLine(val startMs: Long, val endMs: Long, val text: String, val words: List<SubWord> = emptyList())

/** Everything the player screen needs. It does not know where this came from (YouTube, a local file, audio). */
data class Playable(
    val title: String, val uploader: String?, val streamUrl: String, val lines: List<SubLine>,
    val subtitleNote: String?, val sourceUrl: String,
    /** Sound-only stream, if the source has one: used for audio-only mode and the waveform. */
    val audioUrl: String? = null,
    val thumbUrl: String? = null, val durationMs: Long = 0L,
    /** Subtitles in the person's own language, if the video has them (for the bilingual switch). */
    val translation: List<SubLine> = emptyList()
)

/** The player talks only to this. YouTube is one implementation; local files and audio can be others. */
interface MediaSource {
    suspend fun load(input: String, lang: String, nativeLang: String? = null): Result<Playable>
}
