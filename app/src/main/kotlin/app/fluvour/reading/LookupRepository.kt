package app.fluvour.reading

import app.fluvour.ai.AiService
import app.fluvour.data.AuthRepository
import app.fluvour.data.LookupCacheDao
import app.fluvour.data.LookupCacheEntity
import app.fluvour.language.Languages
import app.fluvour.net.Backend
import app.fluvour.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one lookup part: text on success, otherwise a short message the popup can show. */
data class Part(val text: String? = null, val message: String? = null)

/**
 * Meanings for the tap popup: a dictionary entry plus a short AI explanation of the word in its sentence.
 * Both are cached on the phone, so each word (or word + sentence) is fetched once (free tiers have rate limits).
 * Only successes are cached. With your own AI keys saved (Settings > AI keys: Gemini, Groq, Cerebras, Mistral, OpenRouter, Hugging Face)
 * the AI call goes straight to the provider and rotates between keys and providers: no sign-in, no app-side limit.
 * With no key saved it goes through the ai-proxy as before.
 */
@Singleton
class LookupRepository @Inject constructor(private val cache: LookupCacheDao, private val auth: AuthRepository, private val ai: AiService) {

    suspend fun dictionary(userId: String, lang: String, word: String): Part {
        val key = hash("dict", lang, "", word, "")
        cache.get(key)?.let { return Part(it.text) }
        return DictionaryClient.lookup(lang, word).fold(
            onSuccess = { text ->
                if (text == null) Part(message = "No dictionary entry for this form. Try the AI explanation, or write the meaning yourself.")
                else { save(key, userId, "dict", lang, word, text); Part(text) }
            },
            onFailure = { Part(message = "Could not reach the dictionary. Check your connection.") }
        )
    }

    suspend fun explain(userId: String, lang: String, uiLang: String, word: String, sentence: String): Part {
        val key = hash("ai", lang, uiLang, word, sentence)
        cache.get(key)?.let { return Part(it.text) }
        val request = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(lang, uiLang, word, sentence))))))
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 700))
        if (ai.hasTextKeys()) {
            val r = ai.text(prompt(lang, uiLang, word, sentence))
            val text = r.text ?: return Part(message = r.message)
            save(key, userId, "ai", lang, word, text)
            return Part(text)
        }
        if (!Backend.configured) return Part(message = "No AI key yet. Add a key in Settings > AI keys.")
        val token = auth.validToken() ?: return Part(message = "Add a key in Settings > AI keys, or sign in (Settings > Account).")
        val body = JSONObject().put("provider", "gemini").put("body", request).toString()
        val r = try {
            Http.request("POST", "${Backend.url}/functions/v1/ai-proxy", Backend.headers(token), body, readTimeoutMs = 60_000)
        } catch (e: Exception) { return Part(message = "No connection.") }
        if (r.code == 429) return Part(message = "The shared AI is at its limit. Add your own key in Settings > AI keys.")
        if (!r.ok) return Part(message = "The AI explanation failed (${r.code}).")
        val text = try { answerText(r.body) } catch (e: Exception) { null }
        if (text.isNullOrBlank()) return Part(message = "The AI returned no explanation.")
        save(key, userId, "ai", lang, word, text)
        return Part(text)
    }

    private suspend fun save(key: String, userId: String, kind: String, lang: String, word: String, text: String) =
        cache.put(LookupCacheEntity(key, userId, kind, lang, word, text, System.currentTimeMillis()))

    companion object {
        internal fun prompt(lang: String, uiLang: String, word: String, sentence: String): String {
            val target = Languages.byCode(lang).name
            val ui = Languages.byCode(uiLang).name
            return "You help a learner of $target who reads real articles. Explain the word \"$word\" as it is used in this $target sentence:\n" +
                "\"$sentence\"\n\n" +
                "Answer in $ui, plain text, no markdown, at most 4 short lines:\n" +
                "1. Dictionary form of the word (if different) and its reading or romanisation when the script needs one.\n" +
                "2. What it means in THIS sentence.\n" +
                "3. One short note on grammar, a particle, or a common phrase it belongs to, only if useful."
        }

        /** The text of the first Gemini candidate. */
        internal fun answerText(body: String): String? {
            val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim().ifBlank { null }
        }

        internal fun hash(kind: String, lang: String, ui: String, word: String, sentence: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest("$kind|$lang|$ui|$word|$sentence".toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
