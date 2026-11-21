package app.fluvour.reading

import app.fluvour.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Word meanings from Wiktionary (English edition, REST "definition" endpoint). It lists entries for the word in every
 * language, keyed by language code, with English glosses. Text is CC BY-SA 4.0; the popup names the source.
 * This is the online starter dictionary for 2A step 2. Offline dictionaries (JMdict, CC-CEDICT, Yomitan) come in step 3 and 9
 * and can replace this behind the same `lookup` call.
 */
object DictionaryClient {
    const val SOURCE = "Wiktionary (CC BY-SA 4.0)"
    private val headers = mapOf("User-Agent" to "Fluvour/0.0.1 (personal language-learning app)", "Accept" to "application/json")
    private const val MAX_POS = 4
    private const val MAX_DEFS = 4

    /** Plain-text meanings ("Noun\n• ...\n• ..."), null when there is no entry, failure when the service cannot be reached. */
    suspend fun lookup(lang: String, word: String): Result<String?> = runCatching {
        // Wiktionary titles are case-sensitive; sentence starts in Latin scripts are capitalised, so also try lower case.
        // Arabic: Wiktionary titles are usually written without vowel marks, so the plain form is tried too.
        val candidates = (if (lang == "ar") listOf(word, app.fluvour.srs.plainArabic(word)) else listOf(word, word.lowercase())).distinct()
        for (w in candidates) {
            val r = Http.request("GET", "https://en.wiktionary.org/api/rest_v1/page/definition/${enc(w)}", headers)
            if (r.code == 404) continue
            if (!r.ok) error("Wiktionary answered ${r.code}")
            parse(r.body, lang)?.let { return@runCatching it }
        }
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Pure parsing, kept separate so it can be tested without a network. Entries with the same part of speech are merged and repeated meanings dropped. */
    internal fun parse(body: String, lang: String): String? {
        val entries = JSONObject(body).optJSONArray(lang) ?: return null
        val groups = LinkedHashMap<String, LinkedHashSet<String>>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val defs = e.optJSONArray("definitions") ?: continue
            val pos = e.optString("partOfSpeech").ifBlank { "Meaning" }
            for (j in 0 until defs.length()) {
                val line = clean(defs.getJSONObject(j).optString("definition"))
                if (line.isBlank()) continue
                val seen = groups.getOrPut(pos) { LinkedHashSet() }
                if (seen.none { it.equals(line, ignoreCase = true) }) seen.add(line)
            }
        }
        val out = StringBuilder()
        var shown = 0
        for ((pos, lines) in groups) {
            if (lines.isEmpty()) continue
            if (shown++ >= MAX_POS) break
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(pos)
            lines.take(MAX_DEFS).forEach { out.append("\n• ").append(it) }
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** Wiktionary leaves maintenance prompts inside some senses, e.g. "(Can we verify this sense?)". They are not meanings. */
    private val leftovers = listOf(
        Regex("\\(\\s*Can we verify this sense\\?\\s*\\)", RegexOption.IGNORE_CASE),
        Regex("\\(\\s*Can we (?:verify|find|add)[^)]*\\?\\s*\\)", RegexOption.IGNORE_CASE),
        Regex("\\[\\s*(?:citation needed|edit)\\s*]", RegexOption.IGNORE_CASE)
    )

    internal fun clean(html: String): String {
        var t = stripHtml(html)
        leftovers.forEach { t = it.replace(t, "") }
        return t.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.;:])"), "$1").trim()
    }

    internal fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
}
