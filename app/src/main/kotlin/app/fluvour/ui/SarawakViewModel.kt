package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.reading.SarawakRepository
import app.fluvour.reading.SarawakStats
import app.fluvour.reading.SarawakItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SarawakUi(val stats: SarawakStats? = null, val items: List<SarawakItem> = emptyList(), val busy: Boolean = false)

/** The Sarawak tab (Malay only) and its setting in Settings. All the work is in [SarawakRepository]. */
@HiltViewModel
class SarawakViewModel @Inject constructor(private val repo: SarawakRepository) : ViewModel() {
    val newPerDay: StateFlow<Int> = repo.newPerDay
    fun setNewPerDay(n: Int) = repo.setNewPerDay(n)

    private val _ui = MutableStateFlow(SarawakUi())
    val ui: StateFlow<SarawakUi> = _ui.asStateFlow()

    /** Opening the tab unlocks today's share (nothing if it is already used), then shows the result. [onChanged] runs when cards were added. */
    fun open(onChanged: () -> Unit = {}) = run(onChanged) { repo.unlockToday() }
    fun learnMore(onChanged: () -> Unit = {}) = run(onChanged) { repo.unlockMore(10) }

    private fun run(onChanged: () -> Unit, unlock: suspend () -> app.fluvour.reading.SarawakUnlockResult) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val r = unlock()
            repo.fillExampleMeanings().takeIf { it > 0 }?.let { onChanged() }
            _ui.value = SarawakUi(repo.stats(), repo.items())
            if (r.added.isNotEmpty()) onChanged()
        }
    }
}
