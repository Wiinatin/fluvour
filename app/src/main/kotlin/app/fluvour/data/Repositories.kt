package app.fluvour.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.fluvour.language.Languages
import app.fluvour.srs.FacetKind
import app.fluvour.srs.FacetRules
import app.fluvour.srs.needsSarawakFacet
import app.fluvour.srs.sameWord
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
    val dailyGoal: Int = 20,        // reviews per day
    val clipCount: Int = 6,         // suggested clips on the Watch page (1..30)
    val levels: Map<String, String> = emptyMap(), // declared level per language code, e.g. "en" -> "C2" (see ClipLevels)
    val clipLibrary: Boolean = true,  // grow the local clip library in the background (charging, Wi-Fi)
    val art: Boolean = true,          // Settings > Illustrations (saved, so it survives a restart)
    val reduceMotion: Boolean = false // Settings > Reduce motion (saved)
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
    private val kClipCount = intPreferencesKey("clip_count")
    private val kClipLibrary = booleanPreferencesKey("clip_library")
    private val kArt = booleanPreferencesKey("art_enabled")
    private val kReduce = booleanPreferencesKey("reduce_motion")
    private fun kLevel(lang: String) = stringPreferencesKey("level_$lang")
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
            dailyGoal = p[kGoal] ?: 20,
            clipCount = (p[kClipCount] ?: 6).coerceIn(1, 30),
            clipLibrary = p[kClipLibrary] ?: true,
            art = p[kArt] ?: true,
            reduceMotion = p[kReduce] ?: false,
            levels = p.asMap().entries.mapNotNull { (k, v) -> if (k.name.startsWith("level_") && v is String) k.name.removePrefix("level_") to v else null }.toMap()
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
    suspend fun setClipCount(n: Int) = ctx.store.edit { it[kClipCount] = n.coerceIn(1, 30) }
    suspend fun setClipLibrary(on: Boolean) = ctx.store.edit { it[kClipLibrary] = on }
    suspend fun setArt(on: Boolean) = ctx.store.edit { it[kArt] = on }
    suspend fun setReduceMotion(on: Boolean) = ctx.store.edit { it[kReduce] = on }
    suspend fun setLevel(lang: String, code: String) = ctx.store.edit { it[kLevel(lang)] = code }
}

@Singleton
class CardRepository @Inject constructor(
    private val cards: CardDao, private val decks: DeckDao,
    private val logs: ReviewLogDao, private val scheduler: Scheduler,
    private val sentences: SentenceDao, private val sources: SourceDao, private val facets: CardFacetDao
) {
    /** The video moment a card was mined from, or null (no sentence, not from a video, or the video link is gone). */
    suspend fun clipFor(card: CardEntity): app.fluvour.reading.ClipRef? {
        val sentence = card.sentenceId?.let { sentences.get(it) } ?: return null
        val (a, b) = app.fluvour.reading.ClipRef.times(sentence.position) ?: return null
        val src = sentence.sourceId?.let { sources.get(it) } ?: return null
        if (src.kind != "video" || src.deleted || src.url.isNullOrBlank()) return null
        return app.fluvour.reading.ClipRef(src.url, a, b, app.fluvour.reading.ClipRef.words(sentence.position))
    }

    private fun zone() = ZoneId.systemDefault()
    private fun tzOffsetMillis(now: Long) = zone().rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds * 1000L
    /** Start of tomorrow in the learner's local time: review cards due before this are available today. */
    private fun endOfToday(): Long = LocalDate.now().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
    /** Learning path: (card id, word) of the cards whose word is one of [words]. */
    suspend fun cardsForWords(lang: String, words: List<String>): List<Pair<String, String>> =
        if (words.isEmpty()) emptyList() else facets.cardsForWords(lang, words).map { it.id to it.word }
    /** Learning path step 8: memory state of the WORD facet of each card whose word is one of [words]. */
    suspend fun wordMemory(lang: String, words: List<String>): List<WordMemoryRow> =
        if (words.isEmpty()) emptyList() else facets.memoryForWords(lang, words)
    /** Learning path: words of the language held for [minDays] days or more (WORD facet interval). */
    suspend fun matureWords(lang: String, minDays: Int): List<String> = facets.matureWords(lang, minDays)
    /** Learning path step 8: a missed word's review is due today. */
    suspend fun pullDue(cardId: String) = facets.pullDue(cardId, System.currentTimeMillis())
    /** Learning path: due open facets on the cards whose word is one of [words]. */
    suspend fun dueForWords(lang: String, words: List<String>): Int =
        if (words.isEmpty()) 0 else facets.dueForWords(lang, words, System.currentTimeMillis(), endOfToday())
    fun observe(lang: String) = cards.observe(lang)
    fun observeKnownWords(lang: String) = cards.observeKnownWords(lang)
    fun observeLearningWords(lang: String) = cards.observeLearningWords(lang)
    /** Clip recommendations: words you know (graduated cards), and the words you are still learning, newest first. */
    suspend fun knownWordSet(lang: String): Set<String> = cards.knownWordsOnce(lang).toSet()
    suspend fun recentLearningWords(lang: String, limit: Int): List<String> = cards.recentLearningWords(lang, limit)
    /** Every card in every language, in the shape the Anki text export needs. */
    suspend fun allForExport(): List<app.fluvour.srs.AnkiExport.Card> = cards.allActive().map {
        app.fluvour.srs.AnkiExport.Card(it.word, it.meaning, it.example, it.notes, it.languageCode)
    }

    /** Takes back the last review of a card: its schedule goes back to `original` (the card as it was before rating) and the review leaves the history. */
    suspend fun undoReview(original: CardEntity, kind: FacetKind = FacetKind.WORD) {
        val now = System.currentTimeMillis()
        val facet = facetOf(original, kind, now)
        logs.latestIdForFacet(facet.id)?.let { logs.delete(it) }
        facets.upsert(facet.withState(original.srsState(), now))
        if (kind != FacetKind.WORD) return
        // the review may have opened MEANING; if it no longer qualifies and MEANING was never studied, lock it again
        val meaning = facets.get(original.id, FacetKind.MEANING.name)
        if (meaning != null && meaning.unlocked && meaning.reps == 0 && meaning.phase == "NEW" &&
            !FacetRules.shouldUnlockMeaning(original.srsState(), logs.passedCount(facet.id))) facets.upsert(meaning.copy(unlockedAt = null, updatedAt = now))
    }

    /** A facet of a card, made from the card's saved state (WORD) or as a fresh one if it is missing. */
    private suspend fun facetOf(card: CardEntity, kind: FacetKind, now: Long): CardFacetEntity =
        facets.get(card.id, kind.name) ?: when (kind) {
            FacetKind.WORD -> FacetSeed.word(card, now)
            FacetKind.MEANING -> FacetSeed.meaning(card, now)
            FacetKind.CLIP -> FacetSeed.clip(card, now)
            FacetKind.SARAWAK -> FacetSeed.sarawak(card, now)
        }.also { facets.upsert(it) }

    /** A due facet with its card. The card's study fields are that facet's, so everything that reads a CardEntity sees the right schedule. */
    data class DueItem(val card: CardEntity, val kind: FacetKind)

    /** On since step 5: the Review screen can show a Sarawak front. Kept as a switch so Sarawak facets can be held back again if needed. */
    private val sarawakStudyReady = true

    /** Due facets of one kind, or of every kind when [kind] is null (Mixed). Open facets only. */
    suspend fun dueItems(lang: String, kind: FacetKind?, limit: Int = 200): List<DueItem> {
        val due = facets.due(lang, kind?.name, System.currentTimeMillis(), endOfToday(), limit)
        if (due.isEmpty()) return emptyList()
        val byId = due.map { it.cardId }.distinct().chunked(500).flatMap { cards.getMany(it) }.associateBy { it.id }
        return due.mapNotNull { f ->
            val c = byId[f.cardId] ?: return@mapNotNull null
            val k = runCatching { FacetKind.valueOf(f.kind) }.getOrNull() ?: return@mapNotNull null
            if (k == FacetKind.SARAWAK && !sarawakStudyReady) return@mapNotNull null
            DueItem(c.withState(f.srsState(), c.updatedAt), k)
        }
    }

    /** Due count of each kind, for the badges on the mode row. */
    fun observeDueByKind(lang: String) = facets.observeDueByKind(lang, System.currentTimeMillis(), endOfToday()).map { rows ->
        rows.mapNotNull { r -> runCatching { FacetKind.valueOf(r.kind) }.getOrNull()?.takeIf { it != FacetKind.SARAWAK || sarawakStudyReady }?.let { it to r.n } }.toMap()
    }

    /** Makes the facets a card should have, leaving any that exist untouched. CLIP only when the card has a video moment. */
    private suspend fun ensureFacets(card: CardEntity) {
        val now = System.currentTimeMillis()
        val list = mutableListOf(FacetSeed.word(card, now), FacetSeed.meaning(card, now))
        if (clipFor(card) != null) list.add(FacetSeed.clip(card, now))
        if (needsSarawakFacet(card.word, card.variantWord)) list.add(FacetSeed.sarawak(card, now))
        facets.insertIgnore(list)
    }

    /**
     * After an edit: a Malay card that now has a different Sarawak word gets its Sarawak facet (a deleted one comes back with its old memory);
     * one that no longer has it loses the facet. Only the Sarawak facet is touched.
     */
    private suspend fun syncSarawakFacet(card: CardEntity) {
        val now = System.currentTimeMillis()
        val old = facets.get(card.id, FacetKind.SARAWAK.name)
        if (needsSarawakFacet(card.word, card.variantWord)) {
            if (old == null) facets.insertIgnore(listOf(FacetSeed.sarawak(card, now)))
            else if (old.deleted) facets.upsert(old.copy(deleted = false, updatedAt = now))
        } else if (old != null && !old.deleted) facets.upsert(old.copy(deleted = true, updatedAt = now))
    }

    /** One facet of a card (null if it has none or it was deleted). For the mastery ring on the Sarawak page. */
    suspend fun facetOf(cardId: String, kind: FacetKind): CardFacetEntity? = facets.get(cardId, kind.name)?.takeIf { !it.deleted }

    /** A live card of this language with this word. Arabic ignores vowel marks, so a vowelled and a plain spelling are the same word. */
    suspend fun findWordPlain(lang: String, word: String): CardEntity? =
        cards.findWord(lang, word) ?: if (lang == "ar") cards.allActive().firstOrNull { it.languageCode == lang && sameWord(lang, it.word, word) } else null

    /**
     * One-time repair: cards whose "meaning" is the AI's whole 3-line explanation (it starts with "1. word and reading") get a short
     * meaning, and the original text is kept in the notes. Cards you wrote yourself are never touched. Returns how many were changed.
     */
    suspend fun tidyMeanings(): Int {
        var changed = 0
        val now = System.currentTimeMillis()
        for (card in cards.allActive()) {
            val own = app.fluvour.reading.CardText.fromExplanation(card.meaning)
            val original: String
            val split: app.fluvour.reading.CardText.Split
            if (own.notes != null) { original = own.notes; split = own }
            else {
                // shortened by an earlier version but still wordy (cut with "…" or over 40 characters): shorten again from the explanation in the notes
                val blob = app.fluvour.reading.CardText.explanationIn(card.notes) ?: continue
                val m = card.meaning
                if (!(m.endsWith("\u2026") || (m.length > 40 && blob.contains(m)))) continue
                original = blob; split = app.fluvour.reading.CardText.fromExplanation(blob)
                if (split.meaning == m) continue
            }
            val old = card.notes
            val notes = when { old.isNullOrBlank() -> original; old.contains(original) -> old; else -> old + "\n\n" + original }
            cards.upsert(card.copy(meaning = split.meaning, notes = notes, updatedAt = now))
            changed++
        }
        return changed
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
                     example: String?, notes: String?, sentenceId: String? = null,
                     variantWord: String? = null, variantExample: String? = null, root: String? = null, lemma: String? = null,
                     variantExampleMeaning: String? = null): CardEntity {
        val now = System.currentTimeMillis()
        val card = existing?.copy(word = word, meaning = meaning, example = example, notes = notes, updatedAt = now,
                variantWord = variantWord, variantExample = variantExample, root = root, lemma = lemma, variantExampleMeaning = variantExampleMeaning)
            ?: CardEntity(
                id = UUID.randomUUID().toString(), userId = userId, deckId = defaultDeck(userId, lang).id,
                languageCode = lang, word = word, meaning = meaning, example = example, notes = notes,
                sentenceId = sentenceId, createdAt = now, updatedAt = now,
                variantWord = variantWord, variantExample = variantExample, root = root, lemma = lemma, variantExampleMeaning = variantExampleMeaning
            )
        cards.upsert(card)
        if (existing == null) ensureFacets(card) else syncSarawakFacet(card)
        return card
    }

    suspend fun get(id: String) = cards.get(id)

    /** The "Speaking" deck of a language, made on first use. One fixed id per user and language, so it is never made twice. */
    private suspend fun speakingDeck(userId: String, lang: String): DeckEntity {
        val id = UUID.nameUUIDFromBytes("$userId:$lang:speaking".toByteArray()).toString()
        decks.get(id)?.takeIf { !it.deleted }?.let { return it }
        val now = System.currentTimeMillis()
        return DeckEntity(id, userId, lang, "Speaking", now, now).also { decks.upsert(it) }
    }

    /**
     * Speaking (Phase 5): makes a card for each new word, in the "Speaking" deck of [lang]. A word that already has a live card in that
     * language (any deck, upper or lower case) is skipped. Cards start as plain new cards (the entity defaults, as in [save]), with no
     * sentence and the AI's example sentence if it gave one. Returns [words] in the same order, with `cardId` set on the ones that got
     * a card and null on the skipped ones. Remove a card again with [delete].
     */
    suspend fun addSpeakingWords(userId: String, lang: String, words: List<app.fluvour.ai.NewWord>): List<app.fluvour.ai.NewWord> {
        if (words.isEmpty()) return words
        val deck = speakingDeck(userId, lang)
        return words.map { w ->
            val word = w.word.trim(); val meaning = w.meaning.trim()
            if (word.isEmpty() || meaning.isEmpty() || findWordPlain(lang, word) != null) return@map w.copy(cardId = null)
            val now = System.currentTimeMillis()
            val card = CardEntity(
                id = UUID.randomUUID().toString(), userId = userId, deckId = deck.id, languageCode = lang,
                word = word, meaning = meaning, example = w.example?.trim()?.ifBlank { null },
                sentenceId = null, createdAt = now, updatedAt = now
            )
            cards.upsert(card)
            ensureFacets(card)
            w.copy(cardId = card.id)
        }
    }

    /** Stores the generated image (a local file) and its prompt on the card. */
    suspend fun setImage(id: String, path: String?, prompt: String?) {
        val c = cards.get(id) ?: return
        cards.upsert(c.copy(imageUrl = path, imagePrompt = prompt ?: c.imagePrompt, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) {
        val now = System.currentTimeMillis()
        cards.softDelete(id, now)
        facets.softDeleteFor(id, now)
    }

    /** Quiz misses ("Add back"): counts as an Again review, so the word returns soon and its lapses feed leech detection. */
    suspend fun addBack(cardId: String) { get(cardId)?.let { review(it, Rating.AGAIN) } }

    /** Saves a new mnemonic or example from "Try a new angle" (Phase 3 step 4). Null keeps the field as it is. */
    suspend fun setAngle(cardId: String, example: String?, notes: String?) {
        val c = get(cardId) ?: return
        cards.upsert(c.copy(example = example ?: c.example, notes = notes ?: c.notes, updatedAt = System.currentTimeMillis()))
    }

    /** Phase 1's review screen calls this; the log row feeds streaks and the heatmap. */
    suspend fun review(card: CardEntity, rating: Rating): CardEntity = review(card, FacetKind.WORD, rating)

    /** Rates one facet of a card; [card] carries that facet's state. */
    suspend fun review(card: CardEntity, kind: FacetKind, rating: Rating): CardEntity {
        val now = System.currentTimeMillis()
        val facet = facetOf(card, kind, now)
        val before = facet.srsState()
        val after = scheduler.review(before, rating, now, tzOffsetMillis(now))
        facets.upsert(facet.withState(after, now))
        val updated = card.withState(after, now)   // returned to the screen; the facet is what is stored (and synced)
        val elapsedDays = before.lastReviewMillis?.let { (now - it) / 86_400_000.0 } ?: 0.0
        logs.insert(ReviewLogEntity(UUID.randomUUID().toString(), card.userId, card.id, rating.value, now,
            elapsedDays, after.stability, after.difficulty, facet.id))
        // production facet opens once WORD has held
        if (kind == FacetKind.WORD) facets.get(card.id, FacetKind.MEANING.name)?.takeIf { !it.unlocked }?.let { m ->
            if (FacetRules.shouldUnlockMeaning(after, logs.passedCount(facet.id))) facets.upsert(m.copy(unlockedAt = now, updatedAt = now))
        }
        return updated
    }
}
