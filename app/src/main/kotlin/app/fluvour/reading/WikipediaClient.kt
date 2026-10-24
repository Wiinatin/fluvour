package app.fluvour.reading

import app.fluvour.net.Http
import org.json.JSONObject
import java.net.URLEncoder

data class ArticleHit(val lang: String, val pageId: Long, val title: String, val preview: String)
data class FetchedArticle(val title: String, val body: String, val url: String)

/**
 * Sourced reading material from Wikipedia (plain text via the MediaWiki API). Real articles only, never AI-written.
 * Text is CC BY-SA 4.0: keep the link and licence next to every saved article.
 * Wikipedia's language editions use the same codes as Fluvour's languages (ja, th, zh, ko, en, es, fr, de, vi, id).
 */
object WikipediaClient {
    const val LICENSE = "CC BY-SA 4.0 (Wikipedia)"
    private val headers = mapOf("User-Agent" to "Fluvour/0.0.1 (personal language-learning app)", "Accept" to "application/json")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun api(lang: String) = "https://$lang.wikipedia.org/w/api.php"

    private val previewParams = "&prop=extracts&exintro=1&explaintext=1&exchars=300&exlimit=max&redirects=1&format=json&formatversion=2"

    /** Search results, or random articles when `query` is blank. Random stubs are filtered out. */
    suspend fun list(lang: String, query: String?): Result<List<ArticleHit>> = runCatching {
        val url = if (query.isNullOrBlank())
            "${api(lang)}?action=query&generator=random&grnnamespace=0&grnlimit=20$previewParams"
        else
            "${api(lang)}?action=query&generator=search&gsrsearch=${enc(query.trim())}&gsrnamespace=0&gsrlimit=15$previewParams"
        val r = Http.request("GET", url, headers)
        if (!r.ok) error("Wikipedia answered ${r.code}")
        val pages = JSONObject(r.body).optJSONObject("query")?.optJSONArray("pages") ?: return@runCatching emptyList()
        val hits = (0 until pages.length()).map { pages.getJSONObject(it) }
        val ordered = if (query.isNullOrBlank()) hits else hits.sortedBy { it.optInt("index", Int.MAX_VALUE) }
        val all = ordered.map { ArticleHit(lang, it.getLong("pageid"), it.getString("title"), it.optString("extract").trim()) }
        // For random picks, drop stubs and keep the list short; for searches keep everything.
        if (query.isNullOrBlank()) all.filter { it.preview.length >= 150 }.take(8) else all
    }

    /** The whole article as plain text. */
    suspend fun fetch(hit: ArticleHit): Result<FetchedArticle> = runCatching {
        val url = "${api(hit.lang)}?action=query&pageids=${hit.pageId}&prop=extracts&explaintext=1&exsectionformat=plain&format=json&formatversion=2"
        val r = Http.request("GET", url, headers, readTimeoutMs = 45_000)
        if (!r.ok) error("Wikipedia answered ${r.code}")
        val page = JSONObject(r.body).getJSONObject("query").getJSONArray("pages").getJSONObject(0)
        val body = clean(page.optString("extract"))
        if (body.isBlank()) error("This article has no text.")
        FetchedArticle(page.optString("title", hit.title), body, "https://${hit.lang}.wikipedia.org/?curid=${hit.pageId}")
    }

    /** Plain-text extracts mark headings as "== Title ==" in some cases and leave blank lines; normalise both. */
    internal fun clean(raw: String): String =
        raw.replace(Regex("(?m)^=+\\s*(.*?)\\s*=+$"), "$1")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
