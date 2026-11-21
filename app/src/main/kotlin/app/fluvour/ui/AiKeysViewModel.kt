package app.fluvour.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.ai.AiProvider
import app.fluvour.ai.AiService
import app.fluvour.ai.MaskedKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings > AI keys: your keys for each provider. Each list is in rotation order; the first key is the one used next. */
@HiltViewModel
class AiKeysViewModel @Inject constructor(private val ai: AiService, private val tts: app.fluvour.audio.TtsManager, private val local: app.fluvour.audio.LocalVoice) : ViewModel() {
    val aiVoice: StateFlow<Boolean> = tts.aiVoice
    val voice: StateFlow<String> = tts.voice
    fun setAiVoice(on: Boolean) = tts.setAiVoice(on)
    private val _ahead = MutableStateFlow(tts.budget.enabled)
    /** Make voices ahead in the background. */
    val aheadOn: StateFlow<Boolean> = _ahead.asStateFlow()
    fun setAhead(on: Boolean) { _ahead.value = on; tts.setAhead(on) }
    fun aheadStatus(): String = tts.budget.statusLine() + tts.batchNote()
    private val _voiceNote = MutableStateFlow<String?>(null)
    /** Under the voice list: what is happening or why a voice could not play. Null when it plays. */
    val voiceNote: StateFlow<String?> = _voiceNote.asStateFlow()
    /** Chooses a voice and plays a sample with it. A failure is shown in [voiceNote] instead of the phone's voice speaking. */
    fun pickVoice(v: String) {
        _voiceNote.value = "Making the sound..."
        tts.previewVoice(v, "Hello! This is my voice. I am happy to help you learn.") { _voiceNote.value = it }
    }
    fun clearVoiceNote() { _voiceNote.value = null }
    /** Debug builds only: speaks one sentence with the model in the test folder and reports the time (voice packs, step 2). */
    private val _localTest = MutableStateFlow<String?>(null)
    val localTest: StateFlow<String?> = _localTest.asStateFlow()
    fun testLocalVoice() { _localTest.value = "Working..."; viewModelScope.launch { _localTest.value = local.debugSpeak() } }
    fun localTestDir(): String = local.testDir().absolutePath
    val keys: StateFlow<Map<AiProvider, List<MaskedKey>>> =
        combine(AiProvider.values().map { p -> ai.pool(p).maskedKeys.map { p to it } }) { it.toMap() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** What was typed as the model for each provider; blank = the default. */
    val models: StateFlow<Map<AiProvider, String>> = ai.modelOverrides.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { viewModelScope.launch { ai.clearOldLimitMarksOnce() } }

    /** Accepts one key or several pasted together (separated by new lines, spaces or commas). */
    fun add(p: AiProvider, text: String) {
        val items = text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) { _message.value = "Paste a key first."; return }
        if (p == AiProvider.CLOUDFLARE && items.any { AiProvider.splitCloudflareKey(it) == null }) {
            _message.value = "Cloudflare needs both the Account ID and the API token."; return
        }
        viewModelScope.launch {
            var added = 0; var dup = 0
            items.forEach { if (ai.pool(p).add(it).alreadyPresent) dup++ else added++ }
            _message.value = buildString {
                append(if (added == 1) "Added 1 ${p.label} key." else "Added $added ${p.label} keys.")
                if (dup > 0) append(" $dup already saved.")
            }
        }
    }

    fun remove(p: AiProvider, id: String) { viewModelScope.launch { ai.pool(p).remove(id) } }
    fun setModel(p: AiProvider, model: String) { viewModelScope.launch { ai.setModel(p, model); _message.value = if (model.isBlank()) "${p.label}: using the default model." else "${p.label}: model saved." } }
    fun clearMessage() { _message.value = null }
}
