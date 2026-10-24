package app.fluvour.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Speaks words and sentences with Android's built-in voice engine. No network, no keys. */
@Singleton
class TtsManager @Inject constructor(@ApplicationContext private val ctx: Context) {
    enum class Result { OK, NO_VOICE, NOT_READY }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: (() -> Unit)? = null
    @Volatile private var current: String? = null
    private var counter = 0

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            // The video fades down while this speaks. Only the latest utterance may end the duck (QUEUE_FLUSH stops the old one after the new one starts).
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                private fun finished(id: String?) { if (id == current) Ducker.set("tts", false) }
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
            if (ready) { queued?.invoke() }
            queued = null
        }
    }

    /** langTag is a BCP-47 code such as "ja" or "th". NOT_READY means the engine is still starting; the text plays once it is. */
    fun speak(text: String, langTag: String): Result {
        if (text.isBlank()) return Result.OK
        ensure()
        val engine = tts ?: return Result.NOT_READY
        if (!ready) { queued = { speak(text, langTag) }; return Result.NOT_READY }
        val avail = engine.setLanguage(Locale.forLanguageTag(langTag))
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) return Result.NO_VOICE
        val id = "fluvour-${++counter}"; current = id
        Ducker.set("tts", true)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) Ducker.set("tts", false)
        return Result.OK
    }

    fun stop() { tts?.stop(); current = null; Ducker.set("tts", false) }
}
