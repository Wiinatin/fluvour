package app.fluvour.reading

import android.content.Context
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.srs.FacetKind
import app.fluvour.srs.Phase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** An unlocked word: whether a card was made or the deck already had it, whether it unlocked today, and how well the card is known (null without a card). */
data class SarawakItem(val entry: SarawakEntry, val status: SarawakStatus, val today: Boolean, val mastery: SarawakMastery?)

data class SarawakStats(val total: Int, val unlocked: Int, val newLeftToday: Int)

/** Result of an unlock: ids of the new cards, and how many words were skipped because the deck already had them. */
data class SarawakUnlockResult(val added: List<String>, val skipped: Int)

/**
 * The premade Sarawak deck. Like Heisig, a few new cards unlock each day in a fixed order; unlocking adds them to the Malay deck as
 * normal cards (through `CardRepository.save`), so they are reviewed, scheduled and synced like any other card. This class only
 * remembers which pack words have unlocked (one private file, `sarawak-progress.tsv`) and never touches a card afterwards.
 */
@Singleton
class SarawakRepository @Inject constructor(
    @ApplicationContext private val ctx: Context, private val cardRepo: CardRepository, private val settings: SettingsRepository
) {
    companion object { const val DEFAULT_NEW_PER_DAY = 10; const val MAX_NEW_PER_DAY = 50 }

    private val prefs = ctx.getSharedPreferences("sarawak", Context.MODE_PRIVATE)
    private val _newPerDay = MutableStateFlow(prefs.getInt("new_per_day", DEFAULT_NEW_PER_DAY).coerceIn(0, MAX_NEW_PER_DAY))
    /** How many new Sarawak cards unlock each day (0 pauses). Shown in Settings by step 6. */
    val newPerDay: StateFlow<Int> = _newPerDay.asStateFlow()

    fun setNewPerDay(n: Int) {
        val v = n.coerceIn(0, MAX_NEW_PER_DAY)
        prefs.edit().putInt("new_per_day", v).apply()
        _newPerDay.value = v
    }

    private val lock = Mutex()
    private var pack: List<SarawakEntry>? = null
    private var progress: MutableMap<String, SarawakProgress>? = null
    private val file get() = File(ctx.filesDir, SarawakPackData.FILE)

    private fun startOfToday() = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun all(): List<SarawakEntry> = pack ?: run {
        val list = runCatching { ctx.assets.open(SarawakPackData.ASSET).bufferedReader().useLines { SarawakPackData.parse(it) } }.getOrDefault(emptyList())
        pack = list
        list
    }

    private fun load(): MutableMap<String, SarawakProgress> = progress ?: run {
        val m = if (file.exists()) runCatching { file.bufferedReader().useLines { SarawakPackData.decode(it) } }.getOrDefault(LinkedHashMap()) else LinkedHashMap()
        progress = m
        m
    }

    private fun save(m: Map<String, SarawakProgress>) {
        val tmp = File(ctx.filesDir, SarawakPackData.FILE + ".tmp")
        tmp.writeText(SarawakPackData.encode(m))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun unlockedToday(m: Map<String, SarawakProgress>): Int { val t = startOfToday(); return m.values.count { it.introducedAt >= t } }

    suspend fun stats(): SarawakStats = withContext(Dispatchers.IO) {
        lock.withLock {
            val list = all(); val m = load()
            SarawakStats(list.size, list.count { it.key in m }, minOf(list.count { it.key !in m }, (_newPerDay.value - unlockedToday(m)).coerceAtLeast(0)))
        }
    }

    /**
     * Cards made before the meaning field existed: fills in the meaning of the Sarawak example from the pack. Only a card whose
     * meaning is still empty and whose example is still the pack's own sentence is touched; nothing else on the card changes.
     * Safe to run every time (it does nothing once filled). Returns how many cards were filled.
     */
    suspend fun fillExampleMeanings(): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = load(); var n = 0
            val userId = settings.ensureUserId()
            for (e in all()) {
                if (e.example.isBlank() || e.exampleMeaning.isBlank()) continue
                val p = m[e.key]?.takeIf { it.status == SarawakStatus.ADDED && it.cardId.isNotEmpty() } ?: continue
                val card = cardRepo.get(p.cardId) ?: continue
                // the pack sentence sits in the Sarawak example, or in the plain example for a card with no standard Malay word
                val sentence = if (card.variantWord.isNullOrBlank()) card.example else card.variantExample
                if (card.deleted || !card.variantExampleMeaning.isNullOrBlank() || sentence != e.example) continue
                cardRepo.save(userId, card.languageCode, card, card.word, card.meaning, card.example, card.notes, card.sentenceId,
                    card.variantWord, card.variantExample, card.root, card.lemma, e.exampleMeaning)
                n++
            }
            n
        }
    }

    /**
     * Every unlocked word: today's first (day order), then earlier ones, newest first. Mastery comes from the facet the card is learned on
     * (the Sarawak facet, or the word facet for a card with no separate Sarawak form); nothing is stored here.
     */
    suspend fun items(): List<SarawakItem> = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = load(); val t = startOfToday()
            val list = all().mapNotNull { e ->
                val p = m[e.key] ?: return@mapNotNull null
                val facet = if (p.status == SarawakStatus.ADDED && p.cardId.isNotEmpty())
                    cardRepo.facetOf(p.cardId, FacetKind.SARAWAK) ?: cardRepo.facetOf(p.cardId, FacetKind.WORD) else null
                val mastery = facet?.let { f -> runCatching { SarawakMasteryRules.of(Phase.valueOf(f.phase), f.stability) }.getOrNull() }
                SarawakItem(e, p.status, p.introducedAt >= t, mastery)
            }
            list.filter { it.today }.sortedBy { it.entry.dayOrder } + list.filter { !it.today }.sortedByDescending { it.entry.dayOrder }
        }
    }

    /** Unlocks today's share of new cards (nothing more if today's share is already used). Safe to call every time the page opens. */
    suspend fun unlockToday(): SarawakUnlockResult = unlock { list, m -> SarawakPackData.nextToUnlock(list, m.keys, _newPerDay.value, unlockedToday(m)) }

    /** "Learn more": unlocks the next [n] cards now, ignoring the daily limit. */
    suspend fun unlockMore(n: Int): SarawakUnlockResult = unlock { list, m -> SarawakPackData.more(list, m.keys, n) }

    private suspend fun unlock(pick: (List<SarawakEntry>, Map<String, SarawakProgress>) -> List<SarawakEntry>): SarawakUnlockResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = load()
            val todo = pick(all(), m)
            if (todo.isEmpty()) return@withLock SarawakUnlockResult(emptyList(), 0)
            val userId = settings.ensureUserId()
            val added = ArrayList<String>(); var skipped = 0
            for (e in todo) {
                val now = System.currentTimeMillis()
                val f = SarawakPackData.cardFields(e)
                // a word the deck already has is skipped (same check as the speaking deck); the old card is left exactly as it is
                if (f == null || cardRepo.findWordPlain(SarawakPackData.LANG, f.word) != null) {
                    m[e.key] = SarawakProgress(now, SarawakStatus.SKIPPED, ""); skipped++
                } else {
                    val card = cardRepo.save(userId, SarawakPackData.LANG, null, f.word, f.meaning, f.example, f.notes, null, f.variantWord, f.variantExample, null, null, f.variantExampleMeaning)
                    m[e.key] = SarawakProgress(now, SarawakStatus.ADDED, card.id); added.add(card.id)
                }
                save(m) // after every card, so an interruption never makes a word unlock twice
            }
            SarawakUnlockResult(added, skipped)
        }
    }

    /** Forgets which words unlocked (cards already made stay in the deck). The words would then be skipped as duplicates. */
    suspend fun reset() = withContext(Dispatchers.IO) { lock.withLock { progress = LinkedHashMap(); file.delete() } }
}
