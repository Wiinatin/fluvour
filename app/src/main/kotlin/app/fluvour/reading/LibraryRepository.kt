package app.fluvour.reading

import android.content.Context
import app.fluvour.data.LibraryBookDao
import app.fluvour.data.LibraryBookEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** An imported book that has no library row yet. */
data class OwnBook(val sourceId: String, val title: String, val author: String?)

/**
 * The Read library: shelf (heart), downloads and where Win stopped. Local only.
 * A downloaded book is never deleted here; only rows that are neither shelved nor downloaded are pruned.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val dao: LibraryBookDao,
    @ApplicationContext private val context: Context
) {
    private val coverDir get() = File(context.filesDir, "covers")

    fun shelf(lang: String): Flow<List<LibraryBookEntity>> = dao.observeShelf(lang)
    fun downloaded(lang: String): Flow<List<LibraryBookEntity>> = dao.observeDownloaded(lang)
    fun shelfIds(lang: String): Flow<Set<String>> = dao.observeShelfIds(lang).map { it.toSet() }
    fun book(id: String): Flow<LibraryBookEntity?> = dao.observe(id)
    suspend fun get(id: String): LibraryBookEntity? = dao.get(id)

    /** Downloaded books Win has opened, newest first, with the chapter to open and the overall progress. */
    fun continueReading(lang: String): Flow<List<ContinueItem>> = dao.observeProgress(lang).map { LibraryMath.continueList(it) }

    /** Makes sure a catalog book has a row (new rows are not shelved or downloaded). Returns its id. */
    suspend fun ensure(book: CatalogBook, lang: String): String {
        val id = LibraryMath.catalogId("gutenberg", book.id)
        val now = System.currentTimeMillis()
        dao.insertIgnore(LibraryBookEntity(
            id = id, origin = "gutenberg", lang = lang, title = book.title, author = book.author,
            coverUrl = book.coverUrl, coverPath = null, epubUrl = book.epubUrl,
            tags = book.tags.joinToString("|").ifEmpty { null }, about = book.about, sourceId = null,
            shelved = false, shelvedAt = 0, downloadedAt = null, addedAt = now
        ))
        return id
    }

    /** The heart. Adds the book to the shelf, or takes it off. A downloaded book stays downloaded when un-hearted. Returns true when now shelved. */
    suspend fun toggleShelf(book: CatalogBook, lang: String): Boolean {
        val id = ensure(book, lang)
        val on = !(dao.get(id)?.shelved ?: false)
        dao.setShelved(id, on, System.currentTimeMillis())
        if (!on) dao.pruneIdle()
        return on
    }

    /** The heart for a row that already exists (a downloaded own file, or a book on the detail page). */
    suspend fun setShelved(id: String, on: Boolean) {
        if (dao.get(id) == null) return
        dao.setShelved(id, on, System.currentTimeMillis())
        if (!on) dao.pruneIdle()
    }

    /** Called by the download step once the EPUB is imported: links the row to the reader source. */
    suspend fun markDownloaded(id: String, sourceId: String, coverPath: String?) =
        dao.setDownloaded(id, sourceId, coverPath, System.currentTimeMillis())

    /** Saves cover bytes under filesDir/covers and returns the file path, or null if writing failed. */
    suspend fun saveCover(id: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            coverDir.mkdirs()
            val f = File(coverDir, LibraryMath.safeFileName(id) + ".img")
            f.writeBytes(bytes)
            f.absolutePath
        }.getOrNull()
    }

    /**
     * Own files (EPUB the user added): a library row with id "file:<sourceId>", linked to its reader source from the start.
     * `cover` is the picture read from the EPUB; without one the app draws a generated cover. Safe to call twice.
     */
    suspend fun addOwnFile(sourceId: String, lang: String, title: String, author: String?, cover: ByteArray?): String {
        val id = LibraryMath.catalogId("file", sourceId)
        val now = System.currentTimeMillis()
        dao.insertIgnore(LibraryBookEntity(
            id = id, origin = "file", lang = lang, title = title, author = author.orEmpty(), coverUrl = null, coverPath = null, epubUrl = null,
            tags = null, about = null, sourceId = sourceId, shelved = false, shelvedAt = 0, downloadedAt = now, addedAt = now
        ))
        if (cover != null) saveCover(id, cover)?.let { dao.setCoverPath(id, it) }
        return id
    }

    /** Gives books imported before the library existed a row (generated cover). Books that already have one are skipped. */
    suspend fun adoptOwnFiles(lang: String, books: List<OwnBook>) {
        for (b in books) if (dao.getBySource(b.sourceId) == null) addOwnFile(b.sourceId, lang, b.title, b.author, null)
    }

    /** The user removed an own file: its row, heart and cover file go with it. */
    suspend fun forgetOwnFile(sourceId: String) {
        val row = dao.getBySource(sourceId) ?: return
        dao.deleteById(row.id)
        row.coverPath?.let { p -> withContext(Dispatchers.IO) { runCatching { File(p).delete() } } }
    }

    /** The user removed the offline copy. The heart stays; a book that is not hearted loses its row and cover file. */
    suspend fun clearDownload(id: String) {
        val row = dao.get(id) ?: return
        dao.clearDownloaded(id)
        if (!row.shelved) {
            dao.pruneIdle()
            row.coverPath?.let { p -> withContext(Dispatchers.IO) { runCatching { File(p).delete() } } }
        }
    }
}
