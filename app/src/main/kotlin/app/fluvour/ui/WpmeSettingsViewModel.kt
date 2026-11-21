package app.fluvour.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.reading.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Word Power settings group shows while something runs (deck import, book import, link building) and the last result. */
data class WpmeSettingsUi(val busy: Boolean = false, val progress: String? = null, val message: String? = null)

/** Settings > Word Power deck: import your .apkg and your book (.epub), build the word links, see the counts, remove each. Nothing is uploaded or synced. */
@HiltViewModel
class WpmeSettingsViewModel @Inject constructor(
    private val deck: WpmeRepository,
    private val book: WpmeBookRepository,
    private val links: WpmeLinksRepository,
    private val study: WpmeStudyRepository,
) : ViewModel() {
    val count: StateFlow<Int?> = deck.count
    val bookInfo: StateFlow<WpmeBookInfo?> = book.info
    val linkCount: StateFlow<Int?> = links.count
    val newPerDay: StateFlow<Int> = study.newPerDay
    fun setNewPerDay(n: Int) = study.setNewPerDay(n)

    private val _ui = MutableStateFlow(WpmeSettingsUi())
    val ui: StateFlow<WpmeSettingsUi> = _ui.asStateFlow()

    init { viewModelScope.launch { deck.refresh(); book.refresh(); links.refresh() } }

    private fun step(text: String) = _ui.update { it.copy(progress = text) }
    private fun done(message: String) { _ui.value = WpmeSettingsUi(message = message) }

    fun importDeck(uri: Uri) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = WpmeSettingsUi(busy = true, progress = "Opening the file")
            deck.import(uri, ::step).fold(
                onSuccess = { n ->
                    // The book may be imported already: find the words in it now.
                    book.rebuildLocator(::step)
                    done("Imported $n items.")
                },
                onFailure = { done(it.message ?: "Could not import this deck.") }
            )
        }
    }

    fun importBook(uri: Uri) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = WpmeSettingsUi(busy = true, progress = "Opening the book")
            book.importBook(uri, ::step).fold(
                onSuccess = { n ->
                    done(if ((deck.count.value ?: 0) == 0) "Book imported. Import the deck too, so the words can be found in it." else "Book imported. $n words found in it.")
                },
                onFailure = { done(it.message ?: "Could not import this book.") }
            )
        }
    }

    /** [chapter] = a deck chapter number for the trial run, null for every chapter. */
    fun buildLinks(chapter: Int?) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = WpmeSettingsUi(busy = true, progress = "Starting")
            links.build(chapter, ::step).fold(
                onSuccess = { r ->
                    val sample = if (chapter != null) sampleLines(chapter) else ""
                    val stop = r.stopped?.let { " Stopped: $it Run it again later to continue." }.orEmpty()
                    val miss = if (r.noPassage > 0) " ${r.noPassage} words are not in the book." else ""
                    done("Linked ${r.linked} words, ${r.plain} stay plain, ${r.left} left.$miss$stop$sample")
                },
                onFailure = { done(it.message ?: "Could not build the word links.") }
            )
        }
    }

    // For the trial: a few real results to look at, so the links can be judged before the rest is built.
    private suspend fun sampleLines(chapter: Int): String {
        val inChapter = deck.all().filter { it.chapter == chapter }.associateBy { it.key }
        val shown = links.all().values.filter { it.parts.isNotEmpty() && it.key in inChapter }.take(8)
        if (shown.isEmpty()) return ""
        return "\n" + shown.joinToString("\n") { l -> "${inChapter.getValue(l.key).form}: ${l.parts.joinToString(" + ")} (${l.gloss})" }
    }

    fun clearDeck() { viewModelScope.launch { deck.clear(); links.clear(); book.rebuildLocator(); done("Deck removed.") } }
    fun clearBook() { viewModelScope.launch { book.clear(); done("Book removed.") } }
    fun clearLinks() { viewModelScope.launch { links.clear(); done("Word links removed.") } }
}

/** The Word Power tab on the Review screen: how far you are, and what is waiting. */
@HiltViewModel
class WpmeHomeViewModel @Inject constructor(private val study: WpmeStudyRepository, private val deck: WpmeRepository) : ViewModel() {
    private val _stats = MutableStateFlow<WpmeStats?>(null)
    val stats: StateFlow<WpmeStats?> = _stats.asStateFlow()
    val newPerDay: StateFlow<Int> = study.newPerDay

    fun refresh() { viewModelScope.launch { deck.refresh(); _stats.value = study.stats() } }
}
