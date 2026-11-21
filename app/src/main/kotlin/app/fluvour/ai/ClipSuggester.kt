package app.fluvour.ai

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Gemini (through the keys already in the app) for search phrases that fit the user's level and learned words.
 * Gemini only: nothing else is called and nothing paid is used. It writes phrases only; every clip shown is a real video found by NewPipe
 * and measured from its real subtitles. When the keys are out of quota the result is a failure and the Watch row stays hidden.
 */
@Singleton
class ClipSuggester @Inject constructor(private val ai: AiService) {
    suspend fun suggest(lang: String, langName: String, levelCode: String, learned: List<String>, clipCount: Int): Result<List<ClipQuery>> {
        val n = ClipPrompt.queryCount(clipCount)
        val prompt = ClipPrompt.build(langName, levelCode, lang, learned, n)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.8).put("maxOutputTokens", 1200).put("responseMimeType", "application/json"))
            .toString()
        val r = ai.geminiRaw(body)
        val text = r.text ?: return Result.failure(Exception(r.message ?: "Gemini did not answer."))
        val queries = ClipPrompt.parse(text, learned, n)
        return if (queries.isEmpty()) Result.failure(Exception("Gemini returned no usable search phrases.")) else Result.success(queries)
    }
}
