package app.fluvour.audio

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks with a voice pack that is already on the phone (a folder with a Kokoro, Piper, MMS or Mimic model), through sherpa-onnx.
 * It makes sound only; nothing here decides when a pack is used (that is TtsManager).
 *
 * - The model loads on first use and stays loaded; asking for another folder replaces it (one at a time, see [VoiceSlot]).
 * - It is freed after a minute without use, and when Android reports low memory or the app moves to the background cache.
 * - [synthesizeAsync] runs off the main thread and stops between sentences when its coroutine is cancelled.
 *   [synthesize] blocks, so call it off the main thread.
 */
@Singleton
class LocalVoice @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val slot = VoiceSlot(IDLE_MS, { System.nanoTime() / 1_000_000 }) { dir -> SherpaEngine(load(dir)) }
    private var idleJob: Job? = null

    init {
        ctx.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) scope.launch { slot.release() }     // off the main thread: it waits for a running synthesis
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() { scope.launch { slot.release() } }
        })
    }

    /** Folder of the model that is loaded now, or null. */
    val loadedPath: String? get() = slot.loadedPath

    /** Makes the sound and returns it. Blocks. Null only when [shouldStop] said stop. */
    fun synthesize(dir: File, text: String, sid: Int = 0, speed: Float = 1f, shouldStop: () -> Boolean = { false }): Pcm? {
        synchronized(this) { idleJob?.cancel(); idleJob = null }
        try { return slot.synthesize(dir, text, sid, speed, shouldStop) }
        finally { synchronized(this) { idleJob = scope.launch { delay(IDLE_MS + 1_000); slot.releaseIfIdle() } } }
    }

    /** Same, off the main thread. Cancelling the coroutine stops the sound being made at the next sentence end and throws CancellationException. */
    suspend fun synthesizeAsync(dir: File, text: String, sid: Int = 0, speed: Float = 1f): Pcm = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()
        synthesize(dir, text, sid, speed) { !job.isActive } ?: run { ensureActive(); error("stopped") }
    }

    /** Frees the model (about 100 MB for Kokoro). The next [synthesize] loads it again. */
    fun release() = slot.release()

    /**
     * The callback sherpa calls between sentences. It is a named class on purpose: sherpa's native code looks up `invoke([F)Ljava/lang/Integer;`,
     * and a lambda is compiled by D8 into a synthetic class without that method, which aborts the app (JNI NoSuchMethodError). Returning 0 stops.
     */
    private class StopCheck(private val shouldStop: () -> Boolean) : (FloatArray) -> Int {
        @Volatile var stopped = false
        override fun invoke(samples: FloatArray): Int = if (shouldStop()) { stopped = true; 0 } else 1
    }

    private class SherpaEngine(private val tts: OfflineTts) : VoiceEngine {
        override val numSpeakers: Int get() = tts.numSpeakers()
        override fun generate(text: String, sid: Int, speed: Float, shouldStop: () -> Boolean): Pcm? {
            val cb = StopCheck(shouldStop)
            val a = tts.generateWithCallback(text, sid, speed, cb)
            return if (cb.stopped) null else Pcm(a.samples, a.sampleRate)
        }
        override fun free() = tts.free()
    }

    private fun load(dir: File): OfflineTts {
        val layout = ModelLayout.detect(dir.list()?.toList().orEmpty()) ?: error("No voice model in ${dir.name} (needs tokens.txt and a .onnx file).")
        return OfflineTts(config = config(dir, layout))
    }

    private fun config(dir: File, l: ModelLayout): OfflineTtsConfig {
        fun p(name: String) = if (name.isEmpty()) "" else File(dir, name).absolutePath
        val model = when (l.kind) {
            ModelLayout.Kind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = p(l.model), voices = p(l.voices), tokens = p(l.tokens), dataDir = p(l.dataDir),
                    lexicon = l.lexicons.joinToString(",") { p(it) }, dictDir = p(l.dictDir),
                ),
                numThreads = 4, provider = "cpu",
            )
            ModelLayout.Kind.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = p(l.model), tokens = p(l.tokens), dataDir = p(l.dataDir), lexicon = l.lexicons.firstOrNull()?.let { p(it) }.orEmpty()),
                numThreads = 2, provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = model, ruleFsts = l.ruleFsts.joinToString(",") { p(it) })
    }

    // ---- debug test (Settings, debug builds only) ----

    /** Folder the test reads: Android/data/app.fluvour/files/voicetest. Put a model folder in it (see [debugSpeak]). */
    fun testDir(): File = File(ctx.getExternalFilesDir(null), "voicetest").also { it.mkdirs() }

    /**
     * Speaks one sentence with the model found in [testDir] and says how long it took. The model folder may sit directly in it or be its
     * only subfolder. Optional files in [testDir]: `say.txt` (the sentence, first line) and `sid.txt` (the speaker number).
     */
    suspend fun debugSpeak(): String = withContext(Dispatchers.Default) {
        runCatching {
            val root = testDir()
            val dir = if (ModelLayout.detect(root.list()?.toList().orEmpty()) != null) root
                else root.listFiles()?.filter { it.isDirectory }?.singleOrNull() ?: error("Put one model folder in ${root.absolutePath}")
            val text = File(root, "say.txt").takeIf { it.exists() }?.readLines()?.firstOrNull()?.trim()?.ifEmpty { null } ?: "This morning I woke up very early."
            val sid = File(root, "sid.txt").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
            val t0 = System.nanoTime()
            val r = synthesize(dir, text, sid) ?: error("stopped")
            val secs = (System.nanoTime() - t0) / 1e9
            val wav = File(ctx.cacheDir, "localvoice-test.wav").also { it.writeBytes(wav16(r)) }
            withContext(Dispatchers.Main) { playOnce(wav) }
            "${dir.name}: %.1f s of sound made in %.1f s, %d Hz (the first run includes loading the model)".format(r.samples.size / r.sampleRate.toFloat(), secs, r.sampleRate)
        }.getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun playOnce(f: File) {
        val p = MediaPlayer()
        p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
        p.setDataSource(f.path)
        p.setOnPreparedListener { it.start() }
        p.setOnCompletionListener { it.release() }
        p.setOnErrorListener { m, _, _ -> m.release(); true }
        p.prepareAsync()
    }

    companion object {
        const val IDLE_MS = 60_000L

        /** 16-bit mono WAV from float samples (the rate comes from the model, it is not always 24000). */
        fun wav16(pcm: Pcm): ByteArray = pcm.toWav()
    }
}
