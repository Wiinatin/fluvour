package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.ImageRepository
import app.fluvour.data.AnkiExporter
import app.fluvour.data.AuthRepository
import app.fluvour.data.Session
import app.fluvour.sync.SyncRepository
import app.fluvour.data.CardEntity
import app.fluvour.data.CardRepository
import app.fluvour.data.Settings
import app.fluvour.data.SettingsRepository
import app.fluvour.srs.StudyStats
import app.fluvour.ui.theme.ThemeId
import app.fluvour.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val cardRepo: CardRepository,
    private val exporter: AnkiExporter, private val auth: AuthRepository,
    private val sync: SyncRepository, private val images: ImageRepository,
    private val sarawakSuggester: app.fluvour.reading.SarawakSuggester
) : ViewModel() {
    val session: StateFlow<Session?> = auth.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _accountStatus = MutableStateFlow<String?>(null)
    val accountStatus: StateFlow<String?> = _accountStatus.asStateFlow()
    private val _imageBusy = MutableStateFlow(false)
    val imageBusy: StateFlow<Boolean> = _imageBusy.asStateFlow()
    private val _imageStatus = MutableStateFlow<String?>(null)
    val imageStatus: StateFlow<String?> = _imageStatus.asStateFlow()

    fun signIn(email: String, password: String) = viewModelScope.launch {
        _accountStatus.value = "Signing in..."
        val error = auth.signIn(email, password)
        _accountStatus.value = error ?: "Signed in. Syncing..."
        if (error == null) _accountStatus.value = sync.syncNow()
    }
    fun signOut() = viewModelScope.launch { auth.signOut(); _accountStatus.value = "Signed out. Your cards stay on this phone." }
    fun syncNow() = viewModelScope.launch { _accountStatus.value = "Syncing..."; _accountStatus.value = sync.syncNow() }

    fun generateImage(cardId: String) = viewModelScope.launch {
        _imageBusy.value = true; _imageStatus.value = null
        _imageStatus.value = images.generate(cardId).message
        _imageBusy.value = false
    }

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    fun exportToAnki(uri: android.net.Uri) = viewModelScope.launch {
        _exportStatus.value = try { "Exported ${exporter.export(uri)} cards." } catch (e: Exception) { "Export failed: ${e.message}" }
    }

    val settings: StateFlow<Settings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val lang = settingsRepo.settings.map { it.targetLang }.distinctUntilChanged()
    val cards: StateFlow<List<CardEntity>> =
        lang.flatMapLatest { cardRepo.observe(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Re-runs the due query every minute so learning cards are counted when their time comes (the query's "now" is fixed when it starts). */
    private val minuteTick = flow { while (true) { emit(Unit); delay(60_000) } }
    val dueCount: StateFlow<Int> =
        lang.flatMapLatest { l -> minuteTick.flatMapLatest { cardRepo.observeDue(l) } }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Review counts per local day over the last 400 days, and the current streak. */
    val stats: StateFlow<Stats> =
        cardRepo.observeReviewTimes(System.currentTimeMillis() - 400L * 86_400_000L)
            .map { times ->
                val counts = StudyStats.countsByDay(times, java.time.ZoneId.systemDefault())
                Stats(counts, StudyStats.streak(counts.keys, java.time.LocalDate.now()))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Stats(emptyMap(), 0))

    init {
        viewModelScope.launch { settingsRepo.ensureUserId() }
        // Quiet sync when the app opens and an account is signed in.
        viewModelScope.launch { if (auth.session.first() != null) _accountStatus.value = sync.syncNow() }
    }

    fun setTarget(code: String) = viewModelScope.launch { settingsRepo.setTargetLang(code) }
    fun setUi(code: String) = viewModelScope.launch { settingsRepo.setUiLang(code) }
    fun setTheme(t: ThemeId) = viewModelScope.launch { settingsRepo.setTheme(t) }
    fun setMode(m: ThemeMode) = viewModelScope.launch { settingsRepo.setMode(m) }
    fun setDailyGoal(n: Int) = viewModelScope.launch { settingsRepo.setDailyGoal(n) }
    fun setClipCount(n: Int) = viewModelScope.launch { settingsRepo.setClipCount(n) }
    fun setClipLibrary(on: Boolean) = viewModelScope.launch { settingsRepo.setClipLibrary(on) }
    fun setLevel(lang: String, code: String) = viewModelScope.launch { settingsRepo.setLevel(lang, code) }
    fun setAutoPlay(on: Boolean) = viewModelScope.launch { settingsRepo.setAutoPlay(on) }
    fun setArt(on: Boolean) = viewModelScope.launch { settingsRepo.setArt(on) }
    fun setReduceMotion(on: Boolean) = viewModelScope.launch { settingsRepo.setReduceMotion(on) }
    fun setLyricsFinder(on: Boolean) = viewModelScope.launch { settingsRepo.setLyricsFinder(on) }

    fun saveCard(existing: CardEntity?, word: String, meaning: String, example: String, notes: String, sentenceId: String? = null,
                 variantWord: String = "", variantExample: String = "", root: String = "", lemma: String = "", variantExampleMeaning: String = "") =
        viewModelScope.launch {
            val userId = settingsRepo.ensureUserId()
            val s = settingsRepo.settings.first()
            val saved = cardRepo.save(userId, existing?.languageCode ?: s.targetLang, existing, word.trim(), meaning.trim(),
                example.trim().ifBlank { null }, notes.trim().ifBlank { null }, sentenceId,
                variantWord.trim().ifBlank { null }, variantExample.trim().ifBlank { null }, root.trim().ifBlank { null }, lemma.trim().ifBlank { null }, variantExampleMeaning.trim().ifBlank { null })
            // New words get a picture in the background when signed in; otherwise the card stays text-only.
            if (existing == null && session.value != null) images.generate(saved.id)
            // Malay: a new card saved with the Sarawak boxes empty gets a suggestion from the AI, marked in the notes so it can be checked.
            if (existing == null && saved.languageCode == "ms" && variantWord.isBlank() && variantExample.isBlank() && variantExampleMeaning.isBlank()) suggestSarawak(userId, saved)
        }
    /** Fills the Sarawak side of a just-saved Malay card from the AI. Skipped if the card was edited or deleted meanwhile, or the AI has nothing to add. */
    private suspend fun suggestSarawak(userId: String, saved: CardEntity) {
        val sug = runCatching { sarawakSuggester.suggest(saved.word, saved.meaning) }.getOrNull() ?: return
        val now = cardRepo.get(saved.id) ?: return
        if (now.deleted || !now.variantWord.isNullOrBlank() || !now.variantExample.isNullOrBlank()) return
        val notes = listOfNotNull(now.notes?.takeIf { it.isNotBlank() }, app.fluvour.reading.SarawakSuggestPrompt.NOTE).joinToString("\n\n")
        cardRepo.save(userId, now.languageCode, now, now.word, now.meaning, now.example, notes, now.sentenceId,
            sug.variantWord, sug.variantExample, now.root, now.lemma, sug.exampleMeaning)
    }

    /** A card started from the reader: the editor picks this up once when it opens a new card. */
    private var draft: CardDraft? = null
    /** A long AI explanation becomes a short meaning for the card; the whole text goes to the notes (the back of the card). */
    fun startDraft(word: String, example: String, meaning: String = "", sentenceId: String? = null) {
        val split = app.fluvour.reading.CardText.fromExplanation(meaning)
        // Malay/Arabic explanations carry labelled fields (Standard / Sarawak / Vowelled / Root / Lemma); other languages give null here.
        val f = app.fluvour.reading.LookupFields.parse(meaning)
        draft = CardDraft(f?.word ?: word, example, split.meaning, sentenceId, split.notes,
            f?.variantWord.orEmpty(), f?.variantExample.orEmpty(), f?.root.orEmpty(), f?.lemma.orEmpty())
    }
    fun takeDraft(): CardDraft? = draft.also { draft = null }

    fun deleteCard(id: String) = viewModelScope.launch { cardRepo.delete(id); CardEvents.notifyDeleted(id) }
}

data class CardDraft(val word: String, val example: String, val meaning: String = "", val sentenceId: String? = null, val notes: String? = null,
                     val variantWord: String = "", val variantExample: String = "", val root: String = "", val lemma: String = "")

data class Stats(val countsByDay: Map<java.time.LocalDate, Int>, val streak: Int) {
    fun today(): Int = countsByDay[java.time.LocalDate.now()] ?: 0
}
