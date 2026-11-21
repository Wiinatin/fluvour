package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.NewWord
import app.fluvour.audio.TtsManager
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.reading.LookupRepository
import app.fluvour.reading.Part
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A word tapped in text on the Output screens (your own words or the AI's), with the sentence it is in. */
data class OutLookup(val word: String, val lemma: String?, val reading: String?, val sentence: String, val lang: String)

/**
 * Word sheet for the Output screens. Meanings come from the same cached lookups as the reader and Watch, so a word looked up before
 * costs nothing. Nothing is saved until the person taps Save.
 */
@HiltViewModel
class WordLookupViewModel @Inject constructor(
    private val lookups: LookupRepository, private val cardRepo: CardRepository,
    private val settingsRepo: SettingsRepository, private val tts: TtsManager
) : ViewModel() {
    private val _lookup = MutableStateFlow<OutLookup?>(null)
    val lookup: StateFlow<OutLookup?> = _lookup.asStateFlow()
    private val _dictionary = MutableStateFlow<Part?>(null)
    val dictionary: StateFlow<Part?> = _dictionary.asStateFlow()
    private val _explanation = MutableStateFlow<Part?>(null)
    val explanation: StateFlow<Part?> = _explanation.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    private var job: Job? = null

    fun select(word: String, lemma: String?, reading: String?, sentence: String, lang: String) {
        job?.cancel()
        _lookup.value = OutLookup(word, lemma, reading, sentence, lang)
        _dictionary.value = null; _explanation.value = null; _saved.value = false
        job = viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val userId = settingsRepo.ensureUserId()
            launch { _saved.value = cardRepo.findWordPlain(lang, lemma ?: word) != null }
            launch { _dictionary.value = lookups.dictionary(userId, lang, lemma ?: word) }
            launch { _explanation.value = lookups.explain(userId, lang, s.uiLang, word, sentence) }
        }
    }

    fun clear() { job?.cancel(); _lookup.value = null }

    /** A short meaning for the card: the AI's gloss for this sentence (line 2 of its answer), else the first line of the dictionary. */
    fun meaning(): String {
        val ai = _explanation.value?.text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val gloss = (ai.getOrNull(1) ?: ai.firstOrNull())?.replace(Regex("^(\\d+[.)]|[-*•])\\s*"), "")?.take(140)
        if (!gloss.isNullOrBlank()) return gloss
        return _dictionary.value?.text?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.take(140).orEmpty()
    }

    fun save() {
        val l = _lookup.value ?: return
        val m = meaning()
        if (m.isBlank() || _saved.value) return
        viewModelScope.launch {
            cardRepo.addSpeakingWords(settingsRepo.ensureUserId(), l.lang, listOf(NewWord(l.lemma ?: l.word, m, l.sentence)))
            _saved.value = true
        }
    }

    fun speak(text: String) { _lookup.value?.let { tts.speak(text, it.lang) } }
}
