package app.fluvour.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.fluvour.language.Languages
import app.fluvour.srs.Rating
import app.fluvour.srs.Scheduler
import app.fluvour.ui.theme.ThemeId
import app.fluvour.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store by preferencesDataStore("fluvour_settings")

/** How the reader lays text out. `Scroll` is one long page; `Pages` splits the text into swipeable pages. */
enum class ReadMode { Scroll, Pages }
/** Reader colours: `App` follows the app theme, the others set their own paper and ink. */
enum class ReadTheme { App, Sepia, Night }
/** Reader options. `size` 0..4, `spacing` 0..2 (tight, normal, loose). Saved, so the reader opens the way it was left. */
data class ReadPrefs(val mode: ReadMode = ReadMode.Scroll, val theme: ReadTheme = ReadTheme.App, val size: Int = 2, val spacing: Int = 1)

data class Settings(
    val targetLang: String, val uiLang: String, val theme: ThemeId, val mode: ThemeMode, val userId: String,
    val autoPlay: Boolean = false,  // speak the word when a review card appears
    val dailyGoal: Int = 20         // reviews per day
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val kTarget = stringPreferencesKey("target_lang")
    private val kUi = stringPreferencesKey("ui_lang")
    private val kTheme = stringPreferencesKey("theme")
    private val kMode = stringPreferencesKey("mode")
    private val kUser = stringPreferencesKey("local_user_id")
    private val kAutoPlay = booleanPreferencesKey("auto_play")
    private val kGoal = intPreferencesKey("daily_goal")
    private val kReadMode = stringPreferencesKey("read_mode")
    private val kReadTheme = stringPreferencesKey("read_theme")
    private val kReadSize = intPreferencesKey("read_size")
    private val kReadSpacing = intPreferencesKey("read_spacing")

    val settings: Flow<Settings> = ctx.store.data.map { p ->
        Settings(
            targetLang = p[kTarget] ?: Languages.default.code,
            uiLang = p[kUi] ?: "en",
            theme = p[kTheme]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.Warm,
            mode = p[kMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            userId = p[kUser] ?: "local",
            autoPlay = p[kAutoPlay] ?: false,
            dailyGoal = p[kGoal] ?: 20
        )
    }
    val readPrefs: Flow<ReadPrefs> = ctx.store.data.map { p ->
        ReadPrefs(
            mode = p[kReadMode]?.let { runCatching { ReadMode.valueOf(it) }.getOrNull() } ?: ReadMode.Scroll,
            theme = p[kReadTheme]?.let { runCatching { ReadTheme.valueOf(it) }.getOrNull() } ?: ReadTheme.App,
            size = (p[kReadSize] ?: 2).coerceIn(0, 4), spacing = (p[kReadSpacing] ?: 1).coerceIn(0, 2)
        )
    }
    suspend fun setReadPrefs(r: ReadPrefs) = ctx.store.edit {
        it[kReadMode] = r.mode.name; it[kReadTheme] = r.theme.name; it[kReadSize] = r.size; it[kReadSpacing] = r.spacing
    }
    /** Local owner id until real auth exists (Phase 0 backend / Phase 9). */
    suspend fun ensureUserId(): String {
        var id = ""
        ctx.store.edit { p -> id = p[kUser] ?: UUID.randomUUID().toString().also { p[kUser] = it } }
        return id
    }
    suspend fun setTargetLang(code: String) = ctx.store.edit { it[kTarget] = code }
    suspend fun setUiLang(code: String) = ctx.store.edit { it[kUi] = code }
    suspend fun setTheme(t: ThemeId) = ctx.store.edit { it[kTheme] = t.name }
    suspend fun setMode(m: ThemeMode) = ctx.store.edit { it[kMode] = m.name }
    suspend fun setAutoPlay(on: Boolean) = ctx.store.edit { it[kAutoPlay] = on }
    suspend fun setDailyGoal(n: Int) = ctx.store.edit { it[kGoal] = n }
}

@Singleton
class CardRepository @Inject constructor(
    private val cards: CardDao, private val decks: DeckDao,
    private val logs: ReviewLogDao, private val scheduler: Scheduler
) {
    private fun zone() = ZoneId.systemDefault()
    private fun tzOffsetMillis(now: Long) = zone().rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds * 1000L
    /** Start of tomorrow in the learner's local time: review cards due before this are available today. */
    private fun endOfToday(): Long = LocalDate.now().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
    fun observe(lang: String) = cards.observe(lang)
    fun observeKnownWords(lang: String) = cards.observeKnownWords(lang)
    /** Every card in every language, in the shape the Anki text export needs. */
    suspend fun allForExport(): List<app.fluvour.srs.AnkiExport.Card> = cards.allActive().map {
        app.fluvour.srs.AnkiExport.Card(it.word, it.meaning, it.example, it.notes, it.languageCode)
    }

    /** Timestamps of every review since the given time; feeds the streak, the daily goal and the heatmap. */
    fun observeReviewTimes(sinceMillis: Long) = logs.observeSince(sinceMillis)
    fun observeDue(lang: String) = cards.observeDueCount(lang, System.currentTimeMillis(), endOfToday())
    suspend fun dueCards(lang: String, limit: Int = 100) = cards.due(lang, System.currentTimeMillis(), endOfToday(), limit)
    fun preview(card: CardEntity) = scheduler.preview(card.srsState(), System.currentTimeMillis(), tzOffsetMillis(System.currentTimeMillis()))

    private suspend fun defaultDeck(userId: String, lang: String): DeckEntity {
        val now = System.currentTimeMillis()
        val id = UUID.nameUUIDFromBytes("$userId:$lang:default".toByteArray()).toString()
        return DeckEntity(id, userId, lang, "Default", now, now).also { decks.upsert(it) }
    }

    suspend fun save(userId: String, lang: String, existing: CardEntity?, word: String, meaning: String,
                     example: String?, notes: String?, sentenceId: String? = null): CardEntity {
        val now = System.currentTimeMillis()
        val card = existing?.copy(word = word, meaning = meaning, example = example, notes = notes, updatedAt = now)
            ?: CardEntity(
                id = UUID.randomUUID().toString(), userId = userId, deckId = defaultDeck(userId, lang).id,
                languageCode = lang, word = word, meaning = meaning, example = example, notes = notes,
                sentenceId = sentenceId, createdAt = now, updatedAt = now
            )
        cards.upsert(card)
        return card
    }

    suspend fun get(id: String) = cards.get(id)

    /** Stores the generated image (a local file) and its prompt on the card. */
    suspend fun setImage(id: String, path: String?, prompt: String?) {
        val c = cards.get(id) ?: return
        cards.upsert(c.copy(imageUrl = path, imagePrompt = prompt ?: c.imagePrompt, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = cards.softDelete(id, System.currentTimeMillis())

    /** Quiz misses ("Add back"): counts as an Again review, so the word returns soon and its lapses feed leech detection. */
    suspend fun addBack(cardId: String) { get(cardId)?.let { review(it, Rating.AGAIN) } }

    /** Saves a new mnemonic or example from "Try a new angle" (Phase 3 step 4). Null keeps the field as it is. */
    suspend fun setAngle(cardId: String, example: String?, notes: String?) {
        val c = get(cardId) ?: return
        cards.upsert(c.copy(example = example ?: c.example, notes = notes ?: c.notes, updatedAt = System.currentTimeMillis()))
    }

    /** Phase 1's review screen calls this; the log row feeds streaks and the heatmap. */
    suspend fun review(card: CardEntity, rating: Rating): CardEntity {
        val now = System.currentTimeMillis()
        val before = card.srsState()
        val after = scheduler.review(before, rating, now, tzOffsetMillis(now))
        val updated = card.withState(after, now)
        cards.upsert(updated)
        val elapsedDays = before.lastReviewMillis?.let { (now - it) / 86_400_000.0 } ?: 0.0
        logs.insert(ReviewLogEntity(UUID.randomUUID().toString(), card.userId, card.id, rating.value, now,
            elapsedDays, after.stability, after.difficulty))
        return updated
    }
}
