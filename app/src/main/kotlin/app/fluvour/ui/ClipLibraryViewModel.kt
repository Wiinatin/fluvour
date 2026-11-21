package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.data.AnalyzedVideoDao
import app.fluvour.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The Settings line "Library: N clips analyzed": clips of the current learning language that can be recommended. */
@HiltViewModel
class ClipLibraryViewModel @Inject constructor(settingsRepo: SettingsRepository, analyzed: AnalyzedVideoDao) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val count: StateFlow<Int> = settingsRepo.settings.map { it.targetLang }.distinctUntilChanged()
        .flatMapLatest { analyzed.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}
