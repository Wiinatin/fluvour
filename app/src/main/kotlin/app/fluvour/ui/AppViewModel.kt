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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val cardRepo: CardRepository,
    private val exporter: AnkiExporter, private val auth: AuthRepository,
    private val sync: SyncRepository, private val images: ImageRepository
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
    val dueCount: StateFlow<Int> =
        lang.flatMapLatest { cardRepo.observeDue(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

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
    fun setAutoPlay(on: Boolean) = viewModelScope.launch { settingsRepo.setAutoPlay(on) }

    fun saveCard(existing: CardEntity?, word: String, meaning: String, example: String, notes: String, sentenceId: String? = null) =
        viewModelScope.launch {
            val userId = settingsRepo.ensureUserId()
            val s = settingsRepo.settings.first()
            val saved = cardRepo.save(userId, s.targetLang, existing, word.trim(), meaning.trim(),
                example.trim().ifBlank { null }, notes.trim().ifBlank { null }, sentenceId)
            // New words get a picture in the background when signed in; otherwise the card stays text-only.
            if (existing == null && session.value != null) images.generate(saved.id)
        }
    /** A card started from the reader: the editor picks this up once when it opens a new card. */
    private var draft: CardDraft? = null
    fun startDraft(word: String, example: String, meaning: String = "", sentenceId: String? = null) { draft = CardDraft(word, example, meaning, sentenceId) }
    fun takeDraft(): CardDraft? = draft.also { draft = null }

    fun deleteCard(id: String) = viewModelScope.launch { cardRepo.delete(id) }
}

data class CardDraft(val word: String, val example: String, val meaning: String = "", val sentenceId: String? = null)

data class Stats(val countsByDay: Map<java.time.LocalDate, Int>, val streak: Int) {
    fun today(): Int = countsByDay[java.time.LocalDate.now()] ?: 0
}
