package app.fluvour.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.fluvour.data.*
import app.fluvour.net.Backend
import app.fluvour.net.Http
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncStore by preferencesDataStore("fluvour_sync")

/**
 * Offline-first sync with the Supabase project. Room is the source of truth on the phone.
 * Each sync pulls remote changes first (a row wins when its updated_at is newer), then pushes local changes.
 * Covers decks, sources, sentences, cards and the review log (sources and sentences go before cards: cards point at sentences). Image files stay on the device; only the image prompt is synced.
 */
@Singleton
class SyncRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val auth: AuthRepository, private val decks: DeckDao, private val cards: CardDao, private val logs: ReviewLogDao,
    private val sources: SourceDao, private val sentences: SentenceDao
) {
    private val lock = Mutex()
    private val kLast = longPreferencesKey("last_sync_at")
    private val kMsg = stringPreferencesKey("last_sync_message")
    private fun key(name: String) = longPreferencesKey(name)

    suspend fun lastSyncAt(): Long? = ctx.syncStore.data.first()[kLast]

    /** Returns a short message for the user. Never throws. */
    suspend fun syncNow(): String = lock.withLock {
        if (!Backend.configured) return@withLock "The backend address is not set."
        val session = auth.session.first() ?: return@withLock "Sign in to sync."
        val token = auth.validToken() ?: return@withLock "Could not reach your account. Try again online."
        val start = System.currentTimeMillis()
        try {
            val pulled = pullDecks(token) + pullSources(token) + pullSentences(token) + pullCards(token) + pullLogs(token)
            val pushed = pushDecks(token, session.userId, start) + pushSources(token, session.userId, start) +
                pushSentences(token, session.userId, start) + pushCards(token, session.userId, start) +
                pushLogs(token, session.userId, start)
            ctx.syncStore.edit { it[kLast] = start }
            "Synced: $pulled received, $pushed sent."
        } catch (e: Exception) {
            "Sync failed: ${e.message ?: "no connection"}"
        }
    }

    // ---------------- pull ----------------
    private suspend fun pullDecks(token: String) = pull(token, "decks", "updated_at", "pull_decks") { j ->
        val r = DeckEntity(j.s("id"), j.s("user_id"), j.s("language_code"), j.s("name"), j.l("created_at"), j.l("updated_at"), j.b("deleted"))
        val local = decks.get(r.id)
        if (local == null || r.updatedAt > local.updatedAt) { decks.upsert(r); true } else false
    }

    private suspend fun pullSources(token: String) = pull(token, "sources", "updated_at", "pull_sources") { j ->
        val r = SourceEntity(j.s("id"), j.s("user_id"), j.s("kind"), j.s("title"), j.sn("url"), j.sn("meta"),
            j.l("created_at"), j.l("updated_at"), j.b("deleted"))
        val local = sources.get(r.id)
        if (local == null || r.updatedAt > local.updatedAt) { sources.upsert(r); true } else false
    }

    private suspend fun pullSentences(token: String) = pull(token, "sentences", "updated_at", "pull_sentences") { j ->
        val r = SentenceEntity(j.s("id"), j.s("user_id"), j.s("language_code"), j.s("text"), j.sn("translation"),
            j.sn("source_id"), j.sn("position"), j.l("created_at"), j.l("updated_at"), j.b("deleted"))
        val local = sentences.get(r.id)
        if (local == null || r.updatedAt > local.updatedAt) { sentences.upsert(r); true } else false
    }

    private suspend fun pullCards(token: String) = pull(token, "cards", "updated_at", "pull_cards") { j ->
        val r = CardEntity(
            id = j.s("id"), userId = j.s("user_id"), deckId = j.s("deck_id"), languageCode = j.s("language_code"),
            word = j.s("word"), meaning = j.s("meaning"), example = j.sn("example"), notes = j.sn("notes"),
            imagePrompt = j.sn("image_prompt"), imageUrl = null, sentenceId = j.sn("sentence_id"),
            phase = j.s("phase"), stability = j.d("stability"), difficulty = j.d("difficulty"), step = j.i("step"),
            lastReviewAt = j.ln("last_review_at"), dueAt = j.ln("due_at"), scheduledDays = j.i("scheduled_days"),
            reps = j.i("reps"), lapses = j.i("lapses"),
            createdAt = j.l("created_at"), updatedAt = j.l("updated_at"), deleted = j.b("deleted")
        )
        val local = cards.get(r.id)
        if (local == null || r.updatedAt > local.updatedAt) { cards.upsert(r.copy(imageUrl = local?.imageUrl)); true } else false
    }

    private suspend fun pullLogs(token: String) = pull(token, "review_log", "reviewed_at", "pull_logs") { j ->
        logs.insertAllIgnore(listOf(ReviewLogEntity(
            j.s("id"), j.s("user_id"), j.s("card_id"), j.i("rating"), j.l("reviewed_at"),
            j.d("elapsed_days"), j.d("stability_after"), j.d("difficulty_after")
        )))
        true
    }

    /** Pages through a table from a saved cursor. `apply` returns true when it changed local data. */
    private suspend fun pull(token: String, table: String, cursorColumn: String, cursorKey: String,
                             apply: suspend (JSONObject) -> Boolean): Int {
        var cursor = ctx.syncStore.data.first()[key(cursorKey)] ?: 0L
        var changed = 0
        while (true) {
            val r = Http.request("GET", "${Backend.url}/rest/v1/$table?select=*&$cursorColumn=gte.$cursor&order=$cursorColumn.asc&limit=$PAGE",
                Backend.headers(token))
            if (!r.ok) error("$table: ${r.code}")
            val rows = JSONArray(r.body)
            var maxCursor = cursor
            for (i in 0 until rows.length()) {
                val j = rows.getJSONObject(i)
                if (apply(j)) changed++
                maxCursor = maxOf(maxCursor, j.l(cursorColumn))
            }
            val advanced = maxCursor > cursor
            cursor = maxCursor
            ctx.syncStore.edit { it[key(cursorKey)] = cursor }
            if (rows.length() < PAGE || !advanced) break
        }
        return changed
    }

    // ---------------- push ----------------
    private suspend fun pushDecks(token: String, uid: String, start: Long): Int {
        val rows = decks.changedSince(pushCursor("push_decks"))
        push(token, "decks", rows.map { JSONObject()
            .put("id", it.id).put("user_id", uid).put("language_code", it.languageCode).put("name", it.name)
            .put("created_at", it.createdAt).put("updated_at", it.updatedAt).put("deleted", it.deleted) }, upsert = true)
        setPushCursor("push_decks", start); return rows.size
    }

    private suspend fun pushSources(token: String, uid: String, start: Long): Int {
        val rows = sources.changedSince(pushCursor("push_sources"))
        push(token, "sources", rows.map { it2 -> JSONObject()
            .put("id", it2.id).put("user_id", uid).put("kind", it2.kind).put("title", it2.title)
            .put("url", it2.url ?: JSONObject.NULL).put("meta", it2.meta ?: JSONObject.NULL)
            .put("created_at", it2.createdAt).put("updated_at", it2.updatedAt).put("deleted", it2.deleted) }, upsert = true)
        setPushCursor("push_sources", start); return rows.size
    }

    private suspend fun pushSentences(token: String, uid: String, start: Long): Int {
        val rows = sentences.changedSince(pushCursor("push_sentences"))
        push(token, "sentences", rows.map { s -> JSONObject()
            .put("id", s.id).put("user_id", uid).put("language_code", s.languageCode).put("text", s.text)
            .put("translation", s.translation ?: JSONObject.NULL).put("source_id", s.sourceId ?: JSONObject.NULL)
            .put("position", s.position ?: JSONObject.NULL)
            .put("created_at", s.createdAt).put("updated_at", s.updatedAt).put("deleted", s.deleted) }, upsert = true)
        setPushCursor("push_sentences", start); return rows.size
    }

    private suspend fun pushCards(token: String, uid: String, start: Long): Int {
        val rows = cards.changedSince(pushCursor("push_cards"))
        push(token, "cards", rows.map { c -> JSONObject()
            .put("id", c.id).put("user_id", uid).put("deck_id", c.deckId).put("language_code", c.languageCode)
            .put("word", c.word).put("meaning", c.meaning).put("example", c.example ?: JSONObject.NULL)
            .put("notes", c.notes ?: JSONObject.NULL).put("image_prompt", c.imagePrompt ?: JSONObject.NULL)
            .put("image_url", JSONObject.NULL)   // local file path, never synced
            .put("sentence_id", c.sentenceId ?: JSONObject.NULL)
            .put("phase", c.phase).put("stability", c.stability).put("difficulty", c.difficulty).put("step", c.step)
            .put("last_review_at", c.lastReviewAt ?: JSONObject.NULL).put("due_at", c.dueAt ?: JSONObject.NULL)
            .put("scheduled_days", c.scheduledDays).put("reps", c.reps).put("lapses", c.lapses)
            .put("created_at", c.createdAt).put("updated_at", c.updatedAt).put("deleted", c.deleted) }, upsert = true)
        setPushCursor("push_cards", start); return rows.size
    }

    private suspend fun pushLogs(token: String, uid: String, start: Long): Int {
        val rows = logs.changedSince(pushCursor("push_logs"))
        push(token, "review_log", rows.map { l -> JSONObject()
            .put("id", l.id).put("user_id", uid).put("card_id", l.cardId).put("rating", l.rating)
            .put("reviewed_at", l.reviewedAt).put("elapsed_days", l.elapsedDays)
            .put("stability_after", l.stabilityAfter).put("difficulty_after", l.difficultyAfter) }, upsert = false)
        setPushCursor("push_logs", start); return rows.size
    }

    private suspend fun push(token: String, table: String, rows: List<JSONObject>, upsert: Boolean) {
        val prefer = if (upsert) "resolution=merge-duplicates,return=minimal" else "resolution=ignore-duplicates,return=minimal"
        for (chunk in rows.chunked(200)) {
            val body = JSONArray().also { a -> chunk.forEach { a.put(it) } }.toString()
            val r = Http.request("POST", "${Backend.url}/rest/v1/$table?on_conflict=id",
                Backend.headers(token, mapOf("Prefer" to prefer)), body)
            if (!r.ok) error("$table: ${r.code} ${r.body.take(120)}")
        }
    }

    private suspend fun pushCursor(name: String) = ctx.syncStore.data.first()[key(name)] ?: 0L
    private suspend fun setPushCursor(name: String, v: Long) { ctx.syncStore.edit { it[key(name)] = v } }

    private companion object { const val PAGE = 1000 }
}

// Small JSON readers (org.json throws on missing keys and returns "null" strings for nulls).
private fun JSONObject.s(k: String): String = getString(k)
private fun JSONObject.sn(k: String): String? = if (isNull(k)) null else getString(k)
private fun JSONObject.l(k: String): Long = getLong(k)
private fun JSONObject.ln(k: String): Long? = if (isNull(k)) null else getLong(k)
private fun JSONObject.i(k: String): Int = getInt(k)
private fun JSONObject.d(k: String): Double = getDouble(k)
private fun JSONObject.b(k: String): Boolean = getBoolean(k)
