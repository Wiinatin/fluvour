package app.fluvour.path

/** Where one unit stands on the path. */
enum class UnitStatus { GRADUATED, CURRENT, LOCKED }

data class UnitRow(val unit: CurriculumUnit, val status: UnitStatus)

/** What the current unit's badge says: how many of its words are on cards, and how many of those facets are due. */
data class UnitNudge(val linked: Int, val due: Int) {
    /** "6 due" when something is due, "New" when no word of the unit is on a card yet, otherwise no badge. Never blocks anything. */
    val badge: String? get() = when {
        due > 0 -> "$due due"
        linked == 0 -> "New"
        else -> null
    }
}

/**
 * Pure rules for the Path list (no Android, so tools/pathtest runs it on a plain JVM).
 * Units open one at a time (decision 12): graduated units, then the first ungraduated one is current, everything after is locked.
 */
object PathState {
    fun rows(path: CurriculumPath, graduated: Set<String>): List<UnitRow> {
        var currentSeen = false
        return path.units.map { u ->
            when {
                u.id in graduated -> UnitRow(u, UnitStatus.GRADUATED)
                !currentSeen -> { currentSeen = true; UnitRow(u, UnitStatus.CURRENT) }
                else -> UnitRow(u, UnitStatus.LOCKED)
            }
        }
    }

    fun graduatedCount(path: CurriculumPath, graduated: Set<String>) = path.units.count { it.id in graduated }

    /** The path to show: the first one that still has an ungraduated unit, else the last one (everything graduated). */
    fun active(paths: List<CurriculumPath>, graduated: Set<String>): CurriculumPath? =
        paths.firstOrNull { p -> p.units.any { it.id !in graduated } } ?: paths.lastOrNull()

    /** The line under a locked unit: the first locked one names what it waits for, the rest just say locked. */
    fun lockedLine(rows: List<UnitRow>, index: Int): String {
        val firstLocked = rows.indexOfFirst { it.status == UnitStatus.LOCKED }
        val current = rows.firstOrNull { it.status == UnitStatus.CURRENT }
        return if (index == firstLocked && current != null) "Opens after Unit ${current.unit.order} is graduated" else "Locked"
    }
}

/** The link between a unit and the user's cards: a card belongs to a unit when its word is one of the unit's target words. */
object UnitItems {
    fun words(unit: CurriculumUnit): List<String> = unit.words.map { it.word }.distinct()

    /** [cards] are (card id, card word) pairs of one language. Returns the ids of the cards that belong to [unit]. */
    fun cardIds(unit: CurriculumUnit, cards: List<Pair<String, String>>): List<String> {
        val w = words(unit).toSet()
        return cards.filter { it.second in w }.map { it.first }
    }
}
