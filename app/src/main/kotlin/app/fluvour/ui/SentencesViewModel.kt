package app.fluvour.ui

import androidx.lifecycle.ViewModel
import app.fluvour.data.SettingsRepository
import app.fluvour.quiz.ExampleRepository
import app.fluvour.quiz.QSentence
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Gives the word popup's Sentences tab its data: real Tatoeba sentences, translated into the app language. */
@HiltViewModel
class SentencesViewModel @Inject constructor(private val examples: ExampleRepository, private val settingsRepo: SettingsRepository) : ViewModel() {
    suspend fun sentences(lang: String, word: String): List<QSentence> {
        val s = settingsRepo.settings.first()
        return examples.real(settingsRepo.ensureUserId(), lang, s.uiLang, word)
    }
}
