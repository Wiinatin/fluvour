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

enum class LessonPhase { LOADING, ERROR, LESSON, DRILLS }

/** Everything the lesson and drill screens show. Drill answers are held here so a rotation does not lose them. */
data class LessonUi(
    val phase: LessonPhase = LessonPhase.LOADING, val unit: CurriculumUnit? = null, val lesson: Lesson? = null, val message: String? = null,
    val chunk: Int = 0, val word: Int = 0,
    val index: Int = 0, val correct: Int = 0, val checked: Boolean = false, val lastOk: Boolean = false,
    val choice: Int? = null, val tray: List<String> = emptyList(), val text: String = "",
    /** A typed answer is waiting for the AI's verdict; [verdictWhy] is its one-line reason once it arrives. */
    val grading: Boolean = false, val verdictWhy: String = "",
    /** The drill on screen (chosen by the practice session), how many items are learned out of all, and the bar's fill. [proof] = the last question of an item. */
    val current: Drill? = null, val mastered: Int = 0, val masteryTotal: Int = 0, val progress: Float = 0f, val proof: Boolean = false,
) {
    val drill: Drill? get() = current
    /** The answer in the current drill is filled in enough to be checked. */
    val ready: Boolean get() = when (drill) { is BuildDrill -> tray.isNotEmpty(); is ChooseDrill -> choice != null; is TypedDrill -> text.isNotBlank(); null -> false }
}

@HiltViewModel
class LessonViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val repo: PathRepository, private val tts: TtsManager
) : ViewModel() {
    private val _ui = MutableStateFlow(LessonUi())
    val ui: StateFlow<LessonUi> = _ui.asStateFlow()
    private var lang = "ja"

    /** [drillsOnly] skips the chunks (the unit's "Practice drills"). [fresh] writes a new lesson instead of using the saved one. */
    fun load(unitId: String, drillsOnly: Boolean, fresh: Boolean = false) {
        _ui.value = LessonUi()
        viewModelScope.launch {
            lang = settingsRepo.settings.first().targetLang
            val unit = repo.unit(lang, unitId)
            if (unit == null) { _ui.value = LessonUi(LessonPhase.ERROR, message = "This unit was not found."); return@launch }
            val r = repo.lesson(lang, unit, fresh)
            val lesson = r.lesson
            _ui.value = if (lesson == null) LessonUi(LessonPhase.ERROR, unit, message = r.message)
            else LessonUi(if (drillsOnly) LessonPhase.DRILLS else LessonPhase.LESSON, unit, lesson)
            if (lesson != null && drillsOnly) startSession()
        }
    }

    /** The practice session of this unit: every word and every lesson sentence, until each is learned ([MasterySession]). Progress left half way is picked up again. */
    private var session: MasterySession? = null
    private fun startSession() {
        val s = _ui.value; val lesson = s.lesson ?: return; val unit = s.unit ?: return
        val (items, pool) = MasteryBuilder.build(unit, lesson, kotlin.random.Random.Default)
        val ms = MasterySession(items, pool)
        repo.loadMastery(unit.id)?.let { ms.restore(it) }
        session = ms
        _ui.update { it.copy(index = 0, correct = 0) }
        showNext()
    }
    /** Puts the session's next drill on screen; false when there is none (all learned, or the session was capped). */
    private fun showNext(): Boolean {
        val ms = session ?: return false
        val d = ms.next() ?: return false
        _ui.update { it.copy(phase = LessonPhase.DRILLS, current = d, index = it.index + 1, checked = false, lastOk = false, grading = false, verdictWhy = "", choice = null, tray = emptyList(), text = "",
            mastered = ms.mastered, masteryTotal = ms.total, progress = ms.progress, proof = ms.inProof) }
        return true
    }
    private fun answered(ok: Boolean) {
        val ms = session ?: return; val unit = _ui.value.unit ?: return
        ms.record(ok); repo.saveMastery(unit.id, ms.snapshot())
    }

    fun pickWord(i: Int) = _ui.update { it.copy(word = i) }
    fun speak(text: String) { tts.speak(text, lang) }

    /** Next chunk, or after the last one: the unit's words become cards, the lesson counts as done, and the drills start. */
    fun nextChunk() {
        val s = _ui.value; val lesson = s.lesson ?: return; val unit = s.unit ?: return
        if (s.chunk < lesson.chunks.lastIndex) { _ui.update { it.copy(chunk = it.chunk + 1, word = 0) }; return }
        viewModelScope.launch {
            runCatching { repo.ensureCards(lang, unit, lesson) }
            repo.markLessonDone(unit.id)
            _ui.update { it.copy(phase = LessonPhase.DRILLS, index = 0, correct = 0, checked = false, choice = null, tray = emptyList(), text = "") }
            startSession()
        }
    }

    fun toggleChip(token: String) = _ui.update { if (it.checked) it else it.copy(tray = if (token in it.tray) it.tray - token else it.tray + token) }
    fun choose(i: Int) = _ui.update { if (it.checked) it else it.copy(choice = i) }
    fun type(t: String) = _ui.update { if (it.checked) it else it.copy(text = t) }

    private val pending = mutableListOf<kotlinx.coroutines.Job>()

    /**
     * Build and choose are judged on the spot. A typed answer goes to the AI in the background: the screen says "Checking..." and the
     * learner can press Continue at once; the verdict is applied when it arrives (offline: local check, answer queued). A miss saves its reason with the card.
     */
    fun check() {
        val s = _ui.value; val d = s.drill ?: return
        if (s.checked || !s.ready) return
        if (d is TypedDrill) {
            val index = s.index; val answer = s.text
            _ui.update { it.copy(checked = true, lastOk = false, grading = true, verdictWhy = "") }
            val job = repo.scope.launch {
                val v = repo.gradeDrill(lang, d, answer)
                answered(v.ok)   // before the screen is released, so the next drill is chosen with this answer counted
                _ui.update { u ->
                    val here = u.index == index
                    u.copy(correct = u.correct + if (v.ok) 1 else 0, lastOk = if (here) v.ok else u.lastOk,
                        grading = if (here) false else u.grading, verdictWhy = if (here) v.why else u.verdictWhy)
                }
                val target = d.target
                if (!v.ok && target != null) runCatching { repo.noteMiss(lang, target, d.why) }
            }
            pending.add(job)
            return
        }
        val ok = when (d) {
            is BuildDrill -> LessonLogic.buildOk(d, s.tray)
            is ChooseDrill -> s.choice == d.answer
            is TypedDrill -> false
        }
        answered(ok)
        _ui.update { it.copy(checked = true, lastOk = ok, correct = it.correct + if (ok) 1 else 0) }
        val target = d.target
        if (!ok && target != null) viewModelScope.launch { runCatching { repo.noteMiss(lang, target, d.why) } }
    }

    /** Next drill. Returns true when the session is over (all learned, or stopped), with the result posted for the Path screen. */
    fun next(): Boolean {
        val s = _ui.value; val unit = s.unit ?: return false
        if (s.grading) return false   // the next drill depends on this verdict
        if (showNext()) return false
        val ms = session; val done = ms?.allDone == true
        repo.scope.launch {
            pending.toList().forEach { it.join() }   // typed verdicts still on their way
            val grad = if (done) {
                repo.markMastered(unit.id); repo.clearMastery(unit.id)
                // a unit without a unit check (Unit 1) graduates when it is learned
                runCatching { repo.graduateByLesson(lang, unit) }.getOrNull()
            } else null
            val opened = if (grad == null) "" else " Unit ${unit.order} graduated." + (grad.nextOrder?.let { " Unit $it is open." } ?: "")
            repo.notice.value = if (ms == null) "Practice ended."
            else if (done) "Learned: all ${ms.total} words and sentences, in ${ms.answered} drills.$opened"
            else "Practice stopped: ${ms.mastered} of ${ms.total} learned" + ms.hardest().let { if (it.isEmpty()) "" else ". Hardest: " + it.joinToString(", ") } + ". Your progress is saved; practise again to finish."
        }
        return true
    }
}
