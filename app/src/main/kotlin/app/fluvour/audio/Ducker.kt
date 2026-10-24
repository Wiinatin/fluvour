package app.fluvour.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells the video player that other audio inside the app is speaking, so it can fade down and back up.
 * TtsManager and ClipPlayer call `set(owner, true)` when they start and `set(owner, false)` when they end;
 * PlayerViewModel watches `active` and ramps the video volume. Safe to call from any thread.
 */
object Ducker {
    private val owners = HashSet<String>()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun set(owner: String, on: Boolean) = synchronized(owners) {
        if (on) owners.add(owner) else owners.remove(owner)
        _active.value = owners.isNotEmpty()
    }
}
