package app.fluvour.ai

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.fluvour.net.Http
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiKeyStore by preferencesDataStore("fluvour_ai_keys")

private fun encodeKeys(list: List<KeyEntry>): String {
    val arr = JSONArray()
    for (e in list) {
        val o = JSONObject().put("id", e.id).put("key", e.key).put("addedAt", e.addedAt)
        if (e.limitedAt != null) o.put("limitedAt", e.limitedAt)
        arr.put(o)
    }
    return arr.toString()
}

private fun decodeKeys(json: String?): List<KeyEntry> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key").trim()
            if (key.isEmpty()) null else KeyEntry(
                id = o.optString("id").ifEmpty { GeminiKeyPool.keyId(key) }, key = key, addedAt = o.optLong("addedAt"),
                limitedAt = if (o.has("limitedAt") && !o.isNull("limitedAt")) o.getLong("limitedAt") else null
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * One provider's key pool in the app's private DataStore (one JSON string per provider). Gemini keeps the name it had before
 * other providers existed, so keys saved earlier still work. Keys never leave the phone except in the call to that provider.
 */
class DataStoreKeyStorage(private val context: Context, provider: AiProvider) : KeyStorage {
    private val field = stringPreferencesKey(if (provider == AiProvider.GEMINI) "gemini_keys_json" else "${provider.id}_keys_json")

    override val entries: Flow<List<KeyEntry>> = context.aiKeyStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { decodeKeys(it[field]) }
        .distinctUntilChanged()

    override suspend fun update(transform: (List<KeyEntry>) -> List<KeyEntry>) {
        context.aiKeyStore.edit { p -> p[field] = encodeKeys(transform(decodeKeys(p[field]))) }
    }
}

/** An answer to a text request. Exactly one of [text] and [message] is set; [via] names the provider that answered. */
data class AiText(val text: String?, val message: String?, val via: String? = null)

/**
 * Direct AI calls with your own keys (Settings > AI keys): any number of keys per provider, rotated when one hits its limit,
 * no sign-in and no limit set by Fluvour. Text goes to Gemini first, then Groq, Cerebras, Mistral, OpenRouter and Hugging Face
 * (a provider with no key is skipped, a provider that fails passes the request on). Pictures go to Hugging Face only.
 * With no key at all, callers fall back to the ai-proxy as before.
 */
@Singleton
class AiService @Inject constructor(@ApplicationContext private val context: Context) {
    private val pools: Map<AiProvider, GeminiKeyPool> = AiProvider.values().associateWith { GeminiKeyPool(DataStoreKeyStorage(context, it)) }
    private val clients: Map<AiProvider, RotatingClient> = AiProvider.values().associateWith { p ->
        RotatingClient(pools.getValue(p), p.label) { url, key, body ->
            val auth = if (p == AiProvider.GEMINI) "x-goog-api-key" to key else "Authorization" to "Bearer $key"
            val r = Http.request("POST", url, mapOf("Content-Type" to "application/json", auth.first to auth.second), body, readTimeoutMs = 90_000)
            PostResult(r.code, r.body)
        }
    }

    fun pool(p: AiProvider): GeminiKeyPool = pools.getValue(p)

    /** Wipes limit stamps that earlier versions set by mistake. Runs only once per install. */
    suspend fun clearOldLimitMarksOnce() {
        val flag = stringPreferencesKey("old_limit_marks_cleared")
        if (context.aiKeyStore.data.first()[flag] == "1") return
        pools.values.forEach { it.clearLimits() }
        context.aiKeyStore.edit { it[flag] = "1" }
    }

    suspend fun hasTextKeys(): Boolean = AiProvider.TEXT_ORDER.any { pool(it).hasKeys() }
    suspend fun hasImageKeys(): Boolean = AiProvider.IMAGE_ORDER.any { pool(it).hasKeys() }

    // ---- model overrides (the default is only a starting point: model names change) ----
    private fun modelField(p: AiProvider) = stringPreferencesKey("model_${p.id}")
    /** What the user typed for this provider's model; blank = use the default. */
    val modelOverrides: Flow<Map<AiProvider, String>> = context.aiKeyStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> AiProvider.values().associateWith { prefs[modelField(it)].orEmpty() } }
        .distinctUntilChanged()

    suspend fun setModel(p: AiProvider, model: String) { context.aiKeyStore.edit { it[modelField(p)] = model.trim() } }
    private suspend fun modelFor(p: AiProvider): String = context.aiKeyStore.data.first()[modelField(p)]?.trim().orEmpty().ifEmpty { p.defaultModel }

    // ---- text ----
    suspend fun text(prompt: String, temperature: Double = 0.3, maxTokens: Int = 700): AiText {
        var last: String? = null
        var tried = false
        for (p in AiProvider.TEXT_ORDER) {
            if (!pool(p).hasKeys()) continue
            tried = true
            val model = modelFor(p)
            val r = if (p == AiProvider.GEMINI) {
                val chain = (listOf(model) + GeminiModels.chain(null, true)).distinct()
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens)).toString()
                clients.getValue(p).generate(chain) { m -> AiProvider.geminiUrl(m) to body }
            } else {
                val body = JSONObject().put("model", model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                    .put("temperature", temperature).put("max_tokens", maxTokens).toString()
                clients.getValue(p).generate(listOf(model)) { _ -> p.chatUrl!! to body }
            }
            when (r) {
                is GeminiResult.Failure -> { last = r.message; if (r.error == AiError.OFFLINE) return AiText(null, r.message) }
                is GeminiResult.Success -> {
                    val text = if (p == AiProvider.GEMINI) AiParse.geminiText(r.json) else AiParse.chatText(r.json)
                    if (text != null) return AiText(text, null, "${p.label} · ${r.model}")
                    last = "${p.label} returned no text."
                }
            }
        }
        return AiText(null, if (!tried) "No AI key yet. Add one in Settings > AI keys." else "No AI provider could answer. $last Add another key in Settings > AI keys.")
    }

    // ---- Gemini with a ready-made request body (used for reading screenshots) ----
    /** Sends `body` (a full Gemini generateContent request) to Gemini with key rotation and model fallback. Gemini only: the other providers take no pictures here. */
    suspend fun geminiRaw(body: String): AiText {
        if (!pool(AiProvider.GEMINI).hasKeys()) return AiText(null, "No Gemini key yet. Add one in Settings > AI keys.")
        val chain = (listOf(modelFor(AiProvider.GEMINI)) + GeminiModels.chain(null, true)).distinct()
        return when (val r = clients.getValue(AiProvider.GEMINI).generate(chain) { m -> AiProvider.geminiUrl(m) to body }) {
            is GeminiResult.Failure -> AiText(null, r.message)
            is GeminiResult.Success -> AiParse.geminiText(r.json)?.let { AiText(it, null, "Gemini · ${r.model}") } ?: AiText(null, "Gemini returned no text.")
        }
    }

    // ---- pictures ----
    /** Pictures use Hugging Face only (FLUX.1-schnell): the free Gemini API does not generate images. Returns (base64 image data, null) or (null, message). */
    suspend fun image(prompt: String): Pair<String?, String?> {
        if (!pool(AiProvider.HUGGINGFACE).hasKeys()) return null to "No Hugging Face key yet. Add one in Settings > AI keys."
        val body = JSONObject().put("model", AiProvider.HF_IMAGE_MODEL).put("prompt", prompt).put("width", 768).put("height", 768)
            .put("steps", 4).put("n", 1).put("response_format", "base64").toString()
        return when (val r = clients.getValue(AiProvider.HUGGINGFACE).generate(listOf(AiProvider.HF_IMAGE_MODEL)) { _ -> AiProvider.HF_IMAGE_URL to body }) {
            is GeminiResult.Failure -> null to r.message
            is GeminiResult.Success -> AiParse.b64Image(r.json)?.let { it to null } ?: (null to "Hugging Face returned no picture.")
        }
    }
}
