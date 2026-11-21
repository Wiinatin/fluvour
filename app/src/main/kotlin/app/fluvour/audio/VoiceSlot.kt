package app.fluvour.audio

import java.io.File

/** One loaded voice model. [LocalVoice] wraps sherpa-onnx in this; tests use a fake. */
interface VoiceEngine {
    /** 0 when the model does not say (MMS); speaker 0 is then used. */
    val numSpeakers: Int
    /** Null when [shouldStop] asked to stop. [shouldStop] is checked between sentences, so a single long sentence still finishes. */
    fun generate(text: String, sid: Int, speed: Float, shouldStop: () -> Boolean): Pcm?
    fun free()
}

/**
 * Keeps at most one model loaded, loads it on first use, swaps it when another folder is asked for, and frees it on [release] or when it
 * has sat unused for [idleMs]. Pure (no Android, no sherpa), checked on a plain JVM in tools/voicetest. All calls are safe from any
 * thread; [synthesize] blocks, and other calls wait for it.
 */
class VoiceSlot(private val idleMs: Long, private val now: () -> Long, private val load: (File) -> VoiceEngine) {
    private val lock = Any()
    private var engine: VoiceEngine? = null
    private var lastUse = 0L
    @Volatile var loadedPath: String? = null
        private set

    /** Null when stopped by [shouldStop]. Throws when the model cannot be loaded or text is blank. */
    fun synthesize(dir: File, text: String, sid: Int = 0, speed: Float = 1f, shouldStop: () -> Boolean = { false }): Pcm? {
        require(text.isNotBlank()) { "nothing to say" }
        synchronized(lock) {
            try {
                val e = ensure(dir)
                val s = if (sid in 0 until e.numSpeakers) sid else 0
                return e.generate(text, s, speed, shouldStop)
            } finally { lastUse = now() }
        }
    }

    fun release() { synchronized(lock) { free() } }

    /** Frees the model when it has been unused for the idle time. True when it did. */
    fun releaseIfIdle(): Boolean = synchronized(lock) {
        if (engine != null && now() - lastUse >= idleMs) { free(); true } else false
    }

    private fun ensure(dir: File): VoiceEngine {
        val key = dir.absolutePath
        engine?.let { if (loadedPath == key) return it }
        free()
        return load(dir).also { engine = it; loadedPath = key }
    }

    private fun free() { val e = engine; engine = null; loadedPath = null; e?.free() }
}
