package app.fluvour.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A word the reader should light up for a moment: paragraph number and the character range inside that paragraph. */
class FlashSpot(val para: Int, val start: Int, val end: Int) {
    val createdAt = System.currentTimeMillis()
    @Volatile var startedAt = 0L
}

/**
 * "Read in the book" asks the reader to light up the word it opened at. The light stays on for [HOLD_MS], then fades over [FADE_MS]
 * (3 seconds in all, counted from the moment the word is first drawn). One spot at a time; a spot nobody drew within [STALE_MS] is ignored.
 */
object ReaderFlash {
    const val HOLD_MS = 2200L
    const val FADE_MS = 800L
    private const val STALE_MS = 10_000L

    private val _spot = MutableStateFlow<FlashSpot?>(null)
    val spot: StateFlow<FlashSpot?> = _spot.asStateFlow()

    fun show(para: Int, start: Int, end: Int) { _spot.value = FlashSpot(para, start, end) }

    /** Called by the paragraph that draws the word. Starts the clock the first time and returns when it started; null when the spot is too old. */
    fun begin(s: FlashSpot): Long? {
        synchronized(s) { if (s.startedAt == 0L) { if (System.currentTimeMillis() - s.createdAt > STALE_MS) return null; s.startedAt = System.currentTimeMillis() } }
        return s.startedAt
    }

    fun clear(s: FlashSpot) { if (_spot.value === s) _spot.value = null }
}
