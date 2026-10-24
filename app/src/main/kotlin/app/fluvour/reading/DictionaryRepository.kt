package app.fluvour.reading

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.fluvour.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One entry of an imported dictionary, as the popup shows it. */
data class ImportedEntry(val dictionary: String, val term: String, val reading: String, val tags: String, val glossary: String)

/** What imported dictionaries know about the tapped word: entries, plus chips (frequency, pitch accent, IPA). */
data class ImportedLookup(val entries: List<ImportedEntry>, val chips: List<String>) {
    val isEmpty: Boolean get() = entries.isEmpty() && chips.isEmpty()
}

/**
 * Phase 2A step 9: Yomitan-format dictionary import and lookup. The zip is read in a stream on the phone and saved in Room
 * (`dictionaries`, `dict_terms`, `dict_meta`), all inside one transaction, so a failed import leaves nothing behind.
 * Nothing is uploaded or synced. Each dictionary belongs to one language (the target language when it was imported).
 */
@Singleton
class DictionaryRepository @Inject constructor(
    @ApplicationContext private val context: Context, private val db: AppDatabase, private val dao: DictDao
) {
    val dictionaries: Flow<List<DictionaryEntity>> = dao.observeAll()

    suspend fun import(lang: String, uri: Uri, onProgress: (String) -> Unit): Result<DictionaryEntity> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val stream = context.contentResolver.openInputStream(uri) ?: throw YomitanException("Could not open the file.")
            val saved = stream.use { input ->
                db.withTransaction {
                    val sink = object : YomitanSink {
                        override suspend fun terms(rows: List<TermRow>) =
                            dao.insertTerms(rows.map { DictTermEntity(dictId = id, term = it.term, reading = it.reading, tags = it.tags, score = it.score, glossary = it.glossary, sequence = it.sequence) })
                        override suspend fun meta(rows: List<MetaRow>) =
                            dao.insertMeta(rows.map { DictMetaEntity(dictId = id, term = it.term, kind = it.kind, reading = it.reading, value = it.value, num = it.num) })
                    }
                    val r = YomitanReader.read(input, sink, onProgress)
                    if (dao.find(lang, r.index.title, r.index.revision) != null)
                        throw YomitanException("\"${r.index.title}\" is already imported for this language. Remove it first to import it again.")
                    val d = DictionaryEntity(id, lang, r.index.title, r.index.revision, r.index.author, r.index.attribution, r.terms, r.meta,
                        enabled = true, priority = dao.countFor(lang), importedAt = System.currentTimeMillis())
                    dao.insertDictionary(d)
                    d
                }
            }
            Result.success(saved)
        } catch (e: CancellationException) { throw e
        } catch (e: YomitanException) { Result.failure(e)
        } catch (e: OutOfMemoryError) { Result.failure(YomitanException("This dictionary is too large to import on this phone."))
        } catch (e: Exception) { Result.failure(YomitanException("Could not import this: ${e.message ?: "unknown error"}")) }
    }

    suspend fun setEnabled(id: String, on: Boolean) = dao.setEnabled(id, on)

    suspend fun delete(id: String) = db.withTransaction { dao.deleteTerms(id); dao.deleteMeta(id); dao.deleteDictionary(id) }

    /** Entries for the tapped word. `forms` are tried together: dictionary form, tapped form, and their lower-case versions. */
    suspend fun lookup(lang: String, vararg words: String?): ImportedLookup = withContext(Dispatchers.IO) {
        val forms = words.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.flatMap { listOf(it, it.lowercase()) }.distinct()
        if (forms.isEmpty()) return@withContext ImportedLookup(emptyList(), emptyList())
        // The query already orders by dictionary (import order), then by the dictionary's own score.
        val entries = dao.terms(lang, forms).map { ImportedEntry(it.dictTitle, it.t.term, it.t.reading, it.t.tags, it.t.glossary) }
        val chips = YomitanReader.chips(dao.meta(lang, forms).map { it.dictTitle to MetaRow(it.m.term, it.m.kind, it.m.reading, it.m.value, it.m.num) })
        ImportedLookup(entries, chips)
    }
}
