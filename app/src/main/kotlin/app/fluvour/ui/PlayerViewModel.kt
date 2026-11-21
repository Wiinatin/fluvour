package app.fluvour.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import app.fluvour.audio.TtsManager
import app.fluvour.data.SettingsRepository
import app.fluvour.data.SourceDao
import app.fluvour.data.SourceEntity
import app.fluvour.data.LikedVideoDao
import app.fluvour.data.LikedVideoEntity
import app.fluvour.data.WatchHistoryDao
import app.fluvour.data.WatchHistoryEntity
import androidx.media3.common.Player
import app.fluvour.media.Playable
import app.fluvour.media.SpeechListener
import app.fluvour.media.SubLine
import app.fluvour.media.SpectrumTap
import app.fluvour.media.VideoHit
import app.fluvour.media.WaveformExtractor
import app.fluvour.media.YouTubeSource
import app.fluvour.reading.DictResult
import app.fluvour.reading.DictationDiff
import app.fluvour.reading.DictionaryRepository
import app.fluvour.reading.ImportedLookup
import app.fluvour.reading.LevelInfo
import app.fluvour.reading.LevelRepository
import app.fluvour.reading.LookupRepository
import app.fluvour.reading.SentenceRepository
import app.fluvour.reading.Token
import app.fluvour.reading.WordSplitter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

/** `tokens[i]` are the words of `item.lines[i]` (with base forms and readings); `sourceId` is the saved `sources` row (kind = video). */
data class PlayerUi(
    val loading: Boolean = false, val error: String? = null, val item: Playable? = null,
    val tokens: List<List<Token>> = emptyList(), val sourceId: String? = null
)

/** The Watch search bar: `hits` is null until a search has been run for the typed words. */
data class SearchUi(val loading: Boolean = false, val error: String? = null, val hits: List<VideoHit>? = null)

/** Where the video is shown: NONE = only on the Watch page, MINI = floating window over other screens, AUDIO = nothing on screen, sound continues. */
enum class MiniMode { NONE, MINI, AUDIO }

/** How many videos the watch history keeps (the oldest are dropped). */
private const val HISTORY_CAP = 50
/** Dictation plays a line from a little before it starts to a little after it ends, so the first and last sounds are not clipped. */
private const val DICT_LEAD_MS = 400L
private const val DICT_TAIL_MS = 350L

/** The choices for how many times a loop repeats; 0 = until you stop it. */
private val REPEAT_STEPS = listOf(0, 2, 3, 5, 10)

/** A-B loop ends in ms. The loop runs only when both are set. */
data class AbLoop(val a: Long? = null, val b: Long? = null)

/**
 * Dictation and shadowing share this state (`on`). `index` is the subtitle line being practised, `revealed` is true once the answer was
 * submitted (dictation) or heard (shadowing) and the grade is shown. `answer` is the typed or recognised text; `result` is its grade
 * against the real line (null for a moment while it is worked out).
 * Shadowing only: `shadow` is true, the real line is visible from the start, `listening` is true while the microphone is open,
 * and `note` is a short message under the mic (nothing heard, no permission, ...).
 */
data class DictateUi(
    val on: Boolean = false, val index: Int = 0, val revealed: Boolean = false, val answer: String = "", val result: DictResult? = null,
    val shadow: Boolean = false, val listening: Boolean = false, val note: String? = null
)

/** The tapped word and the subtitle line it sits in. */
data class VideoLookup(val word: String, val sentence: String, val startMs: Long, val endMs: Long, val lemma: String? = null, val reading: String? = null, val words: List<app.fluvour.media.SubWord> = emptyList())

/** A subtitle line as one sentence: line breaks inside a cue are joined (no space for Japanese, Chinese and Thai). */
fun subLineText(l: SubLine, lang: String): String =
    l.text.trim().replace(Regex("\\s*\\n\\s*"), if (lang in setOf("ja", "zh", "th")) "" else " ")

/** Owns the video player (Media3), loads a video through the MediaSource, and runs word lookup and sentence mining (Phase 4.1b). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context, private val settingsRepo: SettingsRepository,
    private val source: YouTubeSource, private val tts: TtsManager,
    private val sources: SourceDao, private val sentenceRepo: SentenceRepository,
    private val lookups: LookupRepository, private val levelRepo: LevelRepository, private val dictRepo: DictionaryRepository,
    private val historyDao: WatchHistoryDao,
    private val likedDao: LikedVideoDao,
    private val analyzed: app.fluvour.data.AnalyzedVideoDao
) : ViewModel() {
    // Audio focus handled by ExoPlayer: other apps' audio and in-app TTS that asks for focus make it duck or pause; headphone unplug pauses.
    // The wake mode keeps playback alive with the screen off. PlaybackService (media/PlaybackService.kt) shows the media notification.
    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        .build()
    init {
        app.fluvour.media.PlaybackHolder.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) runCatching { ctx.startService(android.content.Intent(ctx, app.fluvour.media.PlaybackService::class.java)) }
            }
        })
    }
    // ---- Mini player (floating window / audio only) ----
    private val _mini = MutableStateFlow(MiniMode.NONE)
    val mini: StateFlow<MiniMode> = _mini.asStateFlow()
    private val _playing = MutableStateFlow(false)
    /** Live play state, for the mini window's play/pause button. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    /** Where the window was last dropped (kept while the app runs): which side, and how far down as a 0..1 fraction. */
    var miniRight = true
    var miniYFrac = 0.62f
    private var resumeAfterMic = false
    /** The over-other-apps window asked to go back to the Watch page (Root navigates). */
    val openWatch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying }
        })
        // Other in-app audio (TTS, native clips) fades the video down, then back up. Android audio focus already handles other apps.
        viewModelScope.launch {
            app.fluvour.audio.Ducker.active.collectLatest { on ->
                val target = if (on) 0.2f else 1f
                val steps = if (on) 8 else 16                 // about 160 ms down, 320 ms up
                val from = player.volume
                for (i in 1..steps) { player.volume = from + (target - from) * i / steps; delay(20) }
                player.volume = target
            }
        }
    }

    /** Called when the Watch tab is left: a playing video goes to the floating window, a paused one is left alone. */
    fun onLeaveWatch() { onWatch = false; _mini.value = if (_ui.value.item != null && player.playWhenReady && player.playbackState != Player.STATE_ENDED) MiniMode.MINI else MiniMode.NONE }
    /** Called when the Watch tab is shown: the full player takes over, the window and audio-only state are cleared. */
    fun onEnterWatch() { onWatch = true; _mini.value = MiniMode.NONE }
    /** True while the landscape full-screen Watch screen is showing: Root hides the bottom tab bar then. */
    val immersive = MutableStateFlow(false)
    /** The window's X: pause, remember the place, hide. The video stays loaded on the Watch page. */
    fun closeMini() { player.pause(); saveProgress(); _mini.value = MiniMode.NONE }
    /** Hide the window but keep the sound going (the notification, or the Watch page, can stop it). */
    fun minimizeToAudio() { _mini.value = MiniMode.AUDIO }
    fun togglePlay() {
        if (player.isPlaying) player.pause()
        else { if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(); player.play() }
    }
    /** Speaking screens (microphone): pause the video on entry; `micLeft` resumes it if it was playing. */
    fun micEntered() { if (player.playWhenReady) { resumeAfterMic = true; player.pause() } }
    fun micLeft() { if (resumeAfterMic) { resumeAfterMic = false; player.play() } }

    /** A media item with its title, so the notification can show what is playing. */
    private fun mi(uri: String, title: String) = MediaItem.Builder().setUri(uri)
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()).build()
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    var lang: String = "en"; private set

    // ---- Speed and A-B loop (Phase 4 item 4) ----
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private val _loop = MutableStateFlow(AbLoop())
    val loop: StateFlow<AbLoop> = _loop.asStateFlow()
    // How many times a loop repeats (0 = until you stop it) and the pause between repeats; both are remembered.
    private val loopPrefs = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val _repeats = MutableStateFlow(loopPrefs.getInt("loop_repeats", 0))
    val repeats: StateFlow<Int> = _repeats.asStateFlow()
    private val _gapMode = MutableStateFlow(loopPrefs.getInt("loop_gap", 0))
    /** 0 = no pause, 1 = 1 second, 2 = 2 seconds, 3 = as long as the line, so you can say it back. */
    val gapMode: StateFlow<Int> = _gapMode.asStateFlow()
    private val _pass = MutableStateFlow(0)
    /** Plays of the current loop that are already finished. */
    val pass: StateFlow<Int> = _pass.asStateFlow()
    fun cycleRepeats() { val n = REPEAT_STEPS[(REPEAT_STEPS.indexOf(_repeats.value) + 1) % REPEAT_STEPS.size]; _repeats.value = n; loopPrefs.edit().putInt("loop_repeats", n).apply() }
    fun cycleGap() { val n = (_gapMode.value + 1) % 4; _gapMode.value = n; loopPrefs.edit().putInt("loop_gap", n).apply() }

    // ---- Audio-only mode and waveform ----
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()
    private val _wave = MutableStateFlow<FloatArray?>(null)
    /** Loudness bars for the scrubber; null while loading, or if the audio is too long or cannot be read (the scrubber then shows a plain track). */
    val wave: StateFlow<FloatArray?> = _wave.asStateFlow()
    private var waveJob: Job? = null

    // ---- Live spectrum (Android Visualizer on the player's audio session) ----
    private val tap = SpectrumTap()
    private val _spectrum = MutableStateFlow(FloatArray(SpectrumTap.BANDS))
    /** Fresh bars about 30 times a second while the spectrum is on. */
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()
    private val _spectrumOn = MutableStateFlow(false)
    val spectrumOn: StateFlow<Boolean> = _spectrumOn.asStateFlow()
    private var spectrumAllowed = false

    /** Call once the microphone permission is granted. */
    fun enableSpectrum() { spectrumAllowed = true; _spectrumOn.value = tap.attach(player.audioSessionId) }

    /** Swaps between the video stream and the sound-only stream at the same position and play state. */
    fun toggleAudioOnly() {
        val item = _ui.value.item ?: return
        val audio = item.audioUrl ?: return
        val on = !_audioOnly.value; _audioOnly.value = on
        val pos = player.currentPosition; val was = player.playWhenReady
        player.setMediaItem(mi(if (on) audio else item.streamUrl, item.title), pos); player.prepare(); player.playWhenReady = was
    }

    init {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { if (spectrumAllowed) _spectrumOn.value = tap.attach(audioSessionId) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveProgress() }
        })
        viewModelScope.launch {
            while (true) {
                delay(33)
                if (_spectrumOn.value) _spectrum.value = tap.frame(player.isPlaying)
            }
        }
        // Remember where you are every few seconds (a pause saves too), so a killed app can pick up again.
        viewModelScope.launch { while (true) { delay(5000); if (player.isPlaying) saveProgress() } }
        // While playing with both ends set, jump back to A as soon as the playhead reaches B.
        viewModelScope.launch {
            while (true) {
                delay(40)
                val l = _loop.value
                val a = l.a; val b = l.b
                if (a != null && b != null && player.isPlaying && player.currentPosition >= b) {
                    val done = _pass.value + 1
                    val max = _repeats.value
                    if (max > 0 && done >= max) { clearLoop() }   // enough repeats: keep playing past the end
                    else {
                        _pass.value = done
                        val gap = when (_gapMode.value) { 1 -> 1000L; 2 -> 2000L; 3 -> ((b - a) / player.playbackParameters.speed).toLong(); else -> 0L }
                        if (gap > 0) {
                            player.pause(); delay(gap)
                            if (_loop.value != l) continue   // stopped or changed during the pause
                            player.seekTo(a); player.play()
                        } else player.seekTo(a)
                    }
                }
            }
        }
    }

    /** Pitch stays natural at any speed. The speed is kept when you open another video. */
    fun setSpeed(s: Float) { _speed.value = s; player.setPlaybackSpeed(s) }
    /** Skip 10 s back (-1) or forward (1); stops at 0:00 and at the end. */
    fun skipBy(dir: Int) {
        val d = player.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
        player.seekTo(app.fluvour.ui.screens.SkipMath.target(player.currentPosition, d, dir))
    }
    private var boosting = false
    /** Hold-for-2x: only the player speeds up. The chosen speed (`speed`) is untouched, and comes back when this is switched off. */
    fun boost(on: Boolean) { if (on == boosting) return; boosting = on; player.setPlaybackSpeed(if (on) 2f else _speed.value) }
    fun markA() { val p = player.currentPosition; _pass.value = 0; _loop.value = AbLoop(p, _loop.value.b?.takeIf { it > p + 300 }) }
    fun markB() { val a = _loop.value.a ?: return; val p = player.currentPosition; if (p > a + 300) { _pass.value = 0; _loop.value = AbLoop(a, p) } }
    /** Loop one subtitle line: A and B at its start and end, and start playing from A. */
    fun loopLine(line: SubLine) { _pass.value = 0; _loop.value = AbLoop(line.startMs, line.endMs); player.seekTo(line.startMs); player.play() }
    fun clearLoop() { _pass.value = 0; _loop.value = AbLoop() }

    // ---- Dictation: hear one line, type it, then see the real subtitle ----
    private val _dictate = MutableStateFlow(DictateUi())
    val dictate: StateFlow<DictateUi> = _dictate.asStateFlow()
    private var dictStop: androidx.media3.exoplayer.PlayerMessage? = null
    private var speech: SpeechListener? = null

    /** Long-press on play: starts dictation at the line playing now, or leaves it. */
    fun toggleDictation(posMs: Long) {
        if (_dictate.value.on) { exitDictation(); return }
        val lines = _ui.value.item?.lines ?: return
        if (lines.isEmpty()) return
        clearLoop()
        // starts in the mode used last time
        _dictate.value = DictateUi(on = true, index = lines.indexOfLast { it.startMs <= posMs }.coerceAtLeast(0), shadow = loopPrefs.getBoolean("practice_shadow", false))
        playDictLine()
    }
    fun exitDictation() { dictStop?.cancel(); dictStop = null; stopListening(); _dictate.value = DictateUi(); player.pause() }

    /** The Dictation / Shadowing switch in the practice header: same line, other mode. */
    fun setShadow(on: Boolean) {
        val d = _dictate.value
        if (!d.on || d.shadow == on) return
        loopPrefs.edit().putBoolean("practice_shadow", on).apply()
        stopListening()
        _dictate.value = DictateUi(on = true, index = d.index, shadow = on)
        playDictLine()
    }

    /** Plays the current dictation line once, then pauses by itself at its end. Also used for "hear it again". */
    fun playDictLine() {
        val d = _dictate.value
        if (!d.on) return
        val line = _ui.value.item?.lines?.getOrNull(d.index) ?: return
        dictStop?.cancel()
        stopListening()
        _dictate.update { it.copy(note = null) }
        player.seekTo((line.startMs - DICT_LEAD_MS).coerceAtLeast(0L))
        // at the end of the line: pause; when shadowing, open the microphone for your turn
        dictStop = player.createMessage { _, _ -> player.pause(); if (_dictate.value.shadow) listenSoon(d.index) }
            .setLooper(player.applicationLooper).setPosition(line.endMs + DICT_TAIL_MS).setDeleteAfterDelivery(true).also { it.send() }
        player.play()
    }
    /** Submit: show the real subtitle at once, then the grade (missed words, wrong guesses) as soon as it is worked out. */
    fun dictReveal(answer: String) {
        dictStop?.cancel(); dictStop = null; player.pause()
        val d = _dictate.value
        if (!d.on) return
        val idx = d.index
        val line = _ui.value.item?.lines?.getOrNull(idx) ?: return
        val refTokens = _ui.value.tokens.getOrNull(idx).orEmpty()
        _dictate.update { it.copy(revealed = true, answer = answer, result = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                DictationDiff.grade(line.text, refTokens.ifEmpty { WordSplitter.split(line.text, lang) }, answer, lang)
            }
            _dictate.update { if (it.on && it.index == idx && it.revealed) it.copy(result = r) else it }
        }
    }
    fun dictRetry() { stopListening(); _dictate.update { it.copy(revealed = false, result = null, note = null) }; playDictLine() }
    fun dictNext() {
        val n = _ui.value.item?.lines?.size ?: return
        val d = _dictate.value
        if (d.on && d.index + 1 < n) { stopListening(); _dictate.value = DictateUi(on = true, index = d.index + 1, shadow = d.shadow); playDictLine() }
    }

    // ---- Shadowing: hear the line, say it back, see how many words the phone understood ----
    /** A moment after the line ends, so the speaker's last sound does not reach the microphone. */
    private fun listenSoon(idx: Int) {
        viewModelScope.launch {
            delay(250)
            val d = _dictate.value
            if (d.on && d.shadow && !d.revealed && !d.listening && d.index == idx) shadowListen()
        }
    }

    /** Opens the microphone for one phrase. The screen asks for the permission first when the mic is tapped. */
    fun shadowListen() {
        val d = _dictate.value
        if (!d.on || !d.shadow || d.revealed || d.listening) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _dictate.update { it.copy(note = "Tap the mic to allow it.") }; return
        }
        val s = speech ?: SpeechListener(ctx).also { speech = it }
        if (!s.available()) { _dictate.update { it.copy(note = "This phone has no speech recognition.") }; return }
        player.pause()
        val idx = d.index
        _dictate.update { it.copy(listening = true, note = null) }
        s.start(lang) { out ->
            val cur = _dictate.value
            if (!cur.on || cur.index != idx || cur.revealed) return@start
            when (out) {
                is SpeechListener.Out.Text -> { _dictate.update { it.copy(listening = false) }; dictReveal(out.text) }
                is SpeechListener.Out.Fail -> _dictate.update { it.copy(listening = false, note = speechNote(out.code)) }
            }
        }
    }
    /** Tap the mic while it is open: stop listening now (what was heard so far is still graded). */
    fun shadowStop() { speech?.stop() }
    private fun stopListening() { speech?.cancel(); _dictate.update { if (it.listening) it.copy(listening = false) else it } }
    private fun speechNote(code: Int) = when (code) {
        6, 7 -> "Did not hear you. Tap the mic to try again."
        9 -> "Tap the mic to allow it."
        8 -> "Speech recognition is busy. Tap the mic to try again."
        12, 13 -> "This phone's speech recognition does not have this language yet."
        1, 2, 4 -> "Speech recognition needs a connection, or an offline language pack."
        else -> "Speech recognition did not work (code $code). Tap the mic to try again."
    }

    // ---- Watch history (links and details only, never the video) ----
    /** Latest first, capped at HISTORY_CAP rows, of the language being learned only: switching language switches the list. */
    val history: StateFlow<List<WatchHistoryEntity>> = settingsRepo.settings.map { it.targetLang }.distinctUntilChanged()
        .flatMapLatest { historyDao.observe(it, HISTORY_CAP) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    init { viewModelScope.launch { historyDao.claimUnassigned(settingsRepo.settings.first().targetLang) } }
    private var resumed = false
    private var onWatch = false   // the Watch tab is showing
    private var openJob: Job? = null
    private var activeLang: String? = null
    // Switching the learning language: save the video's place under the old language and close it; Watch then shows the list.
    init {
        viewModelScope.launch {
            settingsRepo.settings.map { it.targetLang }.distinctUntilChanged().collect { l ->
                val prev = activeLang; activeLang = l
                if (prev == null || prev == l) return@collect
                openJob?.cancel(); openJob = null
                saveProgress(); exitDictation(); backToLink()
                lang = l
                _mini.value = MiniMode.NONE
                // Watch shows the Recent / Liked list for the new language; the last video is not opened by itself
            }
        }
    }

    // ---- Search bar: a pasted link opens the video, anything else is searched on YouTube ----
    private val _search = MutableStateFlow(SearchUi())
    val search: StateFlow<SearchUi> = _search.asStateFlow()
    private var searchJob: Job? = null
    private val linkPattern = Regex("(youtube\\.com/|youtu\\.be/)", RegexOption.IGNORE_CASE)
    fun looksLikeLink(t: String) = linkPattern.containsMatchIn(t)

    /** Returns true when the text was a link (and is now being opened), false when it started a search. */
    fun submit(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (looksLikeLink(t)) { clearSearch(); open(if (t.startsWith("http", true)) t else "https://$t"); return true }
        searchJob?.cancel()
        _search.value = SearchUi(loading = true)
        searchJob = viewModelScope.launch {
            withContext(app.fluvour.media.YouTubeGuard.UserRequest()) { source.search(t) }.fold(
                onSuccess = { _search.value = SearchUi(hits = it) },
                onFailure = { _search.value = SearchUi(error = "Search did not work: ${it.message ?: it.javaClass.simpleName}") }
            )
        }
        return false
    }
    fun clearSearch() { searchJob?.cancel(); _search.value = SearchUi() }

    private fun videoId(url: String) = UUID.nameUUIDFromBytes("video:${url.trim()}".toByteArray()).toString()

    /** Writes the current place to the history row. Cheap. Does nothing while no video is loaded. */
    private fun saveProgress() {
        val item = _ui.value.item ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val id = videoId(item.sourceUrl); val pos = player.currentPosition; val dur = player.duration.takeIf { it > 0 } ?: 0L
        viewModelScope.launch { historyDao.progress(id, pos, dur, System.currentTimeMillis()) }
    }

    fun removeHistory(id: String) { viewModelScope.launch { historyDao.delete(id) } }

    // ---- Liked videos: the heart in the player. Kept apart from history, so clearing Recent never removes a liked video. ----
    /** Newest liked first, of the language being learned only. */
    val liked: StateFlow<List<LikedVideoEntity>> = settingsRepo.settings.map { it.targetLang }.distinctUntilChanged()
        .flatMapLatest { likedDao.observe(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** True while the loaded video is in the Liked list. */
    val currentLiked: StateFlow<Boolean> = combine(_ui, liked) { u, l -> u.item?.let { i -> l.any { it.id == videoId(i.sourceUrl) } } ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun toggleLike() {
        val item = _ui.value.item ?: return
        val id = videoId(item.sourceUrl)
        val dur = player.duration.takeIf { it > 0 } ?: item.durationMs
        viewModelScope.launch {
            if (liked.value.any { it.id == id }) likedDao.delete(id)
            else likedDao.upsert(LikedVideoEntity(id, item.sourceUrl, item.title, item.uploader, item.thumbUrl ?: historyDao.get(id)?.thumbUrl, dur, System.currentTimeMillis(), lang))
        }
    }
    fun unlike(id: String) { viewModelScope.launch { likedDao.delete(id) } }

    /** First visit to the Watch tab after the app starts: load the last video at its saved place, paused and ready to play. */
    fun resumeLast() {
        val u = _ui.value
        if (resumed || u.item != null || u.loading) return
        resumed = true
        viewModelScope.launch {
            val l = settingsRepo.settings.first().targetLang
            historyDao.claimUnassigned(l)   // rows from before per-language history
            val last = historyDao.latest(l) ?: return@launch
            open(last.url, last.positionMs, autoplay = false)
        }
    }

    /** `startMs` null means "where you stopped last time" (from the history), or the beginning for a new video. */
    fun open(link: String, startMs: Long? = null, autoplay: Boolean = true) {
        if (link.isBlank()) { _ui.value = PlayerUi(error = "Paste a YouTube link first."); return }
        val url = link.trim()
        val now = _ui.value
        if (now.loading) return
        if (now.item?.sourceUrl == url) { if (autoplay) player.play(); return }   // already loaded: carry on where it is
        saveProgress(); player.pause()
        _ui.value = PlayerUi(loading = true); clearLoop(); waveJob?.cancel(); _wave.value = null; dictStop?.cancel(); dictStop = null; stopListening(); _dictate.value = DictateUi()
        openJob = viewModelScope.launch {
            val st = settingsRepo.settings.first()
            lang = st.targetLang
            withContext(app.fluvour.media.YouTubeGuard.UserRequest()) { source.load(url, lang, st.uiLang) }.fold(
                onSuccess = { item ->
                    val id = videoId(item.sourceUrl)
                    val saved = historyDao.get(id)
                    val dur = item.durationMs.takeIf { it > 0 } ?: saved?.durationMs ?: 0L
                    // A video you finished starts again from the top.
                    val start = (startMs ?: saved?.positionMs ?: 0L).let { p -> if (dur > 0 && p > dur - 5000) 0L else p }.coerceAtLeast(0L)
                    player.setMediaItem(mi(if (_audioOnly.value && item.audioUrl != null) item.audioUrl else item.streamUrl, item.title), start)
                    player.prepare(); player.playWhenReady = autoplay
                    (item.audioUrl ?: item.streamUrl).let { u ->   // the waveform loads in the background; falls back to the video stream's own sound when YouTube gives no separate audio
                        waveJob = viewModelScope.launch {
                            val w = withContext(Dispatchers.IO) { WaveformExtractor.extract(u, cancelled = { !isActive }) }
                            if (_ui.value.item === item) _wave.value = w
                        }
                    }
                    val sid = saveSource(item)
                    historyDao.upsert(WatchHistoryEntity(id, item.sourceUrl, item.title, item.uploader, item.thumbUrl ?: saved?.thumbUrl, dur, start, System.currentTimeMillis(), lang))
                    historyDao.trim(lang, HISTORY_CAP)
                    _ui.value = PlayerUi(item = item, sourceId = sid)
                    // Split the lines into words off the main thread (Kuromoji / jieba can take a moment the first time). The lines around where
                    // playback starts go first and the list is published after every chunk, so furigana, colours and the word fill are ready within
                    // moments; a long video used to wait for every line before showing any.
                    val lines = item.lines
                    val first = lines.indexOfLast { it.startMs <= start }.coerceAtLeast(0)
                    val order = (first until lines.size).toList() + (0 until first).toList()
                    val done = arrayOfNulls<List<Token>>(lines.size)
                    var at = 0
                    var chunk = 25
                    while (at < order.size) {
                        if (_ui.value.item !== item) break   // another video was opened meanwhile
                        val ids = order.subList(at, minOf(at + chunk, order.size))
                        val part = withContext(Dispatchers.Default) { ids.map { it to WordSplitter.split(lines[it].text, lang) } }
                        for ((i, t) in part) done[i] = t
                        val snapshot = done.map { it ?: emptyList() }
                        _ui.update { if (it.item === item) it.copy(tokens = snapshot) else it }
                        at += ids.size
                        chunk = 150
                    }
                },
                onFailure = {
                    _ui.value = PlayerUi(error = if (app.fluvour.media.YouTubeGuard.isBlock(it) && it !is app.fluvour.media.YouTubeCooldownException) "YouTube is asking for a bot check on this connection. Fluvour will pause YouTube requests for a while. Try switching between Wi-Fi and mobile data." else "Could not open this video: ${it.message ?: it.javaClass.simpleName}")
                    // A clip from the list that is gone (not a network error) stops being recommended.
                    if (app.fluvour.reading.ClipPlan.isGone(it)) viewModelScope.launch { runCatching { analyzed.markUnavailable(url) } }
                }
            )
        }
    }

    /** One `sources` row per video link (the same link reuses its row), so mined sentences can point back to the video. */
    private suspend fun saveSource(item: Playable): String {
        val id = UUID.nameUUIDFromBytes("video:${item.sourceUrl}".toByteArray()).toString()
        if (sources.get(id) == null) {
            val now = System.currentTimeMillis()
            val meta = JSONObject().apply { item.uploader?.let { put("uploader", it) } }.toString()
            sources.upsert(SourceEntity(id, settingsRepo.ensureUserId(), "video", item.title, item.sourceUrl, meta, now, now))
        }
        return id
    }

    // ---- Lookup sheet (same content as the reader's) ----

    private val _lookup = MutableStateFlow<VideoLookup?>(null)
    val lookup: StateFlow<VideoLookup?> = _lookup.asStateFlow()
    private val _meanings = MutableStateFlow(Meanings())
    val meanings: StateFlow<Meanings> = _meanings.asStateFlow()
    private val _level = MutableStateFlow<LevelInfo?>(null)
    val level: StateFlow<LevelInfo?> = _level.asStateFlow()
    private val _imported = MutableStateFlow<ImportedLookup?>(null)
    val imported: StateFlow<ImportedLookup?> = _imported.asStateFlow()
    private val _voiceHint = MutableStateFlow(false)
    val voiceHint: StateFlow<Boolean> = _voiceHint.asStateFlow()
    private var job: Job? = null

    /** Texts already mined from this video, so the screen can show "saved" for a line or for the sentence in the sheet. */
    val mined: StateFlow<Set<String>> =
        _ui.map { it.sourceId }.distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(emptyList<String>()) else sentenceRepo.observeTextsForSource(id) }
            .map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Opens the sheet for a tapped word in `line` and loads the dictionary entry and the AI explanation side by side. */
    fun select(line: SubLine, word: String, lemma: String?, reading: String?) {
        job?.cancel()
        val sentence = subLineText(line, lang)
        _lookup.value = VideoLookup(word, sentence, line.startMs, line.endMs, lemma, reading, line.words)
        _meanings.value = Meanings(); _level.value = null; _imported.value = null
        job = viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val userId = settingsRepo.ensureUserId()
            launch { _level.value = levelRepo.lookup(lang, lemma, word) }
            launch { _imported.value = dictRepo.lookup(lang, lemma, word) }
            launch { val p = lookups.dictionary(userId, lang, lemma ?: word); _meanings.update { it.copy(dictionary = p) } }
            launch { val p = lookups.explain(userId, lang, s.uiLang, word, sentence); _meanings.update { it.copy(explanation = p) } }
        }
    }
    fun clear() { job?.cancel(); _lookup.value = null }

    // ---- Mining ----

    private suspend fun mine(text: String, startMs: Long, endMs: Long, words: List<app.fluvour.media.SubWord> = emptyList()): String? {
        val sid = _ui.value.sourceId ?: return null
        if (text.isBlank()) return null
        return sentenceRepo.mine(settingsRepo.ensureUserId(), lang, text, sid, SentenceRepository.videoPosition(startMs, endMs, words))
    }

    /** "Mine this line": saves the subtitle line with this video as its source and the line's time. */
    fun mineLine(line: SubLine) { viewModelScope.launch { mine(subLineText(line, lang), line.startMs, line.endMs, line.words) } }

    /** The sheet's "Save sentence": the same, for the line the tapped word is in. */
    fun saveSentence() { val l = _lookup.value ?: return; viewModelScope.launch { mine(l.sentence, l.startMs, l.endMs, l.words) } }

    /** Save the sentence, then hand its id to the card editor so the card links to it. */
    fun mineForCard(onReady: (sentenceId: String?) -> Unit) {
        val l = _lookup.value ?: return
        viewModelScope.launch { onReady(mine(l.sentence, l.startMs, l.endMs)) }
    }

    fun speak(text: String) { _voiceHint.value = tts.speak(text, lang) == TtsManager.Result.NO_VOICE }
    fun backToLink() { clear(); clearLoop(); waveJob?.cancel(); _wave.value = null; player.stop(); player.clearMediaItems(); _ui.value = PlayerUi() }
    override fun onCleared() { speech?.cancel(); app.fluvour.media.PlaybackHolder.player = null; tap.release(); player.release(); tts.stop() }
}
