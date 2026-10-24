package app.fluvour.quiz

import app.fluvour.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/** A real example sentence from Tatoeba (CC BY 2.0 FR) with its translation when there is one. `audioId` is Tatoeba's recording id, when it has one. */
data class TatoebaSentence(val text: String, val translation: String?, val audioId: Long?)

/**
 * Tatoeba example sentences (Phase 3 step 6). Public search API, no key. Needs a connection; callers cache the answer.
 * Tatoeba uses three-letter language codes. Native audio: the recording id is kept, playing it is not built yet.
 */
object TatoebaClient {
    private val codes = mapOf("ja" to "jpn", "th" to "tha", "en" to "eng", "zh" to "cmn", "ko" to "kor", "es" to "spa", "fr" to "fra", "de" to "deu", "vi" to "vie", "id" to "ind")
    const val CREDIT = "Tatoeba (CC BY 2.0 FR)"

    suspend fun search(lang: String, uiLang: String, word: String, limit: Int = 5): Result<List<TatoebaSentence>> {
        val from = codes[lang] ?: return Result.success(emptyList())
        val to = codes[uiLang] ?: "eng"
        val url = "https://tatoeba.org/en/api_v0/search?from=$from&to=$to&query=${URLEncoder.encode(word, "UTF-8")}&orphans=no&unapproved=no&sort=relevance"
        return try {
            val r = Http.request("GET", url, mapOf("Accept" to "application/json"), readTimeoutMs = 20_000)
            if (!r.ok) Result.failure(Exception("Tatoeba ${r.code}")) else Result.success(parse(r.body, limit))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Pure parsing, kept apart so it can be checked without a network. */
    internal fun parse(body: String, limit: Int): List<TatoebaSentence> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<TatoebaSentence>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val text = o.optString("text").trim()
            if (text.isEmpty() || text.length > 120) continue         // very long sentences make poor quiz questions
            // "translations" is a list of groups; the first non-empty text found is used.
            var translation: String? = null
            o.optJSONArray("translations")?.let { groups ->
                loop@ for (g in 0 until groups.length()) {
                    val grp = groups.optJSONArray(g) ?: continue
                    for (k in 0 until grp.length()) { val t = grp.optJSONObject(k)?.optString("text")?.trim(); if (!t.isNullOrEmpty()) { translation = t; break@loop } }
                }
            }
            val audio = o.optJSONArray("audios")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
            out += TatoebaSentence(text, translation, audio)
            if (out.size >= limit) break
        }
        return out
    }
}
