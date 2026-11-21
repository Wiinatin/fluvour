package app.fluvour.reading

import android.content.Context
import android.net.Uri
import app.fluvour.data.ArticleDao
import app.fluvour.data.ChapterRef
import app.fluvour.data.SettingsRepository
import app.fluvour.data.SourceDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings row shows about the imported book. */
data class WpmeBookInfo(val title: String, val chunks: Int, val located: Int)

/**
 * The user's own copy of Word Power Made Easy, imported through the normal book import (so it sits in the Read tab with
 * tap-to-lookup) and linked to the deck: for each deck word the app remembers where the book teaches it ([WpmeLocator], positions
 * only in `wpme-locator.tsv`). The passage shown by "See more" is cut from the book at tap time and never stored. The book's
 * source id is kept in `SharedPreferences("wpme")`.
 */
@Singleton
class WpmeBookRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val imports: ImportRepository,
    private val articles: ArticleDao,
    private val sources: SourceDao,
    private val reader: ReaderRepository,
    private val deck: WpmeRepository,
    private val settings: SettingsRepository,
) {
    private val prefs get() = ctx.getSharedPreferences("wpme", Context.MODE_PRIVATE)
    private val file get() = File(ctx.filesDir, WpmeLocator.FILE)

    private val _info = MutableStateFlow<WpmeBookInfo?>(null)
    /** The imported book and how many deck words were found in it; null while there is no book (or before [refresh]). */
    val info: StateFlow<WpmeBookInfo?> = _info.asStateFlow()

    @Volatile private var locator: Map<String, WpmeLocation>? = null
    @Volatile private var chapterCache: Pair<String, List<ChapterRef>>? = null

    private val sourceId: String? get() = prefs.getString(KEY_SOURCE, null)

    private fun loadLocator(): Map<String, WpmeLocation> = locator ?: run {
        val m = if (file.exists()) runCatching { file.bufferedReader().useLines { WpmeLocator.decode(it) } }.getOrDefault(emptyMap()) else emptyMap()
        locator = m
        m
    }

    private suspend fun chapters(sid: String): List<ChapterRef> {
        chapterCache?.let { if (it.first == sid) return it.second }
        val list = articles.observeChapters(sid).first().sortedBy { it.ordinal }
        chapterCache = sid to list
        return list
    }

    /** Looks at the stored book id and locator file and updates [info]. */
    suspend fun refresh() { withContext(Dispatchers.IO) { publish() } }

    private suspend fun publish() {
        val sid = sourceId
        val src = sid?.let { sources.get(it) }?.takeIf { !it.deleted }
        if (sid == null || src == null) { _info.value = null; return }
        if (prefs.getInt(KEY_LOC_VERSION, 1) < LOC_VERSION && (deck.count.value ?: 0) > 0) { rebuildLocator(); return }   // the locator now covers roots too
        _info.value = WpmeBookInfo(src.title, chapters(sid).size, loadLocator().size)
    }

    /** True when the card can offer "See more" and "Read in the book" for this item. */
    suspend fun hasPassage(key: String): Boolean = withContext(Dispatchers.IO) { sourceId != null && key in loadLocator() }

    /** Imports the user's EPUB through the normal book import, replaces the book imported here before, and links the deck words to it. Returns how many words were found. */
    suspend fun importBook(uri: Uri, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            onProgress("Importing the book")
            val userId = settings.ensureUserId()
            val imported = imports.importFile(userId, "en", uri).getOrElse { return@withContext Result.failure(WpmeException(it.message ?: "Could not import the book.")) }
            val old = sourceId
            prefs.edit().putString(KEY_SOURCE, imported.sourceId).apply()
            chapterCache = null
            if (old != null && old != imported.sourceId) {
                val now = System.currentTimeMillis()
                articles.softDeleteBySource(old, now); sources.softDelete(old, now)   // importing twice must not double the book in the Read tab
            }
            rebuildLocator(onProgress)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Result.failure(WpmeException("Could not import the book: ${e.message ?: "unknown error"}")) }
    }

    /** Finds where the book teaches each deck word. Does nothing (and removes the old file) until both the deck and the book exist. Call it after importing either one. */
    suspend fun rebuildLocator(onProgress: (String) -> Unit = {}): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sid = sourceId
            prefs.edit().putInt(KEY_LOC_VERSION, LOC_VERSION).apply()
            val items = deck.all()
            val list = sid?.let { chapters(it) }.orEmpty()
            if (sid == null || list.isEmpty() || items.none { it.kind == WpmeData.WORD }) {
                file.delete(); locator = emptyMap(); publish()
                return@withContext Result.success(0)
            }
            onProgress("Finding the words in the book")
            val chunks = list.map { c -> WpmeChunk(c.title, articles.observeOne(c.id).first()?.body.orEmpty()) }
            val found = WpmeLocator.locate(chunks, items)
            val tmp = File(ctx.filesDir, WpmeLocator.FILE + ".tmp")
            tmp.writeText(WpmeLocator.encode(found.values))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw WpmeException("Could not save the book links on this phone.") }
            locator = found
            publish()
            Result.success(found.size)
        } catch (e: CancellationException) { throw e
        } catch (e: WpmeException) { Result.failure(e)
        } catch (e: Exception) { Result.failure(WpmeException("Could not link the deck to the book: ${e.message ?: "unknown error"}")) }
    }

    /** The passage for "See more", cut from the book now. Null when there is no book or the word is not in it. */
    suspend fun excerpt(key: String): WpmeExcerpt? = withContext(Dispatchers.IO) {
        val (body, loc) = bodyFor(key) ?: return@withContext null
        WpmePassage.excerpt(body, loc.offset)
    }

    /**
     * Prepares "Read in the book": saves the reader position of the passage (paragraph and character, which the reader resumes
     * from) and returns the article id to open. This replaces the saved reading position of that chapter. Null when the item is not located.
     */
    suspend fun openAt(key: String): String? = withContext(Dispatchers.IO) {
        val sid = sourceId ?: return@withContext null
        val loc = loadLocator()[key] ?: return@withContext null
        val ref = chapters(sid).getOrNull(loc.chunk) ?: return@withContext null
        val body = articles.observeOne(ref.id).first()?.body ?: return@withContext null
        val pos = WpmePassage.position(body, loc.offset)
        reader.saveProgress(settings.ensureUserId(), ref.id, pos.para, pos.ch, pos.percent)
        // The reader lights up the word for a moment so the eye finds it at once.
        var e = loc.offset
        while (e < body.length && (body[e].isLetter() || body[e] == '-' || body[e] == '\u2019' || body[e] == '\'')) e++
        if (e > loc.offset) app.fluvour.ui.ReaderFlash.show(pos.para, pos.ch, pos.ch + (e - loc.offset))
        ref.id
    }

    private suspend fun bodyFor(key: String): Pair<String, WpmeLocation>? {
        val sid = sourceId ?: return null
        val loc = loadLocator()[key] ?: return null
        val ref = chapters(sid).getOrNull(loc.chunk) ?: return null
        val body = articles.observeOne(ref.id).first()?.body ?: return null
        return body to loc
    }

    /** Removes the book imported here from the Read tab, and the links to it. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        sourceId?.let { val now = System.currentTimeMillis(); articles.softDeleteBySource(it, now); sources.softDelete(it, now) }
        prefs.edit().remove(KEY_SOURCE).apply()
        file.delete(); locator = emptyMap(); chapterCache = null; _info.value = null
    }

    private companion object { const val KEY_SOURCE = "bookSourceId"; const val KEY_LOC_VERSION = "locatorVersion"; const val LOC_VERSION = 3 }
}
