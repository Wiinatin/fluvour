package app.fluvour.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A finished recording: the file (AAC in an ADTS stream, plays and uploads as `audio/aac`) and its length. */
data class Recorded(val file: File, val durationSec: Int)

/**
 * Records one speaking attempt (Phase 5). Mono AAC_ADTS, 16 kHz, 48 kbps: about 0.35 MB a minute, which is what the Gemini upload
 * should be small enough for. One recording at a time, at most [MAX_SECONDS]; start, stop and discard, no pause.
 *
 * The caller owns the microphone permission (see `ui/MicPermission.kt`) and the target file. After a recording the file is the
 * caller's to keep or delete. [elapsedSec] drives the on-screen timer. When the limit is reached the recorder stops itself, keeps
 * the file, and shows it in [autoStopped] until the caller calls [takeAutoStopped].
 */
@Singleton
class SpeechRecorder @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var ticker: Job? = null

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec.asStateFlow()
    private val _autoStopped = MutableStateFlow<Recorded?>(null)
    /** Set when the time limit ended the recording. Read it, then call [takeAutoStopped]. */
    val autoStopped: StateFlow<Recorded?> = _autoStopped.asStateFlow()

    /** Starts recording into [target] (parent folders are created). False if it could not start; nothing is left behind then. */
    fun start(target: File): Boolean {
        if (rec != null) return false
        _autoStopped.value = null
        target.parentFile?.mkdirs()
        val r = newRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setMaxDuration(MAX_SECONDS * 1000)
            r.setOutputFile(target.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) scope.launch { limitReached() } }
            r.setOnErrorListener { _, _, _ -> scope.launch { discard() } }
            r.prepare()
            r.start()
            rec = r; file = target; startedAt = SystemClock.elapsedRealtime()
            _elapsedSec.value = 0; _recording.value = true
            ticker = scope.launch { while (true) { delay(250); _elapsedSec.value = seconds() } }
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            target.delete()
            rec = null; file = null; _recording.value = false
            false
        }
    }

    /** Stops and returns the recording, or null if it failed or was shorter than a second (the file is deleted then). */
    fun stop(): Recorded? = finish(keep = true)

    /** Stops and deletes the file. Safe to call when nothing is recording. */
    fun discard() { finish(keep = false) }

    /** Returns the recording that the time limit ended, once, and clears it. */
    fun takeAutoStopped(): Recorded? = _autoStopped.value.also { _autoStopped.value = null }

    private fun limitReached() {
        val r = finish(keep = true)
        if (r != null) _autoStopped.value = r
    }

    private fun finish(keep: Boolean): Recorded? {
        val r = rec ?: return null
        val f = file
        val secs = seconds()
        ticker?.cancel(); ticker = null
        rec = null; file = null; _recording.value = false
        val ok = try { r.stop(); true } catch (e: RuntimeException) { false } // stop() throws if no audio was captured yet
        runCatching { r.reset() }
        runCatching { r.release() }
        if (f == null) return null
        if (!keep || !ok || secs < MIN_SECONDS) { f.delete(); _elapsedSec.value = 0; return null }
        return Recorded(f, secs.coerceAtMost(MAX_SECONDS))
    }

    private fun seconds(): Int = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    companion object {
        const val MAX_SECONDS = 180
        const val MIN_SECONDS = 1
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 48000
    }
}
