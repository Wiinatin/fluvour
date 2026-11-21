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

/** One kanji from the user's own Heisig deck: frame number, keyword, community stories and component list. */
data class HeisigEntry(val ch: String, val frame: Int, val keyword: String, val story1: String, val story2: String, val components: String)

class HeisigException(message: String) : Exception(message)

/** Pure parts (no Android): turning Anki note fields into entries, and the small file they are kept in. */
object HeisigData {
    const val FILE = "heisig.tsv"

    // Field order of the "Kanji - Heisig order" note type ("All in one Kanji" deck).
    private const val F_KANJI = 0
    private const val F_COMPONENTS = 9
    private const val F_KEYWORD = 17
    private const val F_STORY1 = 18
    private const val F_STORY2 = 19
    private const val F_FRAME = 20
    private const val FIELD_COUNT = 21

    fun htmlToText(html: String): String {
        var t = html.replace(Regex("(?i)<br\\s*/?>|</div>|</p>|<div[^>]*>|<p[^>]*>"), "\n").replace(Regex("<[^>]*>"), "")
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        return t.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Notes with a numeric frame number in the Heisig field; the first note wins when a kanji appears twice. Sorted by frame. */
    fun fromNotes(notes: Sequence<List<String>>): List<HeisigEntry> {
        val out = LinkedHashMap<String, HeisigEntry>()
        for (f in notes) {
            if (f.size < FIELD_COUNT) continue
            val ch = f[F_KANJI].trim()
            if (ch.isEmpty() || ch.codePointCount(0, ch.length) != 1) continue
            val frame = f[F_FRAME].trim().toIntOrNull()?.takeIf { it > 0 } ?: continue
            if (ch in out) continue
            out[ch] = HeisigEntry(ch, frame, htmlToText(f[F_KEYWORD]), htmlToText(f[F_STORY1]), htmlToText(f[F_STORY2]), htmlToText(f[F_COMPONENTS]))
        }
        return out.values.sortedBy { it.frame }
    }

    private fun esc(s: String) = s.replace('\t', ' ').replace("\r", "").replace("\n", "\\n")
    private fun unesc(s: String) = s.replace("\\n", "\n")

    fun encode(list: List<HeisigEntry>): String =
        list.joinToString("\n") { listOf(it.ch, it.frame.toString(), esc(it.keyword), esc(it.story1), esc(it.story2), esc(it.components)).joinToString("\t") }

    fun decode(lines: Sequence<String>): Map<String, HeisigEntry> {
        val m = HashMap<String, HeisigEntry>(4_000)
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 6) continue
            val frame = p[1].toIntOrNull() ?: continue
            m[p[0]] = HeisigEntry(p[0], frame, unesc(p[2]), unesc(p[3]), unesc(p[4]), unesc(p[5]))
        }
        return m
    }
}

/**
 * Heisig "Remembering the Kanji" from the user's own Anki deck (.apkg). Imported on the phone with a file picker and kept in one
 * small private file; nothing is uploaded or synced, and nothing of the deck ships with the app. Heisig gives the keyword and
 * frame number; readings still come from KANJIDIC2.
 */
@Singleton
class HeisigRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val _count = MutableStateFlow<Int?>(null)
    /** How many kanji are imported; null until the file has been looked at. */
    val count: StateFlow<Int?> = _count.asStateFlow()

    @Volatile private var map: Map<String, HeisigEntry>? = null
    private val file get() = File(ctx.filesDir, HeisigData.FILE)

    private fun load(): Map<String, HeisigEntry> = map ?: run {
        val m = if (file.exists()) runCatching { file.bufferedReader().useLines { HeisigData.decode(it) } }.getOrDefault(emptyMap()) else emptyMap()
        map = m; _count.value = m.size
        m
    }

    suspend fun refresh() { withContext(Dispatchers.IO) { load() } }

    /** Every imported kanji, in frame order. */
    suspend fun all(): List<HeisigEntry> = withContext(Dispatchers.IO) { load().values.sortedBy { it.frame } }

    suspend fun get(ch: String): HeisigEntry? = withContext(Dispatchers.IO) { load()[ch] }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete(); map = emptyMap(); _count.value = 0
    }

    /** Reads an .apkg: a zip holding the collection database (zstd-compressed `collection.anki21b` in new exports, plain SQLite in older ones). */
    suspend fun import(uri: Uri, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "heisig-import").apply { deleteRecursively(); mkdirs() }
        try {
            onProgress("Opening the file")
            val saved = HashMap<String, File>()
            val input = ctx.contentResolver.openInputStream(uri) ?: throw HeisigException("Could not open the file.")
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
            ?: throw HeisigException("This is not an Anki deck export (.apkg).")
            onProgress("Reading the notes")
            val rows = ArrayList<List<String>>()
            val db = SQLiteDatabase.openDatabase(raw.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("select flds from notes", null).use { c -> while (c.moveToNext()) rows.add(c.getString(0).split('\u001f')) }
            } finally { db.close() }
            val entries = HeisigData.fromNotes(rows.asSequence())
            if (entries.isEmpty()) throw HeisigException("No Heisig kanji found. Use the \"All in one Kanji - Heisig order\" deck.")
            val tmp = File(ctx.filesDir, HeisigData.FILE + ".tmp")
            tmp.writeText(HeisigData.encode(entries))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw HeisigException("Could not save the deck on this phone.") }
            map = null; load()
            Result.success(entries.size)
        } catch (e: CancellationException) { throw e
        } catch (e: HeisigException) { Result.failure(e)
        } catch (e: Exception) { Result.failure(HeisigException("Could not import this: ${e.message ?: "unknown error"}"))
        } finally { dir.deleteRecursively() }
    }
}
