package app.fluvour.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.data.DictionaryEntity
import app.fluvour.data.SettingsRepository
import app.fluvour.reading.DictionaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DictImportUi(val busy: Boolean = false, val progress: String? = null, val message: String? = null)

/** Settings > Dictionaries: import a Yomitan .zip for the target language, switch dictionaries on or off, remove them. */
@HiltViewModel
class DictionaryViewModel @Inject constructor(private val repo: DictionaryRepository, private val settingsRepo: SettingsRepository) : ViewModel() {
    val dictionaries: StateFlow<List<DictionaryEntity>> = repo.dictionaries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _ui = MutableStateFlow(DictImportUi())
    val ui: StateFlow<DictImportUi> = _ui.asStateFlow()

    fun import(uri: Uri) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = DictImportUi(busy = true, progress = "Opening the file")
            val lang = settingsRepo.settings.first().targetLang
            repo.import(lang, uri) { name -> _ui.update { it.copy(progress = "Reading $name") } }.fold(
                onSuccess = { _ui.value = DictImportUi(message = "Imported \"${it.title}\": ${it.terms} entries" + if (it.metaCount > 0) " and ${it.metaCount} frequency or pitch records." else ".") },
                onFailure = { _ui.value = DictImportUi(message = it.message ?: "Could not import this dictionary.") }
            )
        }
    }

    fun setEnabled(id: String, on: Boolean) { viewModelScope.launch { repo.setEnabled(id, on) } }
    fun delete(id: String) { viewModelScope.launch { repo.delete(id) } }
    fun clearMessage() { _ui.update { it.copy(message = null) } }
}
