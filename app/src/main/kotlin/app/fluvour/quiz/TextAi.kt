package app.fluvour.quiz

import app.fluvour.ai.AiService
import app.fluvour.data.AuthRepository
import app.fluvour.net.Backend
import app.fluvour.net.Http
import app.fluvour.reading.LookupRepository
import app.fluvour.reading.Part
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** One plain-text question to the AI, the same way the reader's popup asks: your own keys first, the ai-proxy when you have none. */
@Singleton
class TextAi @Inject constructor(private val ai: AiService, private val auth: AuthRepository) {
    suspend fun ask(prompt: String, maxTokens: Int = 500, temperature: Double = 0.7): Part {
        if (ai.hasTextKeys()) {
            val r = ai.text(prompt, temperature, maxTokens)
            return r.text?.let { Part(it) } ?: Part(message = r.message)
        }
        if (!Backend.configured) return Part(message = "No AI key yet. Add one in Settings > AI keys.")
        val token = auth.validToken() ?: return Part(message = "Add a key in Settings > AI keys, or sign in (Settings > Account).")
        val request = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens))
        val r = try {
            Http.request("POST", "${Backend.url}/functions/v1/ai-proxy", Backend.headers(token), JSONObject().put("provider", "gemini").put("body", request).toString(), readTimeoutMs = 60_000)
        } catch (e: Exception) { return Part(message = "No connection.") }
        if (r.code == 429) return Part(message = "The shared AI is at its limit. Add your own key in Settings > AI keys.")
        if (!r.ok) return Part(message = "The AI request failed (${r.code}).")
        val text = try { LookupRepository.answerText(r.body) } catch (e: Exception) { null }
        return if (text.isNullOrBlank()) Part(message = "The AI returned no text.") else Part(text)
    }
}
