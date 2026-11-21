package app.fluvour.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One spoken phrase to text, with the phone's own speech recognition (shadowing). Use from the main thread.
 * `start` listens until you stop talking, then calls back once with the text or an error code. `stop` ends listening early
 * (the result still arrives). `cancel` drops everything: no callback after it. Nothing is recorded or saved.
 */
class SpeechListener(private val ctx: Context) {
    sealed interface Out {
        data class Text(val text: String) : Out
        /** One of the SpeechRecognizer.ERROR_* codes. */
        data class Fail(val code: Int) : Out
    }

    private var rec: SpeechRecognizer? = null
    private var gen = 0

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(lang: String, onOut: (Out) -> Unit) {
        cancel()
        val mine = ++gen
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        fun done(out: Out) {
            if (mine != gen) return          // cancelled or replaced meanwhile
            rec = null; runCatching { r.destroy() }
            onOut(out)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                done(if (t.isNullOrBlank()) Out.Fail(SpeechRecognizer.ERROR_NO_MATCH) else Out.Text(t))
            }
            override fun onError(error: Int) = done(Out.Fail(error))
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag(lang))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // a short line: do not wait long after you stop talking (the recogniser may treat these as hints only)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        })
    }

    fun stop() { rec?.let { runCatching { it.stopListening() } } }

    fun cancel() {
        gen++
        rec?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        rec = null
    }

    private fun tag(lang: String) = when (lang.substringBefore('-').lowercase()) {
        "ja" -> "ja-JP"; "th" -> "th-TH"; "zh" -> "zh-CN"; "ko" -> "ko-KR"; "en" -> "en-US"
        "es" -> "es-ES"; "fr" -> "fr-FR"; "de" -> "de-DE"; "vi" -> "vi-VN"; "id" -> "id-ID"
        "ms" -> "ms-MY"; "ar" -> "ar-SA"
        else -> lang
    }
}
