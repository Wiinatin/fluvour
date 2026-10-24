package app.fluvour.reading

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.fluvour.data.AppDatabase
import app.fluvour.data.ArticleDao
import app.fluvour.data.ArticleEntity
import app.fluvour.data.SourceDao
import app.fluvour.data.SourceEntity
import app.fluvour.language.Languages
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2A step 8: book, file and pasted-text import. Everything is saved as one `sources` row plus one `articles` row per
 * chapter (ordinal 0, 1, 2...), so the reader, tap-to-lookup, sentence mining, cloze and coverage work unchanged.
 * Pasted text and files are both read in full on the phone; nothing is uploaded. Only the source row (title, author,
 * format) is synced, like any other source; the text stays on the device, as articles do.
 */
@Singleton
class ImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase, private val articles: ArticleDao, private val sources: SourceDao
) {
    data class Imported(val sourceId: String, val firstArticleId: String, val title: String, val chapters: Int)

    suspend fun importText(userId: String, lang: String, raw: String): Result<Imported> =
        attempt { save(userId, lang, TextImport.fromText(raw, null), "paste") }

    suspend fun importFile(userId: String, lang: String, uri: Uri): Result<Imported> = attempt {
        val name = displayName(uri) ?: "Imported file"
        val base = name.substringBeforeLast('.', name).ifBlank { "Imported file" }
        val ext = name.substringAfterLast('.', "").lowercase()
        when (ext) {
            "epub" -> {
                val book = context.contentResolver.openInputStream(uri)?.use { EpubReader.read(it, base) } ?: throw ImportException("Could not open the file.")
                save(userId, lang, book, "epub")
            }
            "txt", "text", "md", "markdown" -> save(userId, lang, TextImport.fromText(TextImport.decode(readBytes(uri), lang), base), "txt")
            "html", "htm", "xhtml" -> save(userId, lang, TextImport.fromHtml(TextImport.decode(readBytes(uri), lang), base), "html")
            else -> throw ImportException("This file type is not supported yet. Use .epub, .txt or .html.")
        }
    }

    private suspend fun attempt(block: suspend () -> Imported): Result<Imported> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: ImportException) { Result.failure(e) }
        catch (e: OutOfMemoryError) { Result.failure(ImportException("This file is too large to import on this phone.")) }
        catch (e: Exception) { Result.failure(ImportException("Could not import this: ${e.message ?: "unknown error"}")) }
    }

    private suspend fun save(userId: String, lang: String, book: ParsedBook, format: String): Imported {
        // A book that says it is in another of the app's languages would be split and looked up with the wrong engine.
        val declared = EpubReader.languageCode(book.language)
        if (declared != null && declared != lang)
            throw ImportException("This book is in ${Languages.byCode(declared).name}. Switch your target language to ${Languages.byCode(declared).name} in Settings, then import it again.")
        val now = System.currentTimeMillis()
        val sourceId = UUID.randomUUID().toString()
        val meta = JSONObject().put("format", format).put("chapters", book.chapters.size).apply { book.author?.let { put("author", it) } }.toString()
        val rows = book.chapters.mapIndexed { i, ch ->
            ArticleEntity(UUID.randomUUID().toString(), userId, sourceId, lang, ch.title, ch.text, "import:$sourceId#$i", LICENSE, now, now, ordinal = i)
        }
        db.withTransaction {
            sources.upsert(SourceEntity(sourceId, userId, SOURCE_KIND, book.title, null, meta, now, now))
            articles.upsertAll(rows)
        }
        return Imported(sourceId, rows.first().id, book.title, rows.size)
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment

    private fun readBytes(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("Could not open the file.")
        input.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_TEXT_FILE) throw ImportException("This file is larger than 40 MB. Split it into smaller files.")
            }
            return out.toByteArray()
        }
    }

    companion object {
        /** The backend only accepts article, video, comic, speaking and manual for `sources.kind`; imports use manual, and `meta.format` says how. */
        const val SOURCE_KIND = "manual"
        const val LICENSE = "Your own file"
        private const val MAX_TEXT_FILE = 40 * 1024 * 1024

        /** The author saved in a source's `meta`, if any. */
        fun author(meta: String?): String? = try { meta?.let { JSONObject(it).optString("author").takeIf { a -> a.isNotBlank() } } } catch (e: Exception) { null }
    }
}
