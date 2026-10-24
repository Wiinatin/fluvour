package app.fluvour.ocr

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.ImageRepository
import app.fluvour.audio.TtsManager
import app.fluvour.data.CardRepository
import app.fluvour.data.Settings
import app.fluvour.data.SettingsRepository
import app.fluvour.data.SourceDao
import app.fluvour.data.SourceEntity
import app.fluvour.reading.DictionaryRepository
import app.fluvour.reading.ImportedLookup
import app.fluvour.reading.LevelInfo
import app.fluvour.reading.LevelRepository
import app.fluvour.reading.LookupRepository
import app.fluvour.reading.Part
import app.fluvour.reading.SentenceRepository
import app.fluvour.reading.Token
import app.fluvour.reading.WordSplitter
import app.fluvour.ui.Meanings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

/** The word picked inside the OCR block and the sentence around it. `start`/`end` are positions in the block text. */
data class OcrPick(val word: String, val lemma: String?, val reading: String?, val sentence: String, val sentenceStart: Int, val start: Int, val end: Int)

/**
 * The lookup sheet over another app (Phase 2B steps 4 and 5). Same engine as the reader: Wiktionary + AI explanation,
 * level chips, imported dictionaries, one-tap sentence mining. The source is the app the text was on (plus an optional series / chapter).
 */
@HiltViewModel
class OcrLookupViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val lookups: LookupRepository, private val tts: TtsManager,
    private val sentenceRepo: SentenceRepository, private val cardRepo: CardRepository, private val levelRepo: LevelRepository,
    private val dictRepo: DictionaryRepository, private val sources: SourceDao, private val images: ImageRepository,
    @ApplicationContext private val ctx: Context
) : ViewModel() {
    val settings: StateFlow<Settings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val prefs = OcrPrefs(ctx)

    var lang: String = "en"; private set
    var text: String = ""; private set
    private var pkg: String? = null
    private var pageKey = ""
    private var blockIndex = 0
    private var started = false

    private val _tokens = MutableStateFlow<List<Token>>(emptyList())
    val tokens: StateFlow<List<Token>> = _tokens.asStateFlow()
    private val _pick = MutableStateFlow<OcrPick?>(null)
    val pick: StateFlow<OcrPick?> = _pick.asStateFlow()
    private val _meanings = MutableStateFlow(Meanings())
    val meanings: StateFlow<Meanings> = _meanings.asStateFlow()
    private val _level = MutableStateFlow<LevelInfo?>(null)
    val level: StateFlow<LevelInfo?> = _level.asStateFlow()
    private val _imported = MutableStateFlow<ImportedLookup?>(null)
    val imported: StateFlow<ImportedLookup?> = _imported.asStateFlow()
    private val _voiceHint = MutableStateFlow(false)
    val voiceHint: StateFlow<Boolean> = _voiceHint.asStateFlow()
    private val _sentenceSaved = MutableStateFlow(false)
    val sentenceSaved: StateFlow<Boolean> = _sentenceSaved.asStateFlow()
    private val _cardAdded = MutableStateFlow(false)
    val cardAdded: StateFlow<Boolean> = _cardAdded.asStateFlow()
    private val _sourceLabel = MutableStateFlow("Screen")
    val sourceLabel: StateFlow<String> = _sourceLabel.asStateFlow()
    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()
    private var job: Job? = null

    fun start(text: String, offset: Int, lang: String, pkg: String?, pageKey: String, blockIndex: Int) {
        if (started) return
        started = true
        this.text = text; this.lang = lang; this.pkg = pkg; this.pageKey = pageKey; this.blockIndex = blockIndex
        _sourceLabel.value = ForegroundApp.label(ctx, pkg) ?: "Screen"
        _note.value = prefs.note(pkg)
        viewModelScope.launch {
            val toks = withContext(Dispatchers.Default) { WordSplitter.split(text, lang) }
            _tokens.value = toks
            val t = WordSplitter.wordAt(toks, offset)
                ?: toks.firstOrNull { it.isWord && it.start >= offset } ?: toks.lastOrNull { it.isWord }
            if (t != null) choose(t)
        }
    }

    /** Looks up the word the user tapped; dictionary and AI explanation load side by side (both cached after the first time). */
    fun choose(t: Token) {
        if (!t.isWord) return
        job?.cancel()
        val (ss, se) = WordSplitter.sentenceSpan(text, t.start)
        val p = OcrPick(text.substring(t.start, t.end), t.lemma, t.reading, text.substring(ss, se), ss, t.start, t.end)
        _pick.value = p
        _meanings.value = Meanings(); _level.value = null; _imported.value = null
        _sentenceSaved.value = false; _cardAdded.value = false
        job = viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val userId = settingsRepo.ensureUserId()
            launch { _level.value = levelRepo.lookup(lang, p.lemma, p.word) }
            launch { _imported.value = dictRepo.lookup(lang, p.lemma, p.word) }
            launch { val r = lookups.dictionary(userId, lang, p.lemma ?: p.word); _meanings.update { it.copy(dictionary = r) } }
            launch { val r = lookups.explain(userId, lang, s.uiLang, p.word, p.sentence); _meanings.update { it.copy(explanation = r) } }
        }
    }

    fun setNote(v: String) { _note.value = v; prefs.setNote(pkg, v) }

    fun speak(s: String) { _voiceHint.value = tts.speak(s, lang) == TtsManager.Result.NO_VOICE }

    /** The source row for this app (and series / chapter): one per combination, so sentences from the same comic group together. */
    private suspend fun ensureSource(userId: String): SourceEntity {
        val note = _note.value.trim()
        val label = _sourceLabel.value
        val id = UUID.nameUUIDFromBytes("screen:$userId:${pkg.orEmpty()}:$note".toByteArray()).toString()
        sources.get(id)?.let { return it }
        val now = System.currentTimeMillis()
        val meta = JSONObject().put("package", pkg ?: JSONObject.NULL).put("note", note).toString()
        return SourceEntity(id, userId, "comic", if (note.isEmpty()) label else "$label · $note", null, meta, now, now).also { sources.upsert(it) }
    }

    /** Saves the sentence with its source and where it was on the screen. Returns the sentence id. */
    private suspend fun mine(): String? {
        val p = _pick.value ?: return null
        if (p.sentence.isBlank()) return null
        val userId = settingsRepo.ensureUserId()
        val source = ensureSource(userId)
        val position = JSONObject().put("kind", "screen").put("page", pageKey).put("block", blockIndex)
            .put("start", p.sentenceStart).put("end", p.sentenceStart + p.sentence.length).toString()
        return sentenceRepo.mine(userId, lang, p.sentence, source.id, position).also { _sentenceSaved.value = true }
    }

    fun saveSentence() { viewModelScope.launch { mine() } }

    /** One tap: mines the sentence, then makes a card linked to it. The meaning is the AI's short answer, else the first imported or dictionary entry. */
    fun addCard() {
        val p = _pick.value ?: return
        if (_cardAdded.value) return
        viewModelScope.launch {
            val userId = settingsRepo.ensureUserId()
            val sentenceId = mine()
            val m = _meanings.value
            val meaning = m.explanation?.text
                ?: _imported.value?.entries?.firstOrNull()?.glossary?.lineSequence()?.take(3)?.joinToString("; ")
                ?: m.dictionary?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
            val card = cardRepo.save(userId, lang, null, (p.lemma ?: p.word).trim(), meaning.trim(), p.sentence, null, sentenceId)
            _cardAdded.value = true
            // A picture in the background, like cards made in the reader. Own scope: the sheet may close before the picture is ready.
            appScope.launch { runCatching { images.generate(card.id) } }
        }
    }

    override fun onCleared() { tts.stop() }

    private companion object {
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }
}
