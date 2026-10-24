package app.fluvour.quiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.audio.ClipPlayer
import app.fluvour.audio.TtsManager
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuizUi(val loading: Boolean = true, val questions: List<Question> = emptyList(), val message: String? = null)

/** One quiz session: builds the questions, speaks audio, and puts missed words back into review. */
@HiltViewModel
class QuizViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val repo: QuizRepository, private val tts: TtsManager, private val cardRepo: CardRepository, private val clip: ClipPlayer
) : ViewModel() {
    private val _ui = MutableStateFlow(QuizUi())
    val ui: StateFlow<QuizUi> = _ui.asStateFlow()
    private val _voiceHint = MutableStateFlow(false)
    val voiceHint: StateFlow<Boolean> = _voiceHint.asStateFlow()
    private var lang = "en"

    /** Builds a fresh quiz (also used by "Try again": the words and sentences are picked again). */
    fun load(setId: String) {
        _ui.value = QuizUi(loading = true)
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            lang = s.targetLang
            val prep = repo.prepare(settingsRepo.ensureUserId(), s.targetLang, s.uiLang, setId)
            _ui.value = QuizUi(false, prep.questions, prep.message)
        }
    }

    /** Plays the native recording when there is one, and the phone voice when there is not (or the recording will not load). */
    fun speak(text: String, audioId: Long?) {
        if (audioId == null) { speak(text); return }
        tts.stop()
        clip.play(audioId) { speak(text) }
    }

    fun speak(text: String) { if (text.isNotBlank()) _voiceHint.value = tts.speak(text, lang) == TtsManager.Result.NO_VOICE }

    fun addBack(cardId: String) { viewModelScope.launch { cardRepo.addBack(cardId) } }

    override fun onCleared() { tts.stop(); clip.stop() }
}
