package app.fluvour.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The words for the transcribe request. Pure, so it can be checked on a plain JVM. */
object TranscribePrompt {
    /** The only languages a speaker here uses besides the one being practised: users are Thai or English speakers. */
    val OWN_LANGUAGES = listOf("Thai", "English")

    /** The practised language first, then Thai and English, with no repeats. Nothing else is ever allowed in a transcript. */
    fun allowed(lang: String): List<String> = (listOf(lang) + OWN_LANGUAGES).distinct()

    /**
     * [lang] is the language being practised (for example "Thai"). [own] is kept for callers and no longer decides which languages are
     * allowed: the speaker only ever uses [lang], Thai or English, so a swapped-in word is Thai or English and never anything else.
     * [topic] and [hints] are the topic title and its cue words: only a hint about what the speaker may say, never words to add.
     * [strict] is the retry after [TranscriptCheck] found foreign letters or romanized words; it says so and repeats the rules.
     * The model is told to keep mistakes: without that it quietly corrects the speech and the corrections step has nothing to show.
     * The answer shape is `{"transcript":"...","substituted":["..."]}`, read by the parser in step 5.
     */
    fun build(lang: String, own: String, topic: String = "", hints: List<String> = emptyList(), strict: Boolean = false): String {
        val ok = allowed(lang)
        val list = ok.joinToString(" and ")
        val others = ok.filter { it != lang }.joinToString(" or ")
        val context = if (topic.isBlank() && hints.isEmpty()) "" else
            "The topic is \"${topic.trim()}\"" + (if (hints.isEmpty()) "" else ", and words that may come up are: " + hints.joinToString(", ")) +
            ". This is only a hint to help you hear correctly. Never write a word that was not spoken. "
        val again = if (!strict) "" else
            "A previous attempt was wrong: it used letters or words from a language that is not allowed, or it wrote $lang words in Latin letters. " +
            "Listen again and follow the language and script rules exactly. "
        return again +
        "This is a recording of a learner speaking $lang. Transcribe exactly what was said. " + context +
        "The speaker only uses $list. No other language is ever spoken, so never write Vietnamese, Spanish, French or any other language. " +
        "If a sound is unclear or badly pronounced, write the closest real word of $list that the speaker most likely meant. " +
        "Write each word in the normal script of its own language: " + ok.joinToString(", ") {
            when (it) { "English" -> "English in Latin letters"; "Thai" -> "Thai in Thai script"; else -> "$it in the script normally used for $it" }
        } + ". Never romanize a word: no pinyin, romaji or Thai written in Latin letters. " +
        "Write a digit only when the speaker said a number; never turn a sound or a syllable into a digit. " +
        "Include every mistake. Do not correct grammar, word choice or word order. Do not add, remove, reorder or improve any word. " +
        "Keep false starts and repeated words. " +
        "Sometimes the speaker says a word in $others because they do not know the word in $lang. " +
        "Write such a word as it was spoken, in its own language and script. Do not translate it. List every such word in \"substituted\". " +
        "If there is silence, noise or no speech, return an empty transcript and an empty list. Never invent words. " +
        "Return JSON only, in this shape: {\"transcript\":\"...\",\"substituted\":[\"...\"]}. " +
        "Each item in \"substituted\" must appear in the transcript exactly as written there, and an item that is one word of a phrase should be listed once."
    }

    const val MIME = "audio/aac"
    /** Gemini takes at most about 20 MB for one request with inline data; a 3 minute recording is about 1 MB. */
    const val MAX_BYTES = 15_000_000
}

/**
 * Step 1 of the speaking pipeline: sends the recording to Gemini and returns its raw JSON reply text (parsed in step 5).
 * Gemini only, through the key pool in `AiService.geminiRaw`; the recording goes to Google through the user's own key and is not kept
 * anywhere else. With no Gemini key the answer is the usual "Add one in Settings > AI keys." message.
 */
@Singleton
class SpeechTranscriber @Inject constructor(private val ai: AiService) {
    /** [audio] is the saved `.aac` file. The text of the result is the model's JSON, or null with [AiText.message] saying why. */
    suspend fun transcribe(audio: File, lang: String, own: String, topic: String = "", hints: List<String> = emptyList(), strict: Boolean = false): AiText {
        if (!ai.pool(AiProvider.GEMINI).hasKeys()) return AiText(null, "No Gemini key yet. Add one in Settings > AI keys.")
        val b64 = withContext(Dispatchers.IO) {
            if (!audio.exists() || audio.length() == 0L) return@withContext null
            if (audio.length() > TranscribePrompt.MAX_BYTES) return@withContext ""
            Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        }
        if (b64 == null) return AiText(null, "The recording file is missing.")
        if (b64.isEmpty()) return AiText(null, "The recording is too large to send.")
        return ai.geminiRaw(body(TranscribePrompt.build(lang, own, topic, hints, strict), b64))
    }

    /** The instruction text first, then the audio part. Temperature 0 so the transcript does not drift towards a nicer wording. */
    internal fun body(prompt: String, b64: String): String = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", TranscribePrompt.MIME).put("data", b64))))))
        .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 8000).put("responseMimeType", "application/json"))
        .toString()
}
