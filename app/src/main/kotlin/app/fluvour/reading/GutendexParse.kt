package app.fluvour.reading

import java.net.URLEncoder

/** One Project Gutenberg book as the catalog describes it. Nothing here is stored or synced. */
data class CatalogBook(
    val id: Long,
    val title: String,
    val author: String,
    val language: String,
    val coverUrl: String?,
    val epubUrl: String,
    val tags: List<String> = emptyList(),
    val about: String? = null,
    val downloads: Long = 0
)

data class CatalogPage(val books: List<CatalogBook>, val hasNext: Boolean)

/** Pure parsing and URL building for the Gutendex API (https://gutendex.com). No Android, no network: runs on a plain JVM. */
object GutendexParse {
    const val BASE = "https://gutendex.com/books"
    private const val EPUB = "application/epub+zip"

    /** `query` blank means the most downloaded books. `lang` is the two-letter code Gutendex uses (same as Fluvour's). */
    fun url(lang: String, query: String?, page: Int = 1): String {
        val q = StringBuilder("$BASE?languages=${enc(lang.trim().lowercase())}")
        if (!query.isNullOrBlank()) q.append("&search=").append(enc(query.trim()))
        if (page > 1) q.append("&page=").append(page)
        return q.toString()
    }

    /** Null when the reply is not usable JSON. Books without an EPUB or marked copyrighted are dropped; bad rows never fail the page. */
    fun page(json: String): CatalogPage? {
        val root = try { MiniJson.parse(json) } catch (e: Exception) { return null } as? Map<*, *> ?: return null
        val results = root["results"] as? List<*> ?: return null
        val books = results.mapNotNull { runCatching { book(it) }.getOrNull() }.distinctBy { it.id }
        val next = (root["next"] as? String)?.isNotBlank() == true
        return CatalogPage(books, next)
    }

    internal fun book(row: Any?): CatalogBook? {
        val m = row as? Map<*, *> ?: return null
        val id = (m["id"] as? Double)?.toLong() ?: return null
        if (m["copyright"] == true) return null
        val formats = (m["formats"] as? Map<*, *>)?.entries
            ?.mapNotNull { (k, v) -> if (k is String && v is String && v.startsWith("http")) k to v else null } ?: return null
        val epub = pickEpub(formats) ?: return null
        val title = tidy((m["title"] as? String).orEmpty()).ifEmpty { return null }
        val authors = (m["authors"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
            .map { flipName(it) }.filter { it.isNotEmpty() }
        val lang = (m["languages"] as? List<*>)?.firstOrNull() as? String ?: ""
        val about = ((m["summaries"] as? List<*>)?.firstOrNull() as? String)?.let { tidy(it) }?.ifEmpty { null }
        return CatalogBook(
            id = id, title = title, author = authors.joinToString(", ").ifEmpty { "Unknown" }, language = lang,
            coverUrl = pickCover(formats), epubUrl = epub, tags = tags(m), about = about,
            downloads = (m["download_count"] as? Double)?.toLong() ?: 0
        )
    }

    /** Prefer the EPUB with images; fall back to any EPUB. */
    private fun pickEpub(formats: List<Pair<String, String>>): String? {
        val epubs = formats.filter { it.first.startsWith(EPUB) }.map { it.second }
        return epubs.firstOrNull { it.contains(".images") && !it.contains(".noimages") } ?: epubs.firstOrNull { !it.contains(".noimages") } ?: epubs.firstOrNull()
    }

    private fun pickCover(formats: List<Pair<String, String>>): String? {
        val imgs = formats.filter { it.first.startsWith("image/") }.map { it.second }
        return imgs.firstOrNull { it.contains("cover") } ?: imgs.firstOrNull()
    }

    /** "Austen, Jane" becomes "Jane Austen". Names without a comma stay as they are. */
    internal fun flipName(raw: String): String {
        val s = tidy(raw)
        val i = s.indexOf(',')
        if (i <= 0) return s
        val last = s.substring(0, i).trim(); val first = s.substring(i + 1).trim()
        return if (first.isEmpty()) last else "$first $last"
    }

    /** Up to three short tags: bookshelves first ("Category: Novels" becomes "Novels"), then the first part of a subject. */
    private fun tags(m: Map<*, *>): List<String> {
        val shelves = (m["bookshelves"] as? List<*>).orEmpty().filterIsInstance<String>().map { tidy(it.removePrefix("Category:")) }
        val subjects = (m["subjects"] as? List<*>).orEmpty().filterIsInstance<String>().map { tidy(it.substringBefore(" -- ")) }
        return (shelves + subjects).filter { it.isNotEmpty() && it.length <= 32 }.distinctBy { it.lowercase() }.take(3)
    }

    /** Collapse line breaks and runs of spaces ("Title\r\nSubtitle" becomes "Title Subtitle"). */
    internal fun tidy(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
