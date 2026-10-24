package app.fluvour.quiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.ImageRepository
import app.fluvour.data.CardEntity
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.language.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What "Try a new angle" has made so far. `text` is the new mnemonic or sentence; `message` says what went wrong or what was done. */
data class AngleUi(val busy: Boolean = false, val text: String? = null, val message: String? = null, val saved: Boolean = false)

/** "Try a new angle" for a word missed 6 or more times (Phase 3 step 4): a new picture, a mnemonic, or a new example sentence. */
@HiltViewModel
class LeechViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val textAi: TextAi, private val examples: ExampleRepository,
    private val cardRepo: CardRepository, private val images: ImageRepository
) : ViewModel() {
    private val _ui = MutableStateFlow(AngleUi())
    val ui: StateFlow<AngleUi> = _ui.asStateFlow()

    fun reset() { _ui.value = AngleUi() }

    fun mnemonic(card: CardEntity) {
        _ui.value = AngleUi(busy = true)
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val prompt = "Write one short, vivid memory hook (a mnemonic) to remember the ${Languages.byCode(card.languageCode).name} word \"${card.word}\", " +
                "meaning \"${QuizEngine.shortMeaning(card.meaning)}\". Write it in ${Languages.byCode(s.uiLang).name}. At most 2 sentences, plain text, no markdown."
            val p = textAi.ask(prompt, 200)
            _ui.value = AngleUi(text = p.text?.trim(), message = p.message)
        }
    }

    fun example(card: CardEntity) {
        _ui.value = AngleUi(busy = true)
        viewModelScope.launch {
            val r = examples.freshExample(card.languageCode, card.word, card.meaning, card.example)
            _ui.value = AngleUi(text = r.getOrNull(), message = r.exceptionOrNull()?.message)
        }
    }

    /** A new picture is made and saved straight away (the card shows it on its back). */
    fun image(card: CardEntity) {
        _ui.value = AngleUi(busy = true)
        viewModelScope.launch {
            val msg = images.generate(card.id).message
            _ui.value = AngleUi(message = msg ?: "A new picture is saved on the card.", saved = msg == null)
        }
    }

    /** Keeps the new mnemonic (added to the card's notes) or the new sentence (becomes the card's example). */
    fun keepMnemonic(card: CardEntity) {
        val t = _ui.value.text ?: return
        viewModelScope.launch {
            cardRepo.setAngle(card.id, null, listOfNotNull(card.notes?.takeIf { it.isNotBlank() }, "Mnemonic: $t").joinToString("\n"))
            _ui.value = _ui.value.copy(saved = true)
        }
    }

    fun keepExample(card: CardEntity) {
        val t = _ui.value.text ?: return
        viewModelScope.launch { cardRepo.setAngle(card.id, t, null); _ui.value = _ui.value.copy(saved = true) }
    }
}
