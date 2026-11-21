package app.fluvour.reading

import app.fluvour.net.Http
import kotlinx.coroutines.CancellationException

/**
 * Step 5: downloads a catalog book for offline reading. EPUB bytes -> existing import (so tap-to-lookup and mining work)
 * -> library row linked to the reader source. The cover is best effort and never blocks the download.
 * A book that is already downloaded is returned as is, never imported twice. Nothing is deleted here.
 */
@javax.inject.Singleton
class BookDownloader @javax.inject.Inject constructor(
    private val imports: ImportRepository,
    private val library: LibraryRepository
) {
    private val headers = mapOf("User-Agent" to "Fluvour/0.0.1 (personal language-learning app)")

    /** `sourceId` of the imported book (open it with the `book/{sourceId}` route) and the chapter to start from. */
    data class Downloaded(val libraryId: String, val sourceId: String, val firstArticleId: String?, val title: String)

    suspend fun download(userId: String, lang: String, book: CatalogBook): Result<Downloaded> {
        val id = library.ensure(book, lang)
        library.get(id)?.let { row ->
            if (row.sourceId != null && row.downloadedAt != null) return Result.success(Downloaded(id, row.sourceId, null, row.title))
        }
        val bytes = try { Http.getBytes(book.epubUrl, headers) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return Result.failure(ImportException(netMessage(e))) }

        val imported = imports.importEpubBytes(userId, lang, bytes, book.title).getOrElse { return Result.failure(it) }
        val coverPath = fetchCover(id, book.coverUrl)
        // The text is already safely stored; if linking fails the book is still readable from the Saved list.
        return try {
            library.markDownloaded(id, imported.sourceId, coverPath)
            Result.success(Downloaded(id, imported.sourceId, imported.firstArticleId, imported.title))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(ImportException("The book was saved but could not be added to your library: ${e.message ?: "unknown error"}")) }
    }

    private suspend fun fetchCover(id: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try { library.saveCover(id, Http.getBytes(url, headers, maxBytes = 5 * 1024 * 1024, readTimeoutMs = 20_000)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { null }
    }

    private fun netMessage(e: Exception): String = when {
        e.message?.contains("too large", true) == true -> "This book is too large to download."
        e is java.io.IOException -> "Could not download the book. Check your connection and try again."
        else -> "Could not download the book: ${e.message ?: "unknown error"}"
    }
}
