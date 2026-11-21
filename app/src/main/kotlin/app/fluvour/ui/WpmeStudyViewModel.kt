package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.reading.*
import app.fluvour.srs.Phase
import app.fluvour.srs.Rating
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the card back offers about the user's book: nothing (elements, words the book does not mention), the passage, or a prompt to import the book. */
enum class WpmeBookState { NONE, PASSAGE, NO_BOOK }

data class WpmeStudyUi(
    val loading: Boolean = true, val noDeck: Boolean = false,
    val current: WpmeItem? = null, val parts: List<WpmePartInfo> = emptyList(), val gloss: String = "",
    val bookState: WpmeBookState = WpmeBookState.NONE, val excerpt: WpmeExcerpt? = null, val note: String? = null,
    val showAnswer: Boolean = false, val previews: Map<Rating, Long> = emptyMap(),
    val left: Int = 0, val answered: Int = 0, val remembered: Int = 0,
    val finished: Boolean = false, val canLearnMore: Boolean = false
) {
    val progress: Float get() { val total = answered + left; return if (total == 0) 0f else answered.toFloat() / total }
}

/** Word Power study: the Heisig screen's flow with a layered card back (parts, meaning, See more, Read in the book). */
@HiltViewModel
class WpmeStudyViewModel @Inject constructor(
    private val deck: WpmeRepository, private val study: WpmeStudyRepository,
    private val links: WpmeLinksRepository, private val book: WpmeBookRepository
) : ViewModel() {
    private val queue = ArrayDeque<WpmeItem>()
    private var answered = 0
    private var remembered = 0
    private var busy = false
    private var canLearnMore = true

    private val _ui = MutableStateFlow(WpmeStudyUi())
    val ui: StateFlow<WpmeStudyUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            deck.refresh(); links.refresh(); book.refresh()
            if ((deck.count.value ?: 0) == 0) { _ui.value = WpmeStudyUi(loading = false, noDeck = true); return@launch }
            queue.addAll(study.session())
            publish()
        }
    }

    private suspend fun publish() {
        val head = queue.firstOrNull()
        val isWord = head?.kind == WpmeData.WORD
        _ui.value = WpmeStudyUi(
            loading = false, current = head,
            parts = if (isWord) study.parts(head!!.key) else emptyList(),
            gloss = if (isWord) links.get(head!!.key)?.gloss.orEmpty() else "",
            bookState = when {
                head == null -> WpmeBookState.NONE
                book.info.value == null -> WpmeBookState.NO_BOOK
                book.hasPassage(head!!.key) -> WpmeBookState.PASSAGE
                else -> WpmeBookState.NONE
            },
            showAnswer = false, previews = head?.let { study.preview(it.key) } ?: emptyMap(),
            left = queue.size, answered = answered, remembered = remembered, finished = head == null, canLearnMore = canLearnMore
        )
    }

    /** Shows the answer. When the book has a passage for this card it is loaded at once and shown on the back, so there is nothing to tap. */
    fun reveal() {
        val cur = _ui.value
        val item = cur.current ?: return
        _ui.value = cur.copy(showAnswer = true)
        if (cur.bookState == WpmeBookState.PASSAGE && cur.excerpt == null) viewModelScope.launch {
            val ex = book.excerpt(item.key)
            if (_ui.value.current?.key == item.key) _ui.value = _ui.value.copy(excerpt = ex)
        }
    }

    fun rate(rating: Rating) {
        val card = queue.firstOrNull() ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val after = study.review(card.key, rating)
                answered++
                if (rating == Rating.GOOD || rating == Rating.EASY) remembered++
                queue.removeFirst()
                // An item still in its learning steps comes back in this session if it is due soon.
                val inSteps = after.phase == Phase.LEARNING || after.phase == Phase.RELEARNING
                if (inSteps && (after.dueMillis ?: 0L) <= System.currentTimeMillis() + LEARN_AHEAD_MS) queue.addLast(card)
                publish()
            } finally { busy = false }
        }
    }

    /** "See more": shows the passage of the user's book, cut from it now; tap again to hide it. */
    fun seeMore() {
        val cur = _ui.value
        val item = cur.current ?: return
        if (cur.excerpt != null) { _ui.value = cur.copy(excerpt = null, note = null); return }
        viewModelScope.launch {
            val ex = book.excerpt(item.key)
            _ui.value = _ui.value.copy(excerpt = ex, note = if (ex == null) "The passage could not be found in the book." else null)
        }
    }

    /** "Read in the book": saves the reader position of the passage and hands the reader's article id to [onOpen]. */
    fun readInBook(onOpen: (String) -> Unit) {
        val item = _ui.value.current ?: return
        viewModelScope.launch {
            val id = book.openAt(item.key)
            if (id == null) _ui.value = _ui.value.copy(note = "Could not open the book at this word.") else onOpen(id)
        }
    }

    /** After the daily set is done: add a few more new items (elements first, then words whose parts are known). */
    fun learnMore() {
        viewModelScope.launch {
            val more = study.moreNew(MORE)
            if (more.isEmpty()) canLearnMore = false else queue.addAll(more)
            publish()
        }
    }

    private companion object { const val LEARN_AHEAD_MS = 20 * 60_000L; const val MORE = 5 }
}
