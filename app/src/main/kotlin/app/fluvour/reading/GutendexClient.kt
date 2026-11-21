package app.fluvour.reading

import app.fluvour.net.Http

/** Search and browse Project Gutenberg through Gutendex (free, no key). Parsing lives in GutendexParse. */
object GutendexClient {
    private val headers = mapOf("User-Agent" to "Fluvour/0.0.1 (personal language-learning app)", "Accept" to "application/json")

    /** Blank `query` gives the most downloaded books in `lang`. One retry on a network error or a 5xx reply. */
    suspend fun search(lang: String, query: String?, page: Int = 1): Result<CatalogPage> = runCatching {
        val url = GutendexParse.url(lang, query, page)
        var last: Throwable? = null
        repeat(2) {
            try {
                val r = Http.request("GET", url, headers)
                if (r.ok) return@runCatching GutendexParse.page(r.body) ?: error("The book catalog sent a reply I could not read.")
                if (r.code < 500) error("The book catalog answered ${r.code}.")
                last = IllegalStateException("The book catalog answered ${r.code}.")
            } catch (e: java.io.IOException) { last = e }
        }
        throw last ?: IllegalStateException("The book catalog is not reachable.")
    }
}
