package app.fluvour.ui

import androidx.lifecycle.ViewModel
import app.fluvour.reading.HeisigStudyRepository
import app.fluvour.reading.KanjiCard
import app.fluvour.reading.KanjiRepository
import app.fluvour.reading.KanjiStrokes
import app.fluvour.reading.StrokeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives the word popup's Kanji tab its data. */
@HiltViewModel
class KanjiViewModel @Inject constructor(
    private val repo: KanjiRepository,
    private val study: HeisigStudyRepository,
    private val strokes: StrokeRepository
) : ViewModel() {
    suspend fun cards(lang: String, text: String): List<KanjiCard> = repo.cards(lang, text)

    /** Kanji you have graduated to review in Heisig study mode. */
    suspend fun known(): Set<String> = study.known()

    suspend fun strokes(ch: String): KanjiStrokes? = strokes.strokes(ch)
}
