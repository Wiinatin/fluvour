package app.fluvour.reading

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.github.luben.zstd.ZstdInputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

class WpmeException(message: String) : Exception(message)

/**
 * The Word Power deck from the user's own Anki file (.apkg), imported on the phone and kept in one small private file. Nothing is
 * uploaded or synced, and nothing of the deck ships with the app. Same pattern as [HeisigRepository], plus the sub-deck names,
 * because the chapter order lives there.
 */
@Singleton
class WpmeRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val _count = MutableStateFlow<Int?>(null)
    /** How many items (elements and words) are imported; null until the file has been looked at. */
    val count: StateFlow<Int?> = _count.asStateFlow()

    @Volatile private var items: List<WpmeItem>? = null
    private val file get() = File(ctx.filesDir, WpmeData.FILE)

    private fun load(): List<WpmeItem> = items ?: run {
        val l = if (file.exists()) runCatching { file.bufferedReader().useLines { WpmeData.decode(it) } }.getOrDefault(emptyList()) else emptyList()
        items = l; _count.value = l.size
        l
    }

    suspend fun refresh() { withContext(Dispatchers.IO) { load() } }

    /** Every imported item: elements first, then words, each in chapter order. */
    suspend fun all(): List<WpmeItem> = withContext(Dispatchers.IO) { load() }

    suspend fun get(key: String): WpmeItem? = withContext(Dispatchers.IO) { load().firstOrNull { it.key == key } }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete(); items = emptyList(); _count.value = 0
    }

    /** Reads an .apkg: a zip holding the collection database (zstd-compressed `collection.anki21b` in new exports, plain SQLite in older ones). */
    suspend fun import(uri: Uri, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "wpme-import").apply { deleteRecursively(); mkdirs() }
        try {
            onProgress("Opening the file")
            val saved = HashMap<String, File>()
            val input = ctx.contentResolver.openInputStream(uri) ?: throw WpmeException("Could not open the file.")
            input.use {
                ZipInputStream(it).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name in setOf("collection.anki21b", "collection.anki21", "collection.anki2")) {
                            val f = File(dir, e.name)
                            f.outputStream().use { out -> zip.copyTo(out) }
                            saved[e.name] = f
                        }
                    }
                }
            }
            val raw = saved["collection.anki21b"]?.let { z ->
                onProgress("Unpacking the deck")
                val db = File(dir, "collection.sqlite")
                ZstdInputStream(z.inputStream()).use { zs -> db.outputStream().use { out -> zs.copyTo(out) } }
                db
            } ?: saved["collection.anki21"] ?: saved["collection.anki2"]
            ?: throw WpmeException("This is not an Anki deck export (.apkg).")
            onProgress("Reading the notes")
            val notes = ArrayList<WpmeNote>(4_000)
            val db = SQLiteDatabase.openDatabase(raw.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val names = deckNames(db)
                db.rawQuery("select n.flds, c.did from notes n join cards c on c.nid = n.id order by n.id, c.ord", null).use { c ->
                    while (c.moveToNext()) notes.add(WpmeNote(c.getString(0).split('\u001f'), names[c.getLong(1)] ?: ""))
                }
            } finally { db.close() }
            onProgress("Sorting the cards")
            val list = WpmeData.fromNotes(notes.asSequence())
            if (list.none { it.kind == WpmeData.WORD }) throw WpmeException("No Word Power cards found. Use the \"Word Power Made Easy\" deck.")
            val tmp = File(ctx.filesDir, WpmeData.FILE + ".tmp")
            tmp.writeText(WpmeData.encode(list))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw WpmeException("Could not save the deck on this phone.") }
            items = null; load()
            Result.success(list.size)
        } catch (e: CancellationException) { throw e
        } catch (e: WpmeException) { Result.failure(e)
        } catch (e: Exception) { Result.failure(WpmeException("Could not import this: ${e.message ?: "unknown error"}"))
        } finally { dir.deleteRecursively() }
    }

    /** Deck id to name. New exports (`anki21b`) have a `decks` table, legacy ones a JSON text in `col.decks`. */
    private fun deckNames(db: SQLiteDatabase): Map<Long, String> {
        val hasTable = db.rawQuery("select 1 from sqlite_master where type = 'table' and name = 'decks'", null).use { it.moveToFirst() }
        if (hasTable) {
            val m = HashMap<Long, String>()
            runCatching {
                db.rawQuery("select id, name from decks", null).use { c -> while (c.moveToNext()) m[c.getLong(0)] = WpmeData.deckName(c.getString(1) ?: "") }
            }
            if (m.isNotEmpty()) return m
        }
        val json = runCatching { db.rawQuery("select decks from col", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" } }.getOrDefault("") ?: ""
        return WpmeData.deckNamesFromJson(json)
    }
}
