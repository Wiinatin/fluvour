package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.media.ExtractorManager
import app.fluvour.media.UpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Settings > YouTube extractor: which NewPipeExtractor is running, and a button to look for a newer one now. */
@HiltViewModel
class ExtractorViewModel @Inject constructor(private val extractor: ExtractorManager) : ViewModel() {
    data class Ui(
        val version: String? = null, val downloaded: Boolean = false, val configured: Boolean = true,
        val lastCheck: Long = 0L, val busy: Boolean = false, val message: String? = null
    )

    private val _ui = MutableStateFlow(Ui(configured = extractor.isConfigured()))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init { refresh() }

    private fun refresh() {
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { runCatching { extractor.version() }.getOrNull() }   // first call loads the extractor
            _ui.update { it.copy(version = v ?: "unknown", downloaded = extractor.isDownloaded(), lastCheck = extractor.lastCheckAt()) }
        }
    }

    fun check() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val msg = when (val r = extractor.checkForUpdate(force = true, manual = true)) {
                is UpdateResult.Updated -> "Updated to ${r.version}. New videos use it now."
                UpdateResult.UpToDate -> "You have the newest one."
                UpdateResult.Skipped -> if (extractor.isConfigured()) "Checked a moment ago." else "Auto-update is off in this build: no update address was set."
                is UpdateResult.Failed -> "Could not update: ${r.reason}"
            }
            _ui.update { it.copy(busy = false, message = msg) }
            refresh()
        }
    }
}
