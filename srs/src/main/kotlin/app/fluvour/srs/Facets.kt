package app.fluvour.srs

import java.util.UUID

/** The tracks one note can have. Each has its own [CardState]. These are the same three fronts the Review screen already shows. */
enum class FacetKind { WORD, MEANING, CLIP, SARAWAK }

/** One fixed id per card and kind, so two devices that each create the facet never make duplicates. */
fun facetId(cardId: String, kind: FacetKind): String = UUID.nameUUIDFromBytes("$cardId:${kind.name}".toByteArray()).toString()

/** Malay: a Sarawak facet exists only when the Sarawak word is filled and differs from the standard word (so one word is never reviewed twice). */
fun needsSarawakFacet(word: String, variantWord: String?): Boolean {
    val v = variantWord?.trim().orEmpty()
    return v.isNotEmpty() && !v.equals(word.trim(), ignoreCase = true)
}

/** Rules for when a locked facet opens. Constants live here so they are easy to tune. */
object FacetRules {
    const val UNLOCK_MIN_PASSED = 2           // reviews on the WORD facet rated above Again
    const val UNLOCK_MIN_INTERVAL_DAYS = 3    // WORD interval, in days

    /** MEANING (the production facet) opens once WORD has held: 2 reviews above Again and an interval of 3 days or more. */
    fun shouldUnlockMeaning(word: CardState, passedReviews: Int): Boolean =
        word.phase == Phase.REVIEW && passedReviews >= UNLOCK_MIN_PASSED && word.scheduledDays >= UNLOCK_MIN_INTERVAL_DAYS
}

/** A facet waiting in a study queue. */
data class QueueItem(val facetId: String, val cardId: String, val kind: FacetKind, val phase: Phase, val dueMillis: Long?)

/** Mixed mode: all facets in one queue. */
object FacetQueue {
    /** Due-time order across all facets, new facets last (same as today's single-card queue). Stable for ties. */
    fun mixedOrder(items: List<QueueItem>): List<QueueItem> =
        items.sortedWith(compareBy<QueueItem>({ if (it.phase == Phase.NEW) 1 else 0 }, { it.dueMillis ?: Long.MAX_VALUE }))

    /** Sibling burying: drops facets of cards already answered this session, and keeps only the first facet of each card. */
    fun buried(ordered: List<QueueItem>, answeredCardIds: Set<String>): List<QueueItem> =
        ordered.filter { it.cardId !in answeredCardIds }.distinctBy { it.cardId }
}
