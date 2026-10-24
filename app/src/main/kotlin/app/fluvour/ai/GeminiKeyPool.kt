package app.fluvour.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

data class KeyEntry(
    val id: String,
    val key: String,
    val addedAt: Long,
    val limitedAt: Long?   // when the key last hit a quota limit; kept even after the key works again
)

/** What the UI may see: never the raw key. */
data class MaskedKey(
    val id: String,
    val masked: String,
    val addedAt: Long,
    val limitedAt: Long?,
    val active: Boolean
)

data class AddResult(val alreadyPresent: Boolean, val count: Int)

/** Where the pool keeps its entries. [update] must be an atomic read-modify-write. */
interface KeyStorage {
    val entries: Flow<List<KeyEntry>>
    suspend fun update(transform: (List<KeyEntry>) -> List<KeyEntry>)
}

/**
 * Rotating pool of Gemini API keys, ported from the Momori app (which ported it from Torbito's gemini_keys.py).
 * Order: never-limited keys first with the NEWEST added first, then limited keys with the OLDEST
 * limit stamp first. The first key is the active one. There is no timed cooldown; a limited key
 * just sorts behind fresh ones and keeps its stamp.
 */
class GeminiKeyPool(
    private val storage: KeyStorage,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        fun keyId(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }

        fun mask(key: String): String =
            if (key.length > 8) key.take(4) + "..." + key.takeLast(4) else "****"

        fun sorted(entries: List<KeyEntry>): List<KeyEntry> {
            val fresh = entries.withIndex()
                .filter { it.value.limitedAt == null }
                .sortedWith(
                    compareByDescending<IndexedValue<KeyEntry>> { it.value.addedAt }
                        .thenByDescending { it.index }
                )
                .map { it.value }
            val limited = entries.filter { it.limitedAt != null }.sortedBy { it.limitedAt }
            return fresh + limited
        }
    }

    /** Priority-ordered, masked, for the Settings list. Emits again whenever the pool changes. */
    val maskedKeys: Flow<List<MaskedKey>> = storage.entries.map { list ->
        sorted(list).mapIndexed { i, e ->
            MaskedKey(e.id, mask(e.key), e.addedAt, e.limitedAt, active = i == 0)
        }
    }

    suspend fun orderedKeys(): List<KeyEntry> = sorted(storage.entries.first())

    suspend fun hasKeys(): Boolean = storage.entries.first().isNotEmpty()

    suspend fun limitedCount(): Int = storage.entries.first().count { it.limitedAt != null }

    /** How many keys hit a limit at or after [sinceMs] (used for the nightly run summary). */
    suspend fun limitedSince(sinceMs: Long): Int =
        storage.entries.first().count { (it.limitedAt ?: Long.MIN_VALUE) >= sinceMs }

    suspend fun markLimited(id: String) {
        val now = clock()
        storage.update { list -> list.map { if (it.id == id) it.copy(limitedAt = now) else it } }
    }

    /** Removes every limit stamp (used once to wipe stamps that earlier versions set by mistake). */
    suspend fun clearLimits() {
        storage.update { list -> list.map { if (it.limitedAt != null) it.copy(limitedAt = null) else it } }
    }

    /** Throws IllegalArgumentException for an empty key. Duplicates are ignored. */
    suspend fun add(key: String): AddResult {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "API key can't be empty" }
        var present = false
        var count = 0
        storage.update { list ->
            if (list.any { it.key == trimmed }) {
                present = true
                count = list.size
                list
            } else {
                val next = list + KeyEntry(keyId(trimmed), trimmed, clock(), null)
                count = next.size
                next
            }
        }
        return AddResult(present, count)
    }

    suspend fun remove(id: String): Boolean {
        var removed = false
        storage.update { list ->
            val rest = list.filter { it.id != id }
            removed = rest.size != list.size
            rest
        }
        return removed
    }

    suspend fun clear() {
        storage.update { emptyList() }
    }
}
