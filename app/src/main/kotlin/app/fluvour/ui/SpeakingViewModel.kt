package app.fluvour.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.*
import app.fluvour.audio.Recorded
import app.fluvour.audio.SpeechRecorder
import app.fluvour.audio.TtsManager
import app.fluvour.data.*
import app.fluvour.language.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Where the topic screen is: ready to record, recording, waiting for the AI, or showing a result. */
enum class SpeakPhase { READY, RECORDING, PROCESSING, RESULT }

/**
 * Speaking practice (Phase 5): topics, one recording per version, and the two AI steps. Kept apart from the other ViewModels.
 * When Stop is tapped the file and the version row are saved first; only then the AI is called, so a failed call never loses a
 * recording. [retry] runs the same pipeline again from what is saved: it redoes only the steps that are still missing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SpeakingViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: SpeakingDao, private val recorder: SpeechRecorder,
    private val transcriber: SpeechTranscriber, private val improver: SpeechImprover,
    private val ai: AiService, private val cardRepo: CardRepository,
    private val settingsRepo: SettingsRepository, private val tts: TtsManager
) : ViewModel() {

    // ---- topics tab ----
    fun topics(lang: String): Flow<List<SpeakingTopicRow>> = dao.observeTopics(lang)

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()
    private var suggestedFor: String? = null

    /** Asks the AI for topic titles for this language and the declared level. Once per language until [force]. Quiet on failure (the text field still works). */
    fun loadSuggestions(lang: String, force: Boolean = false) {
        if (!force && suggestedFor == lang) return
        suggestedFor = lang
        viewModelScope.launch {
            val level = settingsRepo.settings.first().levels[lang].orEmpty()
            val r = ai.text(TopicPrompt.suggest(Languages.byCode(lang).name, level), TopicPrompt.TEMPERATURE, TopicPrompt.MAX_TOKENS)
            val list = r.text?.let(SpeakingParse::titles).orEmpty()
            _suggestions.value = list
            if (list.isEmpty()) suggestedFor = null
        }
    }

    /** Makes the topic and returns its id to [onCreated]. */
    fun createTopic(lang: String, title: String, onCreated: (String) -> Unit) {
        val clean = title.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val t = SpeakingTopicEntity(UUID.randomUUID().toString(), settingsRepo.ensureUserId(), lang, clean, now, now)
            dao.upsertTopic(t)
            onCreated(t.id)
        }
    }

    /** Deletes a topic with all its recordings (the files too). Cards made from its words stay. */
    fun deleteTopic(id: String) {
        viewModelScope.launch {
            dao.observeVersions(id).first().forEach { v -> v.audioPath?.let { runCatching { File(it).delete() } } }
            dao.deleteTopic(id, System.currentTimeMillis())
        }
    }

    // ---- topic screen ----
    private val _topic = MutableStateFlow<SpeakingTopicEntity?>(null)
    val topic: StateFlow<SpeakingTopicEntity?> = _topic.asStateFlow()
    private val topicId = MutableStateFlow<String?>(null)
    val versions: StateFlow<List<SpeakingVersionEntity>> =
        topicId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeVersions(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _phase = MutableStateFlow(SpeakPhase.READY)
    val phase: StateFlow<SpeakPhase> = _phase.asStateFlow()
    private val shownId = MutableStateFlow<String?>(null)
    /** The version whose result is on screen (phase RESULT). */
    val shown: StateFlow<SpeakingVersionEntity?> =
        combine(versions, shownId) { vs, id -> vs.firstOrNull { it.id == id } }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _fromList = MutableStateFlow(false)
    /** True when the result on screen was opened from the version list (closing it goes back to the topic page, not out of the topic). */
    val fromList: StateFlow<Boolean> = _fromList.asStateFlow()

    /** Measures of every processed version of this topic, oldest first. Versions with no transcript or rewrite yet are left out. */
    val measures: StateFlow<List<Pair<SpeakingVersionEntity, Measures>>> =
        combine(versions, topic) { vs, t ->
            val lang = t?.languageCode ?: return@combine emptyList<Pair<SpeakingVersionEntity, Measures>>()
            vs.filter { it.improved != null && it.transcript.isNotBlank() }
                .map { it to SpeakingMeasures.of(it.durationSec, it.transcript, it.substituted, it.newWords, lang) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _cues = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val cues: StateFlow<List<Pair<String, String>>> = _cues.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** A short plain line to show under the record button (too short, microphone problem). */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _failDetail = MutableStateFlow<String?>(null)
    /** Why the last processing failed, when the AI said (for example no key). The main failure line is fixed text in the screen. */
    val failDetail: StateFlow<String?> = _failDetail.asStateFlow()
    private val _micDenied = MutableStateFlow(false)
    val micDenied: StateFlow<Boolean> = _micDenied.asStateFlow()

    val elapsedSec: StateFlow<Int> = recorder.elapsedSec

    private var pendingId: String? = null

    init {
        // The recorder stops itself at the time limit; only the screen that is recording takes the file.
        viewModelScope.launch {
            recorder.autoStopped.collect { r ->
                if (r != null && _phase.value == SpeakPhase.RECORDING) { recorder.takeAutoStopped(); finishRecording(r) }
            }
        }
    }

    fun open(id: String) {
        if (topicId.value == id) return
        topicId.value = id
        viewModelScope.launch {
            val t = dao.topic(id) ?: return@launch
            _topic.value = t
            loadCues(t)
        }
    }

    private fun loadCues(t: SpeakingTopicEntity) = viewModelScope.launch {
        val s = settingsRepo.settings.first()
        val r = ai.text(TopicPrompt.cues(t.title, Languages.byCode(t.languageCode).name, Languages.byCode(s.uiLang).name, s.levels[t.languageCode].orEmpty()),
            TopicPrompt.TEMPERATURE, TopicPrompt.MAX_TOKENS)
        _cues.value = r.text?.let(SpeakingParse::cues).orEmpty()
    }

    fun onMicDenied() { _micDenied.value = true }

    fun startRecording() {
        if (_phase.value != SpeakPhase.READY) return
        val id = UUID.randomUUID().toString()
        if (recorder.start(File(ctx.filesDir, "speaking/$id.aac"))) {
            pendingId = id; _notice.value = null; _micDenied.value = false; _phase.value = SpeakPhase.RECORDING
        } else _notice.value = "Could not start the microphone. Try again."
    }

    fun stopRecording() { if (_phase.value == SpeakPhase.RECORDING) finishRecording(recorder.stop()) }

    /** Throws the recording away (the screen was left, or the user cancelled). */
    fun discardRecording() { if (_phase.value == SpeakPhase.RECORDING) { recorder.discard(); pendingId = null; _phase.value = SpeakPhase.READY } }

    private fun finishRecording(rec: Recorded?) {
        val id = pendingId; pendingId = null
        if (rec == null || id == null) { _notice.value = "That was too short. Try again."; _phase.value = SpeakPhase.READY; return }
        _phase.value = SpeakPhase.PROCESSING
        viewModelScope.launch {
            val t = _topic.value ?: dao.topic(topicId.value ?: "")
            if (t == null) { _phase.value = SpeakPhase.READY; return@launch }
            val now = System.currentTimeMillis()
            // Save first: the file is on disk and the version row exists before any AI call.
            dao.upsertVersion(SpeakingVersionEntity(id = id, userId = t.userId, topicId = t.id, versionNo = dao.nextVersionNo(t.id), transcript = "",
                audioPath = rec.file.absolutePath, createdAt = now, updatedAt = now, durationSec = rec.durationSec))
            process(id)
        }
    }

    /** Tries a saved version again: only the missing steps run (transcript first, then the rewrite). */
    fun retry(versionId: String) {
        if (_phase.value == SpeakPhase.PROCESSING || _phase.value == SpeakPhase.RECORDING) return
        _phase.value = SpeakPhase.PROCESSING
        viewModelScope.launch { process(versionId) }
    }

    /** Back to the cue card for another attempt. */
    fun again() { _notice.value = null; _failDetail.value = null; shownId.value = null; _fromList.value = false; _phase.value = SpeakPhase.READY }

    /** Opens the saved result of a version from the list. Only versions that are finished (they have the rewrite) can be opened. */
    fun show(versionId: String) {
        if (_phase.value != SpeakPhase.READY) return
        val v = versions.value.firstOrNull { it.id == versionId } ?: return
        if (v.improved == null) return
        _fromList.value = true
        shownId.value = versionId
        _phase.value = SpeakPhase.RESULT
    }

    /**
     * Deletes only the recording file of a finished version. The transcript, rewrite, new words and cards stay.
     * A version that is not finished keeps its file, because a retry needs it.
     */
    fun deleteAudio(versionId: String) {
        viewModelScope.launch {
            val v = dao.version(versionId) ?: return@launch
            if (v.improved == null) return@launch
            v.audioPath?.let { runCatching { File(it).delete() } }
            dao.clearAudioPath(versionId, System.currentTimeMillis())
        }
    }

    private suspend fun process(id: String) {
        _failDetail.value = null
        val ok = try { pipeline(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
        if (ok) { _fromList.value = false; shownId.value = id; _phase.value = SpeakPhase.RESULT } else _phase.value = SpeakPhase.READY
    }

    private suspend fun pipeline(id: String): Boolean {
        var v = dao.version(id) ?: return false
        val t = _topic.value ?: dao.topic(v.topicId) ?: return false
        val s = settingsRepo.settings.first()
        val lang = Languages.byCode(t.languageCode).name
        val own = Languages.byCode(s.uiLang).name
        val level = s.levels[t.languageCode].orEmpty()

        if (v.transcript.isBlank()) {
            val audio = v.audioPath?.let { File(it) }
            if (audio == null || !audio.exists()) { _failDetail.value = "The recording file is missing."; return false }
            val hints = _cues.value.map { it.first }.take(8)
            val r = transcriber.transcribe(audio, lang, own, t.title, hints)
            val reply = r.text ?: run { _failDetail.value = r.message; return false }
            var tr = SpeakingParse.transcript(reply) ?: return false
            // Foreign letters or romanized words: ask once more with the rules repeated, and keep whichever answer looks better.
            val bad = TranscriptCheck.problems(tr.text, tr.substituted, lang)
            if (bad.isNotEmpty() && tr.text.isNotEmpty()) {
                val r2 = transcriber.transcribe(audio, lang, own, t.title, hints, strict = true)
                val tr2 = r2.text?.let(SpeakingParse::transcript)
                if (tr2 != null && tr2.text.isNotEmpty() && TranscriptCheck.problems(tr2.text, tr2.substituted, lang).size <= bad.size) tr = tr2
            }
            if (tr.text.isEmpty()) { _failDetail.value = "No speech was heard."; return false }
            v = v.copy(transcript = tr.text, substituted = SpeakingParse.substitutedJson(tr.substituted), updatedAt = System.currentTimeMillis())
            dao.updateVersion(v)
        }
        if (v.improved == null) {
            val tr = Transcript(v.transcript, SpeakingParse.substitutedFrom(v.substituted))
            val r = improver.improve(lang, own, level, tr)
            val reply = r.text ?: run { _failDetail.value = r.message; return false }
            val imp = SpeakingParse.improved(reply) ?: return false
            val words = cardRepo.addSpeakingWords(settingsRepo.ensureUserId(), t.languageCode, imp.newWords)
            v = v.copy(improved = imp.improved, newWords = SpeakingParse.newWordsJson(words), updatedAt = System.currentTimeMillis())
            dao.updateVersion(v)
            tts.kickAhead()   // its voice is made in the background right away
        }
        return true
    }

    /** Undo for one auto-made card: the card is deleted and the version remembers that it has no card for this word any more. */
    fun removeCard(versionId: String, word: String) {
        viewModelScope.launch {
            val v = dao.version(versionId) ?: return@launch
            val words = SpeakingParse.newWordsFrom(v.newWords)
            val cardId = words.firstOrNull { it.word == word }?.cardId ?: return@launch
            cardRepo.delete(cardId)
            dao.updateVersion(v.copy(newWords = SpeakingParse.newWordsJson(words.map { if (it.word == word) it.copy(cardId = null) else it }),
                updatedAt = System.currentTimeMillis()))
        }
    }

    fun speak(text: String) { _topic.value?.let { tts.speak(text, it.languageCode, keep = true) } }

    override fun onCleared() { if (_phase.value == SpeakPhase.RECORDING) recorder.discard() }
}
