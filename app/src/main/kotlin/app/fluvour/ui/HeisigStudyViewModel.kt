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

data class HeisigStudyUi(
    val loading: Boolean = true, val noDeck: Boolean = false,
    val current: HeisigEntry? = null, val kanji: KanjiEntry? = null,
    val showAnswer: Boolean = false, val previews: Map<Rating, Long> = emptyMap(),
    val left: Int = 0, val answered: Int = 0, val remembered: Int = 0,
    val finished: Boolean = false, val canLearnMore: Boolean = false
) {
    val progress: Float get() { val total = answered + left; return if (total == 0) 0f else answered.toFloat() / total }
}

@HiltViewModel
class HeisigStudyViewModel @Inject constructor(
    private val heisig: HeisigRepository, private val study: HeisigStudyRepository, private val kanjiRepo: KanjiRepository,
    private val strokeRepo: StrokeRepository
) : ViewModel() {
    private val queue = ArrayDeque<HeisigEntry>()
    private var answered = 0
    private var remembered = 0
    private var busy = false
    private var canLearnMore = true

    private val _ui = MutableStateFlow(HeisigStudyUi())
    val ui: StateFlow<HeisigStudyUi> = _ui.asStateFlow()

    /** Stroke-order data for the back of the card; null when KanjiVG has no entry (the box is then left out). */
    suspend fun strokes(ch: String): KanjiStrokes? = strokeRepo.strokes(ch)

    init {
        viewModelScope.launch {
            heisig.refresh()
            if ((heisig.count.value ?: 0) == 0) { _ui.value = HeisigStudyUi(loading = false, noDeck = true); return@launch }
            queue.addAll(study.session())
            publish()
        }
    }

    private suspend fun publish() {
        val head = queue.firstOrNull()
        _ui.value = HeisigStudyUi(
            loading = false, current = head, kanji = head?.let { kanjiRepo.entry(it.ch) },
            showAnswer = false, previews = head?.let { study.preview(it.ch) } ?: emptyMap(),
            left = queue.size, answered = answered, remembered = remembered, finished = head == null, canLearnMore = canLearnMore
        )
    }

    fun reveal() { if (_ui.value.current != null) _ui.value = _ui.value.copy(showAnswer = true) }

    fun rate(rating: Rating) {
        val card = queue.firstOrNull() ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val after = study.review(card.ch, rating)
                answered++
                if (rating == Rating.GOOD || rating == Rating.EASY) remembered++
                queue.removeFirst()
                // A kanji still in its learning steps comes back in this session if it is due soon.
                val inSteps = after.phase == Phase.LEARNING || after.phase == Phase.RELEARNING
                if (inSteps && (after.dueMillis ?: 0L) <= System.currentTimeMillis() + LEARN_AHEAD_MS) queue.addLast(card)
                publish()
            } finally { busy = false }
        }
    }

    /** Maturity of one kanji now (before it is rated), for the tint on the Answer box. */
    suspend fun tier(ch: String): Tier = study.tier(ch)

    /** After the daily set is done: add a few more new kanji, in frame order. */
    fun learnMore() {
        viewModelScope.launch {
            val more = study.moreNew(MORE)
            if (more.isEmpty()) canLearnMore = false else queue.addAll(more)
            publish()
        }
    }

    private companion object { const val LEARN_AHEAD_MS = 20 * 60_000L; const val MORE = 5 }
}

/** The Heisig tab on the Review screen: how far you are, and what is waiting. */
@HiltViewModel
class HeisigHomeViewModel @Inject constructor(private val study: HeisigStudyRepository, private val heisig: HeisigRepository) : ViewModel() {
    private val _stats = MutableStateFlow<HeisigStats?>(null)
    val stats: StateFlow<HeisigStats?> = _stats.asStateFlow()
    val newPerDay: StateFlow<Int> = study.newPerDay
    val practiceTrace: StateFlow<Boolean> = study.practiceTrace
    val practiceStory: StateFlow<Boolean> = study.practiceStory
    fun setPracticeTrace(on: Boolean) = study.setPracticeTrace(on)
    fun setPracticeStory(on: Boolean) = study.setPracticeStory(on)
    private val _tiers = MutableStateFlow<Map<String, Tier>>(emptyMap())
    /** Maturity per studied kanji, for the frames on the grid. Worked out when the tab opens, never stored. */
    val tiers: StateFlow<Map<String, Tier>> = _tiers.asStateFlow()
    private val _due = MutableStateFlow<Set<String>>(emptySet())
    /** Kanji whose card is due today: a small dot on their tile. */
    val due: StateFlow<Set<String>> = _due.asStateFlow()
    private val _entries = MutableStateFlow<List<HeisigEntry>>(emptyList())
    /** Every kanji in the Heisig deck, in frame order: the practice grid on the tab (not only due or learned ones). */
    val entries: StateFlow<List<HeisigEntry>> = _entries.asStateFlow()

    fun refresh() { viewModelScope.launch { heisig.refresh(); _stats.value = study.stats(); _entries.value = heisig.all(); _tiers.value = study.tiers(); _due.value = study.dueToday() } }
}

/** The Heisig practice page: the deck, the two remembered switches and stroke data. Nothing about a drawing is kept here. */
@HiltViewModel
class HeisigPracticeViewModel @Inject constructor(
    private val study: HeisigStudyRepository, private val heisig: HeisigRepository, private val strokeRepo: StrokeRepository
) : ViewModel() {
    private val _all = MutableStateFlow<List<HeisigEntry>?>(null)
    /** Every kanji in frame order; null while loading. */
    val all: StateFlow<List<HeisigEntry>?> = _all.asStateFlow()
    val traceGuide: StateFlow<Boolean> = study.practiceTrace
    val story: StateFlow<Boolean> = study.practiceStory
    fun setTraceGuide(on: Boolean) = study.setPracticeTrace(on)
    fun setStory(on: Boolean) = study.setPracticeStory(on)
    suspend fun strokes(ch: String): KanjiStrokes? = strokeRepo.strokes(ch)
    val submits: StateFlow<Map<String, Int>> = study.submits
    fun addSubmit(ch: String) = study.addSubmit(ch)
    private val _tiers = MutableStateFlow<Map<String, Tier>>(emptyMap())
    val tiers: StateFlow<Map<String, Tier>> = _tiers.asStateFlow()

    init { viewModelScope.launch { _all.value = heisig.all(); _tiers.value = study.tiers() } }
}
