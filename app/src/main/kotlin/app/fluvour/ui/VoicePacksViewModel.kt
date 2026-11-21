package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.audio.PackManager
import app.fluvour.audio.PackState
import app.fluvour.audio.TtsManager
import app.fluvour.audio.VoiceCatalog
import app.fluvour.audio.VoicePacks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings > Voice packs: the offline voices. Downloads start only from here, only when a button is tapped. */
@HiltViewModel
class VoicePacksViewModel @Inject constructor(private val packs: PackManager, private val tts: TtsManager) : ViewModel() {
    val catalog: StateFlow<VoiceCatalog?> = packs.catalog
    val states: StateFlow<Map<String, PackState>> = packs.states
    val installedIds: StateFlow<Set<String>> = packs.installedIds
    val preferOffline: StateFlow<Boolean> = tts.preferOffline
    fun setPreferOffline(on: Boolean) = tts.setPreferOffline(on)
    val sarawakAccent: StateFlow<Boolean> = tts.sarawakAccent
    fun setSarawakAccent(on: Boolean) = tts.setSarawakAccent(on)
    fun isConfigured() = packs.isConfigured()
    fun onMetered() = packs.onMeteredNetwork()

    private val _note = MutableStateFlow<String?>(null)
    /** One muted line after Test, or null. */
    val note: StateFlow<String?> = _note.asStateFlow()

    init { if (packs.isConfigured()) viewModelScope.launch { packs.refresh() } }

    fun download(id: String) { _note.value = null; packs.download(id) }
    fun cancel(id: String) = packs.cancel(id)
    fun delete(id: String) { tts.stop(); packs.delete(id); _note.value = null }
    fun test(id: String, lang: String) {
        _note.value = null
        tts.speakWithPack(VoicePacks.sample(lang), lang, id) { ok -> if (!ok) _note.value = "This voice could not speak. Try again, or delete it and download it again." }
    }
}
