package app.fluvour.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.reading.HeisigRepository
import app.fluvour.reading.HeisigStudyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HeisigImportUi(val busy: Boolean = false, val progress: String? = null, val message: String? = null)

/** Settings > Heisig deck: import your .apkg, see how many kanji are in, remove it. */
@HiltViewModel
class HeisigViewModel @Inject constructor(private val repo: HeisigRepository, private val study: HeisigStudyRepository) : ViewModel() {
    val count: StateFlow<Int?> = repo.count
    val newPerDay: StateFlow<Int> = study.newPerDay
    fun setNewPerDay(n: Int) = study.setNewPerDay(n)
    val maturityColors: StateFlow<Boolean> = study.maturityColors
    fun setMaturityColors(on: Boolean) = study.setMaturityColors(on)
    private val _ui = MutableStateFlow(HeisigImportUi())
    val ui: StateFlow<HeisigImportUi> = _ui.asStateFlow()

    init { viewModelScope.launch { repo.refresh() } }

    fun import(uri: Uri) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = HeisigImportUi(busy = true, progress = "Opening the file")
            repo.import(uri) { step -> _ui.update { it.copy(progress = step) } }.fold(
                onSuccess = { _ui.value = HeisigImportUi(message = "Imported $it kanji.") },
                onFailure = { _ui.value = HeisigImportUi(message = it.message ?: "Could not import this deck.") }
            )
        }
    }

    fun clear() { viewModelScope.launch { repo.clear(); _ui.value = HeisigImportUi(message = "Deck removed.") } }
}
