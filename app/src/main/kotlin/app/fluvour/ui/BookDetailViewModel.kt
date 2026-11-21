package app.fluvour.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.data.ChapterRef
import app.fluvour.data.LibraryBookEntity
import app.fluvour.data.SettingsRepository
import app.fluvour.reading.ArticleRepository
import app.fluvour.reading.BookDownloader
import app.fluvour.reading.ContinueItem
import app.fluvour.reading.LibraryRepository
import app.fluvour.ui.screens.toCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One library book's detail page. The route argument `libraryId` is the library row id (for example "gutenberg:1342"). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookDetailViewModel @Inject constructor(
    state: SavedStateHandle, private val library: LibraryRepository, private val downloader: BookDownloader,
    private val settingsRepo: SettingsRepository, private val repo: ArticleRepository
) : ViewModel() {
    private val id = state.get<String>("libraryId") ?: ""

    /** The row. Un-hearting a book that is not downloaded deletes its row, so the last seen row is kept (as not hearted) and the page stays put. */
    private var last: LibraryBookEntity? = null
    val book: StateFlow<LibraryBookEntity?> = library.book(id)
        .map { row -> (row ?: last?.copy(shelved = false, sourceId = null, downloadedAt = null))?.also { last = it } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Where Win stopped, once the book is downloaded and has been opened. */
    val progress: StateFlow<ContinueItem?> = book.map { it?.lang }.distinctUntilChanged()
        .flatMapLatest { l -> if (l == null) flowOf(null) else library.continueReading(l).map { list -> list.firstOrNull { it.bookId == id } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Chapters of the downloaded book, in order. The first one is what Read opens. */
    val chapters: StateFlow<List<ChapterRef>> = book.map { it?.sourceId }.distinctUntilChanged()
        .flatMapLatest { sid -> if (sid == null) flowOf(emptyList()) else repo.observeChapters(sid) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    /** The heart. Brings the row back first if it was pruned a moment ago. */
    fun toggleHeart() {
        val b = book.value ?: return
        viewModelScope.launch {
            b.toCatalog()?.let { library.ensure(it, b.lang) }
            library.setShelved(id, !b.shelved)
        }
    }

    /** Downloads the book for offline reading. The page stays open and swaps Download for Read. */
    fun download() {
        val b = book.value ?: return
        if (_busy.value) return
        val cb = b.toCatalog() ?: run { _message.value = "This book has no download link."; return }
        _busy.value = true; _message.value = null
        viewModelScope.launch {
            downloader.download(settingsRepo.ensureUserId(), b.lang, cb).fold(
                onSuccess = { _busy.value = false },
                onFailure = { _busy.value = false; _message.value = it.message ?: "Could not download the book." }
            )
        }
    }

    /**
     * Removes the book from this phone (the user confirmed). Cards and mined sentences made from it stay.
     * A catalog book keeps its heart and can be downloaded again; an own file is forgotten entirely (row, heart and cover).
     */
    fun removeDownload(done: () -> Unit) {
        val b = book.value ?: return
        viewModelScope.launch {
            b.sourceId?.let { repo.deleteSource(it) }
            if (b.origin == "file") b.sourceId?.let { library.forgetOwnFile(it) } else library.clearDownload(id)
            done()
        }
    }
}
