package app.fluvour.reading

import android.content.Context
import app.fluvour.ai.AiService
import app.fluvour.ai.WpmeLinkTask
import app.fluvour.ai.WpmeLinksPrompt
import app.fluvour.ai.WpmeVerdict
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** How a build went. [left] counts words that have a book passage and no answer yet; [noPassage] words the book did not locate. [stopped] is the reason it stopped early (no key, offline, quota), null when it finished. */
data class WpmeLinksReport(val linked: Int, val plain: Int, val left: Int, val noPassage: Int, val stopped: String?)

/**
 * The word to parts links of the Word Power deck, built from the user's own book. For each word the located passage is sent to the AI
 * with the element list ([WpmeLinksPrompt]); the reply is checked by pure code and cached in `wpme-links.tsv`. A word with no
 * passage, or without a link that passes the check twice, stays a plain word card (no parts, no unlock rule), so nothing ever blocks the user.
 * The passage is sent to the user's own AI keys and is never stored here.
 */
@Singleton
class WpmeLinksRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val ai: AiService,
    private val deck: WpmeRepository,
    private val book: WpmeBookRepository,
) {
    private val file get() = File(ctx.filesDir, WpmeLinks.FILE)
    private companion object { const val ASSET = "wpme-links.tsv" }

    private val _count = MutableStateFlow<Int?>(null)
    /** Words that have a real link (with parts); null until the file has been looked at. */
    val count: StateFlow<Int?> = _count.asStateFlow()

    @Volatile private var cache: Map<String, WpmeLink>? = null

    private fun load(): Map<String, WpmeLink> = cache ?: run {
        // The links that ship with the app (checked against the deck and the book), overlaid by any the user built on this phone.
        val bundled = runCatching { ctx.assets.open(ASSET).bufferedReader().useLines { WpmeLinks.decode(it) } }.getOrDefault(emptyMap())
        val own = if (file.exists()) runCatching { file.bufferedReader().useLines { WpmeLinks.decode(it) } }.getOrDefault(emptyMap()) else emptyMap()
        val m = own + bundled   // bundled wins, so an old "plain" result cannot hide a checked link
        cache = m; _count.value = m.values.count { it.parts.isNotEmpty() }
        m
    }

    suspend fun refresh() { withContext(Dispatchers.IO) { load() } }

    /** The link of an item, or null when it was never tried. A link with no parts means "tried, plain". */
    suspend fun get(key: String): WpmeLink? = withContext(Dispatchers.IO) { load()[key] }

    /** Every tried word, with and without parts. */
    suspend fun all(): Map<String, WpmeLink> = withContext(Dispatchers.IO) { load() }

    /** Removes the links built on this phone. The links that ship with the app stay. */
    suspend fun clear() = withContext(Dispatchers.IO) { file.delete(); cache = null; load() }

    private fun save(m: Map<String, WpmeLink>) {
        val tmp = File(ctx.filesDir, WpmeLinks.FILE + ".tmp")
        tmp.writeText(WpmeLinks.encode(m.values))
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw WpmeException("Could not save the word links on this phone.") }
        cache = m; _count.value = m.values.count { it.parts.isNotEmpty() }
    }

    /**
     * Builds the links that are missing, one deck chapter at a time, in small batches, saving after every batch. [chapter] limits it to
     * one deck chapter (the number NN of the sub-deck) so the first run can be a trial; null runs every chapter. Stops at the first
     * AI failure (no key, offline, limit) and keeps what was done; run it again later to continue. Words the AI could not link
     * cleanly after two tries are saved as plain, so they are not asked again.
     */
    suspend fun build(chapter: Int? = null, onProgress: (String) -> Unit = {}): Result<WpmeLinksReport> = withContext(Dispatchers.IO) {
        try {
            val items = deck.all()
            val elements = items.filter { it.kind == WpmeData.ELEMENT }
            if (elements.isEmpty()) throw WpmeException("Import the Word Power deck first.")
            val done = HashMap(load())
            val todo = items.filter { WpmeLinksPrompt.isLinkable(it) && (chapter == null || it.chapter == chapter) && it.key !in done }
            var linked = 0; var plain = 0; var noPassage = 0
            val tasks = ArrayList<WpmeLinkTask>()
            for (w in todo) {
                val ex = book.excerpt(w.key)
                if (ex == null) { noPassage++; continue }
                tasks += WpmeLinkTask(w, WpmeLinksPrompt.trimPassage(ex.heading, ex.text))
            }
            var left = tasks.size
            var stopped: String? = null
            outer@ for ((chNo, group) in tasks.groupBy { it.item.chapter }.toSortedMap()) {
                for ((bi, batch) in group.chunked(WpmeLinksPrompt.BATCH).withIndex()) {
                    onProgress("Chapter $chNo, words ${bi * WpmeLinksPrompt.BATCH + 1} to ${bi * WpmeLinksPrompt.BATCH + batch.size} of ${group.size}")
                    val first = ask(batch, elements, emptyMap())
                    if (first.error != null) { stopped = first.error; break@outer }
                    val verdicts = first.verdicts!!.toMutableMap()
                    val bad = verdicts.filter { it.value is WpmeVerdict.Rejected }
                    if (bad.isNotEmpty()) {
                        val again = bad.keys.sorted().map { batch[it - 1] }
                        val reasons = bad.keys.sorted().mapIndexed { j, n -> (j + 1) to (bad.getValue(n) as WpmeVerdict.Rejected).reason }.toMap()
                        val second = ask(again, elements, reasons)
                        if (second.error != null) { stopped = second.error; break@outer }
                        bad.keys.sorted().forEachIndexed { j, n -> verdicts[n] = second.verdicts!!.getValue(j + 1) }
                    }
                    for ((n, v) in verdicts) {
                        val t = batch[n - 1]
                        when (v) {
                            is WpmeVerdict.Linked -> { done[t.item.key] = v.link; linked++ }
                            else -> { done[t.item.key] = WpmeLink(t.item.key, emptyList(), ""); plain++ }   // plain, or rejected twice: stays a plain card
                        }
                    }
                    left -= batch.size
                    save(done)
                }
            }
            Result.success(WpmeLinksReport(linked, plain, left, noPassage, stopped))
        } catch (e: CancellationException) { throw e
        } catch (e: WpmeException) { Result.failure(e)
        } catch (e: Exception) { Result.failure(WpmeException("Could not build the word links: ${e.message ?: "unknown error"}")) }
    }

    private class Asked(val verdicts: Map<Int, WpmeVerdict>?, val error: String?)

    private suspend fun ask(batch: List<WpmeLinkTask>, elements: List<WpmeItem>, fixes: Map<Int, String>): Asked {
        val r = ai.text(WpmeLinksPrompt.build(batch, elements, fixes), temperature = 0.1, maxTokens = 4096)
        val text = r.text ?: return Asked(null, r.message ?: "The AI did not answer.")
        val reply = WpmeLinksPrompt.parse(text)
        if (reply.isEmpty()) return Asked(null, "The AI reply could not be read. Try again later.")   // garbage must not turn a whole batch into plain words
        return Asked(WpmeLinksPrompt.checkAll(batch, reply, elements), null)
    }
}
