package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.audio.TtsManager
import app.fluvour.data.CardEntity
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.reading.Cloze
import app.fluvour.reading.ClozeText
import app.fluvour.reading.WordSplitter
import app.fluvour.srs.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.fluvour.srs.Rating
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewUi(
    val loading: Boolean = true,
    val current: CardEntity? = null,
    val showAnswer: Boolean = false,
    val previews: Map<Rating, Long> = emptyMap(),   // delay until due for each button
    val newLeft: Int = 0, val learningLeft: Int = 0, val reviewLeft: Int = 0,
    val answered: Int = 0, val remembered: Int = 0,
    val finished: Boolean = false,
    val cloze: ClozeText? = null    // set when the current card is shown as a fill-in-the-blank (card made from a mined sentence)
) {
    val progress: Float get() { val total = answered + newLeft + learningLeft + reviewLeft; return if (total == 0) 0f else answered.toFloat() / total }
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val cardRepo: CardRepository,
    private val tts: TtsManager
) : ViewModel() {
    private val queue = ArrayDeque<CardEntity>()
    private var answered = 0
    private var remembered = 0
    private var busy = false
    private var autoPlay = false

    private val _voiceMissing = MutableStateFlow(false)
    /** True when the phone has no voice installed for the language of the last spoken text. */
    val voiceMissing: StateFlow<Boolean> = _voiceMissing.asStateFlow()

    private val _ui = MutableStateFlow(ReviewUi())
    val ui: StateFlow<ReviewUi> = _ui.asStateFlow()

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        val settings = settingsRepo.settings.first()
        autoPlay = settings.autoPlay
        val lang = settings.targetLang
        val due = cardRepo.dueCards(lang, 200)
        // Cards already in progress first, then a capped number of new ones.
        queue.addAll(due.filter { it.phase != Phase.NEW.name })
        queue.addAll(due.filter { it.phase == Phase.NEW.name }.take(NEW_PER_SESSION))
        publish(showAnswer = false)
        autoSpeakHead()
    }

    // Not for cloze cards: saying the word would give the answer away.
    private fun autoSpeakHead() { if (autoPlay && _ui.value.cloze == null) queue.firstOrNull()?.let { speak(it.word, it.languageCode) } }

    fun speak(text: String, langTag: String) {
        _voiceMissing.value = tts.speak(text, langTag) == TtsManager.Result.NO_VOICE
    }

    override fun onCleared() { tts.stop() }

    /** Phase 2A step 5: a card made from a mined sentence is shown with its word blanked out. Cards without a sentence, or whose sentence no longer holds the word, stay normal. */
    private suspend fun clozeFor(card: CardEntity?): ClozeText? {
        val sentence = card?.example?.takeIf { card.sentenceId != null && it.isNotBlank() } ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { Cloze.build(sentence, card.word, WordSplitter.split(sentence, card.languageCode)) }.getOrNull()
        }
    }

    private suspend fun publish(showAnswer: Boolean) {
        val head = queue.firstOrNull()
        val cloze = clozeFor(head)
        _ui.value = ReviewUi(
            loading = false, current = head, showAnswer = showAnswer,
            previews = head?.let { cardRepo.preview(it) } ?: emptyMap(),
            newLeft = queue.count { it.phase == Phase.NEW.name },
            learningLeft = queue.count { it.phase == Phase.LEARNING.name || it.phase == Phase.RELEARNING.name },
            reviewLeft = queue.count { it.phase == Phase.REVIEW.name },
            answered = answered, remembered = remembered, finished = head == null, cloze = cloze
        )
    }

    fun reveal() { if (_ui.value.current != null) _ui.value = _ui.value.copy(showAnswer = true) }

    fun rate(rating: Rating) {
        val card = queue.firstOrNull() ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val updated = cardRepo.review(card, rating)
                answered++
                if (rating == Rating.GOOD || rating == Rating.EASY) remembered++
                queue.removeFirst()
                // Cards still in learning steps come back in this session if they are due soon.
                val inSteps = updated.phase == Phase.LEARNING.name || updated.phase == Phase.RELEARNING.name
                if (inSteps && (updated.dueAt ?: 0L) <= System.currentTimeMillis() + LEARN_AHEAD_MS) queue.addLast(updated)
                publish(showAnswer = false)
                autoSpeakHead()
            } finally { busy = false }
        }
    }

    private companion object {
        const val NEW_PER_SESSION = 20
        const val LEARN_AHEAD_MS = 20 * 60_000L
    }
}
