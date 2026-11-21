package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.data.SettingsRepository
import app.fluvour.path.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Path list shows. [path] is null while loading or when the language has no curriculum. */
data class PathUi(
    val loading: Boolean = true, val path: CurriculumPath? = null, val rows: List<UnitRow> = emptyList(),
    val graduated: Int = 0, val nudge: UnitNudge? = null, val lessonDone: Set<String> = emptySet(), val mastered: Set<String> = emptySet()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PathViewModel @Inject constructor(settingsRepo: SettingsRepository, private val repo: PathRepository) : ViewModel() {
    private val lang = settingsRepo.settings.map { it.targetLang }.distinctUntilChanged()
    private val tick = MutableStateFlow(0)

    /** Whether the Path sub-tab exists for the study language. False until the answer is known, so the tab appears a moment after the others. */
    val available: StateFlow<Boolean> = lang.map { repo.hasPath(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val ui: StateFlow<PathUi> = lang.flatMapLatest { l ->
        combine(repo.observeGraduated(l), tick) { g, _ -> g }.map { g -> build(l, g) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PathUi())

    private val _open = MutableStateFlow<String?>(null)
    /** Id of the unit whose detail is open, or null for the list. In memory: kept while the app stays open, cleared when the language changes. */
    val open: StateFlow<String?> = _open.asStateFlow()
    fun openUnit(id: String) { _open.value = id }
    fun closeUnit() { _open.value = null }

    init {
        viewModelScope.launch { runCatching { repo.flushGrading() } }   // answers queued while offline
        viewModelScope.launch { lang.drop(1).collect { _open.value = null } }
        // "Back to the path" on a passed test result: show the unit list
        viewModelScope.launch { repo.listRequest.collect { if (it) { _open.value = null; repo.listRequest.value = false } } }
    }

    /** One-line message from a drill session; the screen shows it once and calls [clearNotice]. */
    val notice: StateFlow<String?> = repo.notice.asStateFlow()
    fun clearNotice() { repo.notice.value = null }

    /** Called when the tab is shown again: the due count on the current unit changes as cards are reviewed. */
    fun refresh() { tick.value++ }

    private suspend fun build(lang: String, graduated: Set<String>): PathUi {
        val path = PathState.active(repo.paths(lang), graduated) ?: return PathUi(loading = false)
        val rows = PathState.rows(path, graduated)
        val current = rows.firstOrNull { it.status == UnitStatus.CURRENT }
        return PathUi(
            loading = false, path = path, rows = rows, graduated = PathState.graduatedCount(path, graduated),
            nudge = current?.let { repo.nudge(lang, it.unit) }, lessonDone = repo.lessonDone(), mastered = repo.masteredUnits()
        )
    }
}
