package app.fluvour.ui

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.audio.TtsManager
import app.fluvour.data.CardEntity
import app.fluvour.data.CardRepository
import app.fluvour.data.SettingsRepository
import app.fluvour.media.YouTubeSource
import app.fluvour.media.ClipCache
import app.fluvour.media.YouTubeGuard
import app.fluvour.reading.ClipRef
import app.fluvour.reading.HeisigEntry
import app.fluvour.reading.HeisigRepository
import app.fluvour.data.CardRepository.DueItem
import app.fluvour.srs.FacetKind
import app.fluvour.srs.FacetQueue
import app.fluvour.srs.Phase
import app.fluvour.srs.QueueItem
import app.fluvour.srs.facetId
import app.fluvour.srs.Rating
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the front of a card shows: the word, the meaning, the Sarawak form (Malay only), or (for cards mined from a video) the clip. A card with no clip shows the word in clip mode. */
enum class Front { WORD, MEANING, CLIP, SARAWAK }

/** What the Cards tab studies: every open facet interleaved (MIXED), or one kind. The order is the mode row's. */
enum class StudyMode(val kind: FacetKind?) { MIXED(null), CLIP(FacetKind.CLIP), MEANING(FacetKind.MEANING), WORD(FacetKind.WORD), SARAWAK(FacetKind.SARAWAK) }

/** A facet waiting in the queue: its card (carrying that facet's schedule) and which kind it is. */
private class Item(val card: CardEntity, val kind: FacetKind)

/** The clip of the card on screen: IDLE = nothing to report, LOADING = its video is being looked up, FAILED = it could not be loaded. */
enum class ClipStatus { IDLE, LOADING, FAILED }

/** Clip cards play the mined line from a little before it starts to a little after it ends, never longer than ClipRef.MAX_MS. */
internal const val CLIP_LEAD_MS = 400L
internal const val CLIP_TAIL_MS = 350L

data class ReviewUi(
    val loading: Boolean = true,
    val current: CardEntity? = null,
    val showAnswer: Boolean = false,
    val previews: Map<Rating, Long> = emptyMap(),   // delay until due for each button
    val newLeft: Int = 0, val learningLeft: Int = 0, val reviewLeft: Int = 0,
    val answered: Int = 0, val remembered: Int = 0,
    val finished: Boolean = false,
    val canUndo: Boolean = false,   // the last rating can be taken back
    val clip: ClipRef? = null,       // the video moment this card was mined from, when there is one
    val nextClips: List<ClipRef> = emptyList(),   // clips of the next 2 distinct videos in the queue that are not looked up yet, only in clip mode: looked up ahead of time
    val vanishing: String? = null,   // id of the card on screen that was just deleted: it turns to dust, then the next card comes
    val heisig: List<HeisigEntry> = emptyList()   // Heisig keyword and story for each kanji of the current Japanese card (back of the card)
) {
    val progress: Float get() { val total = answered + newLeft + learningLeft + reviewLeft; return if (total == 0) 0f else answered.toFloat() / total }
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val cardRepo: CardRepository,
    private val tts: TtsManager, private val heisig: HeisigRepository, private val source: YouTubeSource,
    @ApplicationContext private val ctx: Context
) : ViewModel() {
    private val prefs = ctx.getSharedPreferences("review", Context.MODE_PRIVATE)
    private val _front = MutableStateFlow(Front.WORD)
    /** Which side the card on screen shows: the front of its facet. It follows the card; it is not chosen here. */
    val front: StateFlow<Front> = _front.asStateFlow()

    private val _mode = MutableStateFlow(StudyMode.MIXED)
    /** The mode row's choice. In memory only: Mixed on every fresh start, kept while the app stays open, Mixed again when the study language changes. */
    val mode: StateFlow<StudyMode> = _mode.asStateFlow()
    fun selectMode(m: StudyMode) { if (m == _mode.value) return; _mode.value = m; reload() }

    /** Due count of each facet kind (the badges on the mode row; Mixed shows their total). Refreshed every minute so learning steps that come due are counted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dueByKind: StateFlow<Map<FacetKind, Int>> = settingsRepo.settings.map { it.targetLang }.distinctUntilChanged()
        .flatMapLatest { l -> flow { while (true) { emit(Unit); delay(60_000) } }.flatMapLatest { cardRepo.observeDueByKind(l) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Drops the queue and builds a new one (mode or language changed). */
    private fun reload() {
        loadJob?.cancel(); clipJob?.cancel(); stopClip()
        queue.clear(); answeredCards.clear(); answered = 0; remembered = 0; busy = false; last = null
        _ui.value = ReviewUi()
        loadJob = viewModelScope.launch { load() }
    }

    /**
     * Puts due facets in study order. Due-time order across kinds, new ones last. In Mixed also sibling burying: one facet per card,
     * and cards already answered this session wait for a later session. A single mode only orders.
     */
    private fun arrange(items: List<DueItem>): List<Item> {
        val queued = items.map { QueueItem(facetId(it.card.id, it.kind), it.card.id, it.kind, Phase.valueOf(it.card.phase), it.card.dueAt) }
        val ordered = FacetQueue.mixedOrder(queued)
        val picked = if (_mode.value == StudyMode.MIXED) FacetQueue.buried(ordered, answeredCards) else ordered
        val byKey = items.associateBy { it.card.id to it.kind }
        return picked.mapNotNull { q -> byKey[q.cardId to q.kind]?.let { Item(it.card, it.kind) } }
    }
    /** True when the front shows the word itself (so saying it aloud is fine); false when the word is the answer. */
    private fun wordFirst() = _front.value == Front.WORD || (_front.value == Front.CLIP && (_ui.value.clip == null || _ytBlocked.value))

    private val queue = ArrayDeque<Item>()
    private val answeredCards = HashSet<String>()   // cards answered this session, for Mixed's sibling burying
    private var answered = 0
    private var remembered = 0
    private var busy = false
    private class LastRating(val item: Item, val rating: Rating)
    private var last: LastRating? = null
    private var autoPlay = false

    private val _voiceMissing = MutableStateFlow(false)
    /** True when the phone has no voice installed for the language of the last spoken text. */
    val voiceMissing: StateFlow<Boolean> = _voiceMissing.asStateFlow()

    private val _ui = MutableStateFlow(ReviewUi())
    val ui: StateFlow<ReviewUi> = _ui.asStateFlow()

    private var loadJob: Job? = null
    init {
        loadJob = viewModelScope.launch { load() }
        tts.kickAhead()   // voices for the cards are made in the background
        // Language switched: drop the old language's queue and build one for the new language.
        viewModelScope.launch {
            var shown: String? = null
            settingsRepo.settings.map { it.targetLang }.distinctUntilChanged().collect { l ->
                val prev = shown; shown = l
                if (prev == null || prev == l) return@collect
                _mode.value = StudyMode.MIXED
                reload()
            }
        }
        viewModelScope.launch { CardEvents.deleted.collect { onCardDeleted(it) } }
    }

    /** A card was deleted in the editor or in Browse: if it is on screen it vanishes and the next one comes; if it is further down the queue it just leaves it. */
    private fun onCardDeleted(id: String) {
        if (last?.item?.card?.id == id) last = null
        val head = queue.firstOrNull()
        if (head?.card?.id == id) { if (_ui.value.vanishing != id) _ui.value = _ui.value.copy(vanishing = id, canUndo = last != null) }
        else if (queue.removeAll { it.card.id == id }) viewModelScope.launch { publish(showAnswer = _ui.value.showAnswer) }
    }

    /** The screen calls this when the vanishing animation is over. */
    fun finishVanish() {
        val id = _ui.value.vanishing ?: return
        queue.removeAll { it.card.id == id }
        viewModelScope.launch { publish(showAnswer = false); autoSpeakHead() }
    }

    /** Pulls in cards that became due after the queue was built (new cards, learning steps that came due). Only appends; the card on screen is never swapped. */
    fun refresh() {
        viewModelScope.launch {
            if (busy || loadJob?.isActive == true) return@launch
            val lang = settingsRepo.settings.first().targetLang
            val have = queue.mapTo(HashSet()) { it.card.id }
            val fresh = arrange(cardRepo.dueItems(lang, _mode.value.kind, 200)).filter { it.card.id !in have }
            if (fresh.isEmpty()) return@launch
            val newRoom = (NEW_PER_SESSION - queue.count { it.card.phase == Phase.NEW.name }).coerceAtLeast(0)
            val wasEmpty = queue.isEmpty()
            queue.addAll(fresh.filter { it.card.phase != Phase.NEW.name })
            queue.addAll(fresh.filter { it.card.phase == Phase.NEW.name }.take(newRoom))
            publish(showAnswer = if (wasEmpty) false else _ui.value.showAnswer)
            if (wasEmpty) autoSpeakHead()
        }
    }

    private suspend fun load() {
        // once: cards saved with the AI's whole explanation as their meaning get a short meaning (the original goes to the notes)
        if (!prefs.getBoolean("tidied_meanings_2", false)) { runCatching { cardRepo.tidyMeanings() }.onSuccess { prefs.edit().putBoolean("tidied_meanings_2", true).apply() } }
        val settings = settingsRepo.settings.first()
        autoPlay = settings.autoPlay
        val lang = settings.targetLang
        val due = arrange(cardRepo.dueItems(lang, _mode.value.kind, 200))
        // Cards already in progress first, then a capped number of new ones.
        queue.addAll(due.filter { it.card.phase != Phase.NEW.name })
        queue.addAll(due.filter { it.card.phase == Phase.NEW.name }.take(NEW_PER_SESSION))
        publish(showAnswer = false)
        autoSpeakHead()
    }

    // Not when the meaning is on the front: saying the word would give the answer away (it is spoken when the card is flipped instead).
    private fun autoSpeakHead() { if (autoPlay && wordFirst()) queue.firstOrNull()?.let { speak(spokenWord(it.card, it.kind), it.card.languageCode) } }

    /** The word a facet's front shows: the Sarawak form on a Sarawak facet, otherwise the card's word. */
    private fun spokenWord(card: CardEntity, kind: FacetKind) = if (kind == FacetKind.SARAWAK) card.variantWord?.takeIf { it.isNotBlank() } ?: card.word else card.word

    fun speak(text: String, langTag: String) {
        _voiceMissing.value = tts.speak(text, langTag) == TtsManager.Result.NO_VOICE
    }

    // ---- Clip cards: this screen's own small player. It is separate from the Watch player, which is never touched. ----
    private var clipExo: ExoPlayer? = null
    /** True while YouTube is blocking this connection: clip cards show their word instead (no spinner, no failed clip) and no lookups are tried. */
    private val _ytBlocked = MutableStateFlow(false)
    val ytBlocked: StateFlow<Boolean> = _ytBlocked.asStateFlow()
    private var cooldownJob: Job? = null

    /** Word mode on; it switches itself off the moment the cooldown ends (not only when the next card appears), which brings the clip back. */
    private fun setBlocked() {
        _ytBlocked.value = true
        if (cooldownJob?.isActive == true) return
        cooldownJob = viewModelScope.launch {
            while (YouTubeGuard.coolingDown()) delay(10_000)
            _ytBlocked.value = false
        }
    }
    private val _clipStatus = MutableStateFlow(ClipStatus.IDLE)
    val clipStatus: StateFlow<ClipStatus> = _clipStatus.asStateFlow()
    /** True once the clip has played to its end (the card shows a replay icon). */
    private val _clipEnded = MutableStateFlow(false)
    val clipEnded: StateFlow<Boolean> = _clipEnded.asStateFlow()
    /** True while the clip is actually playing (the play / replay icons are hidden then). */
    private val _clipPlaying = MutableStateFlow(false)
    val clipPlaying: StateFlow<Boolean> = _clipPlaying.asStateFlow()
    /** True once the first picture of the loaded video has been drawn; until then the card shows the cover and a spinner. */
    /** True while the viewer has paused the clip by tapping it (the card shows a play arrow). */
    private val _clipPaused = MutableStateFlow(false)
    val clipPaused: StateFlow<Boolean> = _clipPaused.asStateFlow()
    private val _clipFrame = MutableStateFlow(false)
    val clipFrame: StateFlow<Boolean> = _clipFrame.asStateFlow()
    private val _clipAspect = MutableStateFlow(16f / 9f)
    val clipAspect: StateFlow<Float> = _clipAspect.asStateFlow()
    /** The clip the player holds now (so the back of that card can show its last frame as a thumbnail). */
    private val _clipNow = MutableStateFlow<ClipRef?>(null)
    val clipNow: StateFlow<ClipRef?> = _clipNow.asStateFlow()
    private var clipUrl: String? = null
    private var clipRef: ClipRef? = null     // the clip the player was last asked for (to retry it once if its stream fails)
    private var clipRetried = false
    private val prefetching = HashSet<String>()   // videos being looked up ahead right now (main thread only)
    private var clipJob: Job? = null
    private var clipStop: PlayerMessage? = null
    // Videos looked up ahead of time live in YouTubeGuard's cache (2 hours, up to 60 entries): a lookup of a video seen before costs no request.

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun clipPlayer(): ExoPlayer = clipExo ?: ExoPlayer.Builder(ctx)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setMediaSourceFactory(ClipCache.mediaSourceFactory(ctx))   // clips are saved as they play, and prepared clips play from disk
        .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) _clipAspect.value = (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.5f, 2.4f)
                }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) _clipEnded.value = true }
                override fun onRenderedFirstFrame() { _clipFrame.value = true }
                override fun onIsPlayingChanged(isPlaying: Boolean) { _clipPlaying.value = isPlaying }
                // an expired or broken stream: forget the saved lookup and try once with a fresh one before showing FAILED
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.d("FluvourClip", "player error: ${error.errorCodeName} ${error.message} (retried=$clipRetried)")
                    val ref = clipRef
                    clipUrl = null
                    if (ref != null && !clipRetried && !YouTubeGuard.coolingDown()) {
                        clipRetried = true
                        _clipNow.value = null; _clipStatus.value = ClipStatus.LOADING
                        viewModelScope.launch {
                            val st = settingsRepo.settings.first()
                            source.forgetLookup(ref.url, st.targetLang, st.uiLang)
                            ClipCache.forgetReady(ctx, ref)   // a prepared clip that failed to play: use a fresh lookup instead
                            startClip(ref)
                        }
                    } else if (YouTubeGuard.coolingDown()) { setBlocked(); _clipNow.value = null; _clipStatus.value = ClipStatus.IDLE }
                    else { _clipNow.value = null; _clipStatus.value = ClipStatus.FAILED }
                }
            })
            clipExo = p
        }

    /** Plays a card's clip (it starts by itself when the card appears; tapping the video or the replay icon calls this again). */
    fun playClip(ref: ClipRef) { clipRetried = false; startClip(ref) }

    private fun startClip(ref: ClipRef) {
        clipRef = ref
        val p = clipPlayer()
        clipJob?.cancel(); clipStop?.cancel(); clipStop = null
        p.pause()
        _clipEnded.value = false; _clipPaused.value = false
        clipJob = viewModelScope.launch {
            val from = (ref.startMs - CLIP_LEAD_MS).coerceAtLeast(0L)
            if (clipUrl != ref.url) {
                _clipStatus.value = ClipStatus.LOADING
                val st = settingsRepo.settings.first()
                // a clip prepared ahead plays from disk with no lookup at all
                val readyItag = ClipCache.readyItag(ctx, ref)
                val item = if (readyItag != null) null else kotlinx.coroutines.withContext(YouTubeGuard.UserRequest()) { source.load(ref.url, st.targetLang, st.uiLang) }.getOrNull()
                if (readyItag != null) {
                    _clipFrame.value = false
                    p.setMediaItem(ClipCache.readyItem(ref, readyItag), from); p.prepare()
                    clipUrl = ref.url
                } else {
                    if (item == null) {
                        android.util.Log.d("FluvourClip", "lookup failed, cooldown=${YouTubeGuard.cooldownMinutes()} min")
                        // YouTube is blocking: the card turns into a word card instead of a failed clip
                        if (YouTubeGuard.coolingDown()) { setBlocked(); _clipStatus.value = ClipStatus.IDLE } else _clipStatus.value = ClipStatus.FAILED
                        return@launch
                    }
                    _clipFrame.value = false
                    p.setMediaItem(ClipCache.item(item.streamUrl, ref.url), from); p.prepare()
                    clipUrl = ref.url
                }
            } else p.seekTo(from)
            _clipStatus.value = ClipStatus.IDLE
            _clipNow.value = ref
            val last = minOf(ref.endMs, ref.startMs + ClipRef.MAX_MS) + CLIP_TAIL_MS
            clipStop = p.createMessage { _, _ -> p.pause(); _clipEnded.value = true }
                .setLooper(p.applicationLooper).setPosition(last).setDeleteAfterDelivery(true).also { it.send() }
            p.play()
        }
    }

    /**
     * A tap on the clip: playing pauses it, paused resumes where it stopped, finished or failed plays it again from the start.
     * While its video is being looked up a tap does nothing. A clip the player does not hold yet is started.
     */
    fun toggleClip(ref: ClipRef) {
        val p = clipPlayer()
        if (_clipStatus.value == ClipStatus.LOADING) return
        if (_clipNow.value != ref || _clipStatus.value == ClipStatus.FAILED || _clipEnded.value) { playClip(ref); return }
        if (p.isPlaying) { p.pause(); _clipPaused.value = true }
        else { _clipPaused.value = false; p.play() }
    }

    /** Looks up the next clip card's video while the current card is shown, so it starts without waiting. */
    fun prefetch(url: String) {
        if (url == clipUrl || !prefetching.add(url)) return   // already on screen, or already being looked up
        viewModelScope.launch {
            try {
                val st = settingsRepo.settings.first()
                source.load(url, st.targetLang, st.uiLang)   // result is kept by the guard's cache; a cached video sends no request
            } finally { prefetching.remove(url) }
        }
    }

    /** Leaving the Review screen: stop whatever is playing. */
    fun stopClip() { clipJob?.cancel(); clipStop?.cancel(); clipStop = null; clipExo?.pause(); _clipPaused.value = false }

    override fun onCleared() { tts.stop(); clipExo?.release(); clipExo = null }

    /** Japanese cards: the Heisig keyword and story of each kanji in the word, when the Heisig deck is imported. */
    private suspend fun heisigFor(card: CardEntity?): List<HeisigEntry> {
        if (card == null || card.languageCode != "ja") return emptyList()
        return card.word.map { it.toString() }.distinct().mapNotNull { heisig.get(it) }.take(4)
    }

    private suspend fun publish(showAnswer: Boolean) {
        val head = queue.firstOrNull()
        val headCard = head?.card
        val kanji = heisigFor(headCard)
        val clip = headCard?.let { cardRepo.clipFor(it) }
        // While YouTube is blocking, a clip that is saved on disk still plays (it needs no YouTube request); only the others fall back to the word.
        val onDisk = clip != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClipCache.readyItag(ctx, clip) != null }
        if (YouTubeGuard.coolingDown() && !onDisk) setBlocked() else _ytBlocked.value = false
        // Look ahead by video, not by card: the next LOOKAHEAD_VIDEOS distinct videos (other than the one on screen) that are not looked up yet.
        val nextClips = if (!_ytBlocked.value) {
            val st = settingsRepo.settings.first()
            val found = LinkedHashMap<String, ClipRef>()
            for (c in queue.drop(1).take(LOOKAHEAD_SCAN)) {
                if (found.size >= LOOKAHEAD_VIDEOS) break
                if (c.kind != FacetKind.CLIP) continue   // only clip facets need their video ahead of time
                val ref = cardRepo.clipFor(c.card) ?: continue
                if (ref.url == clip?.url || ref.url in found || source.isLookupCached(ref.url, st.targetLang, st.uiLang)) continue
                found[ref.url] = ref
            }
            found.values.toList()
        } else emptyList()
        _front.value = head?.let { runCatching { Front.valueOf(it.kind.name) }.getOrDefault(Front.WORD) } ?: Front.WORD   // set together with the card, so the front never lags it
        _ui.value = ReviewUi(
            loading = false, current = headCard, showAnswer = showAnswer,
            previews = headCard?.let { cardRepo.preview(it) } ?: emptyMap(),
            newLeft = queue.count { it.card.phase == Phase.NEW.name },
            learningLeft = queue.count { it.card.phase == Phase.LEARNING.name || it.card.phase == Phase.RELEARNING.name },
            reviewLeft = queue.count { it.card.phase == Phase.REVIEW.name },
            answered = answered, remembered = remembered, finished = head == null, canUndo = last != null, clip = clip, nextClips = nextClips, heisig = kanji
        )
        // the sounds of the next few cards are made ahead, so their speaker buttons play at once (the sentence is only spoken from a word-first front)
        tts.prefetch(queue.take(PREFETCH_CARDS).flatMap { c -> listOfNotNull(spokenWord(c.card, c.kind), c.card.example.takeIf { c.kind == FacetKind.WORD }, c.card.variantExample.takeIf { c.kind == FacetKind.SARAWAK }) }, queue.firstOrNull()?.card?.languageCode)
    }

    fun reveal() {
        val card = _ui.value.current ?: return
        _ui.value = _ui.value.copy(showAnswer = true)
        if (autoPlay && !wordFirst()) speak(card.word, card.languageCode)
    }

    fun rate(rating: Rating) {
        val item = queue.firstOrNull() ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val updated = cardRepo.review(item.card, item.kind, rating)
                last = LastRating(item, rating)
                answered++
                if (rating == Rating.GOOD || rating == Rating.EASY) remembered++
                queue.removeFirst()
                answeredCards.add(item.card.id)
                // Cards still in learning steps come back in this session if they are due soon.
                val inSteps = updated.phase == Phase.LEARNING.name || updated.phase == Phase.RELEARNING.name
                if (inSteps && (updated.dueAt ?: 0L) <= System.currentTimeMillis() + LEARN_AHEAD_MS) queue.addLast(Item(updated, item.kind))
                publish(showAnswer = false)
                autoSpeakHead()
            } finally { busy = false }
        }
    }

    /** Takes back the last rating: the card comes back with its answer showing and its old schedule, so it can be rated again. */
    fun undo() {
        val u = last ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                cardRepo.undoReview(u.item.card, u.item.kind)
                // a card in learning steps may have been queued again: take that copy out and put the original first
                queue.removeAll { it.card.id == u.item.card.id && it.kind == u.item.kind }
                queue.addFirst(u.item)
                answeredCards.remove(u.item.card.id)
                answered = (answered - 1).coerceAtLeast(0)
                if (u.rating == Rating.GOOD || u.rating == Rating.EASY) remembered = (remembered - 1).coerceAtLeast(0)
                last = null
                publish(showAnswer = true)
            } finally { busy = false }
        }
    }

    private companion object {
        const val NEW_PER_SESSION = 20
        const val LEARN_AHEAD_MS = 20 * 60_000L
        /** How many cards ahead their sounds are made. A few, not the whole day: the free Gemini voice limits requests per minute and per day. */
        const val PREFETCH_CARDS = 5
        const val LOOKAHEAD_VIDEOS = 2   // videos looked up ahead of the card on screen
        const val LOOKAHEAD_SCAN = 20    // cards searched for them
    }
}
