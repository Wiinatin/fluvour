package app.fluvour.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Tells the screens that keep their own copy of the cards (the Review session) that a card was deleted somewhere else (the editor, Browse). */
object CardEvents {
    private val _deleted = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val deleted: SharedFlow<String> = _deleted.asSharedFlow()
    fun notifyDeleted(id: String) { _deleted.tryEmit(id) }
}
