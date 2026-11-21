package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.audio.TtsManager
import app.fluvour.data.SettingsRepository
import app.fluvour.path.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TestPhase { LOADING, ERROR, QUESTION, GRADING, RESULT }

/** Everything the unit test screens show. The answers given so far are held here, so a rotation does not lose them. */
data class TestUi(
    val phase: TestPhase = TestPhase.LOADING, val unit: CurriculumUnit? = null, val test: UnitTest? = null, val message: String? = null,
    val index: Int = 0, val answers: List<TestAnswer> = emptyList(), val flagged: Set<Int> = emptySet(),
    val tray: List<String> = emptyList(), val choice: Int? = null, val text: String = "",
    val result: TestResult? = null, val finish: TestFinish? = null, val progress: BuildProgress = BuildProgress()
) {
    val question: TestQuestion? get() = test?.questions?.getOrNull(index)
    /** The answer to the current question is filled in enough to submit. */
    val ready: Boolean get() = when (question?.drill) { is BuildDrill -> tray.isNotEmpty(); is ChooseDrill -> choice != null; is TypedDrill -> text.isNotBlank(); null -> false }
}

/** Learning path step 8: runs one graduation test. No feedback during the test; everything is graded at the end. */
@HiltViewModel
class UnitTestViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val repo: PathRepository, private val tts: TtsManager
) : ViewModel() {
    private val _ui = MutableStateFlow(TestUi())
    val ui: StateFlow<TestUi> = _ui.asStateFlow()
    private var lang = "ja"

    init {
        // the loading screen's bar follows the repository while the test is written
        viewModelScope.launch { repo.buildProgress.collect { p -> _ui.update { if (it.phase == TestPhase.LOADING) it.copy(progress = p) else it } } }
    }

    /** Writes a fresh test (so a retake never repeats the same questions). With [resume] (\"Try again\" after a failed build) only the questions that did not pass are asked for again. */
    fun start(unitId: String, resume: Boolean = false) {
        _ui.value = TestUi()
        repo.buildProgress.value = BuildProgress()
        viewModelScope.launch {
            lang = settingsRepo.settings.first().targetLang
            val unit = repo.unit(lang, unitId)
            if (unit == null) { _ui.value = TestUi(TestPhase.ERROR, message = "This unit was not found."); return@launch }
            val r = repo.buildTest(lang, unit, resume)
            val test = r.test
            _ui.value = if (test == null) TestUi(TestPhase.ERROR, unit, message = r.message) else TestUi(TestPhase.QUESTION, unit, test)
        }
    }

    fun speak(text: String) { tts.speak(text, lang) }
    fun toggleChip(token: String) = _ui.update { it.copy(tray = if (token in it.tray) it.tray - token else it.tray + token) }
    fun choose(i: Int) = _ui.update { it.copy(choice = i) }
    fun type(t: String) = _ui.update { it.copy(text = t) }

    /** Flag a question that looks wrong: it never counts as a miss. At most [TestLogic.MAX_FLAGS] per test. */
    fun toggleFlag() = _ui.update {
        if (it.index in it.flagged) it.copy(flagged = it.flagged - it.index)
        else if (it.flagged.size < TestLogic.MAX_FLAGS) it.copy(flagged = it.flagged + it.index) else it
    }

    /** Keeps the answer and moves on; after the last one, grades everything and posts the result. */
    fun submit() {
        val s = _ui.value; val test = s.test ?: return; val unit = s.unit ?: return
        if (s.phase != TestPhase.QUESTION || !s.ready) return
        val answers = s.answers + TestAnswer(s.tray, s.choice, s.text)
        if (s.index < test.questions.lastIndex) {
            _ui.update { it.copy(index = it.index + 1, answers = answers, tray = emptyList(), choice = null, text = "") }
            return
        }
        _ui.update { it.copy(phase = TestPhase.GRADING, answers = answers) }
        viewModelScope.launch {
            val right = repo.gradeTest(test, answers)
            val result = TestLogic.score(test, right, s.flagged)
            val finish = repo.finishTest(lang, unit, test, result)
            _ui.update { it.copy(phase = TestPhase.RESULT, result = result, finish = finish) }
        }
    }

    /** "Back to the path" after a pass: the Path tab shows the unit list. */
    fun showPathList() { repo.listRequest.value = true }
}
