package app.fluvour.extractor.api

/**
 * The contract between the app and a downloaded extractor plugin.
 *
 * Only plain types cross this line (String, Int, Boolean, List, Map), never NewPipe classes,
 * so a plugin built from any NewPipeExtractor release can be swapped in without touching the app.
 *
 * Bump [VERSION] only when something here changes in a way an older app or an older plugin cannot cope with.
 * The app refuses plugins built for another version, and the release workflow publishes per version.
 */
object ExtractorApi {
    const val VERSION = 2
}

class HttpRequest(val method: String, val url: String, val headers: Map<String, List<String>>, val body: ByteArray?)
class HttpResponse(val code: Int, val message: String, val headers: Map<String, List<String>>, val body: String, val finalUrl: String)

/** Web access, provided by the app. The extractor never opens connections itself. */
fun interface HttpFetcher { fun execute(request: HttpRequest): HttpResponse }

class VideoStream(val url: String, val height: Int, val isVideoOnly: Boolean)
class AudioStream(val url: String, val suffix: String?, val bitrate: Int)
class SubtitleTrack(val language: String, val suffix: String?, val isAuto: Boolean, val url: String)
class Thumbnail(val url: String, val height: Int)
class ExtractedMedia(
    val title: String, val uploader: String?,
    val videoStreams: List<VideoStream>, val audioStreams: List<AudioStream>, val subtitles: List<SubtitleTrack>,
    val durationSeconds: Long, val thumbnails: List<Thumbnail>
)
class SearchHit(val url: String, val title: String, val uploader: String?, val durationSeconds: Long, val thumbnails: List<Thumbnail>)

interface ExtractorBackend {
    /** The [ExtractorApi.VERSION] this backend was built against. */
    val apiVersion: Int
    /** The NewPipeExtractor release tag inside, e.g. "v0.26.5". */
    val extractorVersion: String
    fun init(fetcher: HttpFetcher)
    /** Throws when YouTube cannot be read (that is how the app knows the extractor needs an update). */
    fun extract(url: String): ExtractedMedia
    /** YouTube video search. Throws when YouTube cannot be read. */
    fun search(query: String): List<SearchHit>
}
