package app.fluvour.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.*
import app.fluvour.audio.TtsManager
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.language.Languages
import app.fluvour.ui.screens.PlayState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Journal: write, have the AI correct it, listen to the corrected text, save the words worth learning. Entries live in one small file. */
@HiltViewModel
class JournalViewModel @Inject constructor(
    @ApplicationContext ctx: Context, private val ai: AiService, private val cardRepo: CardRepository,
    private val settingsRepo: SettingsRepository, private val tts: TtsManager
) : ViewModel() {
    private val store = JournalStore(File(ctx.filesDir, "journal.json"))
    private val all = MutableStateFlow(store.all())

    fun entries(lang: String): Flow<List<JournalEntry>> = all.map { l -> l.filter { it.lang == lang } }
    fun entry(id: String?): JournalEntry? = if (id == null) null else all.value.firstOrNull { it.id == id }

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun put(e: JournalEntry) { store.put(e); all.value = store.all() }

    /** Saves the text as a draft (or the new text of an entry being edited). Returns the entry id. An empty text saves nothing. */
    fun saveDraft(id: String?, lang: String, text: String): String? {
        val t = text.trim()
        val old = entry(id)
        if (t.isEmpty()) return old?.id
        val now = System.currentTimeMillis()
        val e = if (old == null) JournalEntry(UUID.randomUUID().toString(), lang, now, now, t)
        else if (old.text == t) old else old.copy(text = t, updatedAt = now, corrected = null, fixes = emptyList(), words = emptyList())
        put(e)
        return e.id
    }

    fun delete(id: String) { store.delete(id); all.value = store.all() }

    /** Asks the AI to correct the entry. The text is saved first, so a failed call loses nothing. */
    fun check(id: String) {
        val e = entry(id) ?: return
        if (_checking.value) return
        _checking.value = true; _error.value = null
        viewModelScope.launch {
            try {
                val s = settingsRepo.settings.first()
                val r = ai.text(JournalPrompt.build(Languages.byCode(e.lang).name, Languages.byCode(s.uiLang).name, s.levels[e.lang].orEmpty(), e.text), JournalPrompt.TEMPERATURE, JournalPrompt.MAX_TOKENS)
                val p = r.text?.let(JournalPrompt::parse)
                if (p == null) _error.value = r.message ?: "The AI answer could not be read. Try again."
                else put(e.copy(corrected = p.corrected, fixes = p.fixes, words = p.words, updatedAt = System.currentTimeMillis()))
                if (p != null) tts.kickAhead()
            } catch (c: CancellationException) { throw c } catch (x: Exception) { _error.value = "Could not reach the AI. Check your connection." }
            _checking.value = false
        }
    }

    fun saveWord(id: String, word: String) {
        viewModelScope.launch {
            val e = entry(id) ?: return@launch
            val w = e.words.firstOrNull { it.word == word } ?: return@launch
            val made = cardRepo.addSpeakingWords(settingsRepo.ensureUserId(), e.lang, listOf(w)).firstOrNull() ?: return@launch
            val cid = made.cardId ?: cardRepo.findWordPlain(e.lang, w.word)?.id
            put(e.copy(words = e.words.map { if (it.word == word) it.copy(cardId = cid) else it }))
        }
    }

    // ---- sound of the corrected text ----
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private var playing = false

    fun play(lang: String, text: String) {
        playing = true; _loading.value = true
        tts.speak(text, lang, keep = true)
        viewModelScope.launch { kotlinx.coroutines.delay(15_000); _loading.value = false }
    }
    fun playState(): PlayState {
        if (!playing || !tts.hasSound) return PlayState()
        _loading.value = false
        return PlayState(true, tts.isPlaying(), tts.positionMs(), tts.durationMs())
    }
    fun pauseSound() = tts.pause()
    fun resumeSound() = tts.resume()
    fun seekSound(ms: Int) = tts.seekTo(ms)
    fun setSpeed(f: Float) { _speed.value = f; tts.setSpeed(f) }
    fun stopSound() { playing = false; _loading.value = false; tts.stop() }

    override fun onCleared() { tts.stop() }
}
