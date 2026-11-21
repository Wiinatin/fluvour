package app.fluvour.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.fluvour.media.Playable
import app.fluvour.media.SubLine
import app.fluvour.reading.Token
import app.fluvour.reading.WordSplitter
import app.fluvour.reading.WordTiming
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun stamp(ms: Long) = "${ms / 60000}:${((ms / 1000) % 60).toString().padStart(2, '0')}"
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)

/**
 * The real video. Video, waveform and controls stay fixed at the top; only the subtitle list below scrolls.
 * Emits into the Watch column (which does not scroll while a video is open).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.WatchVideo(vm: PlayerViewModel, ui: PlayerUi, item: Playable, onAddCard: (String, String, String, String?) -> Unit) {
    val c = Fluvour.colors
    // start from the real player state, so a rebuilt screen (tab switch) shows the right line on the first frame
    var pos by remember { mutableLongStateOf(vm.player.currentPosition) }
    var playing by remember { mutableStateOf(vm.player.isPlaying) }
    var dur by remember { mutableLongStateOf(vm.player.duration.takeIf { it > 0 } ?: 0L) }
    val mined by vm.mined.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val loop by vm.loop.collectAsStateWithLifecycle()
    val repeats by vm.repeats.collectAsStateWithLifecycle()
    val gapMode by vm.gapMode.collectAsStateWithLifecycle()
    val pass by vm.pass.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    val dict by vm.dictate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { pos = vm.player.currentPosition; playing = vm.player.isPlaying; dur = vm.player.duration.takeIf { it > 0 } ?: 0L; delay(200) } }
    // No pause here: WatchScreen pauses when you leave the tab, so opening the search page keeps the video playing.

    // word colours, furigana, translation lines: shared with the landscape screen (WatchLandscape.kt)
    val cd = rememberCaptionData(vm, item)
    val wc = cd.wc; val mode = cd.mode; val ja = cd.ja; val furigana = cd.furigana; val showTrans = cd.showTrans; val trans = cd.trans; val colorer = cd.colorer

    val cur = item.lines.indexOfLast { it.startMs <= pos }
    val curLine = item.lines.getOrNull(cur)
    val dictLine = if (dict.on) item.lines.getOrNull(dict.index) else null
    val actLine = dictLine ?: curLine   // "Mine" works on the dictation line while practising
    val lineSaved = actLine != null && subLineText(actLine, vm.lang) in mined

    // video frame: collapses completely in audio-only mode so the subtitle list gets the room
    val frame = Modifier.padding(horizontal = 22.dp, vertical = 8.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.card)
    AnimatedVisibility(!audioOnly && !(dict.on && !dict.revealed), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        // a tap on the picture shows a full-screen icon in the bottom right corner for a few seconds
        var fsIcon by remember { mutableStateOf(false) }
        LaunchedEffect(fsIcon) { if (fsIcon) { delay(3000); fsIcon = false } }
        val fsCtx = LocalContext.current
        VideoGestures(vm, frame, skipEnabled = !dict.on, onTap = { fsIcon = !fsIcon }) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = vm.player; useController = false } }, modifier = Modifier.fillMaxSize())
            Reveal(fsIcon, Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { fsCtx.enterFullScreen() }, Alignment.Center) {
                    Icon(Icons.Outlined.Fullscreen, "Full screen", Modifier.size(22.dp), tint = Color.White)
                }
            }
        }
    }

    // slimmer waveform while the video is on screen, full height when it is not
    SpectrumScrubber(vm, pos, dur, loop, if (audioOnly) 76.dp else 52.dp) { vm.player.seekTo(it) }
    // time, audio-only icon (always in the same centre spot), time
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Sm(stamp(pos), size = 12.sp)
        if (item.audioUrl != null) Box(
            Modifier.fillMaxHeight().widthIn(min = 48.dp).clip(RoundedCornerShape(17.dp)).clickable { vm.toggleAudioOnly() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.Headphones, if (audioOnly) "Audio only is on. Show video" else "Audio only", Modifier.size(19.dp), tint = if (audioOnly) c.accent else c.mute) }
        Sm(stamp(dur), size = 12.sp)
    }

    // one quiet control row: 1x  A-B  (play)  loop line  Mine. Play is the only filled element; the rest are plain text or thin icons.
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextCtl(if (speed == 1f) "1x" else "${speed}x", speed != 1f) { vm.setSpeed(SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // one button, three taps: set A, set B, clear. The label shows where you are (the times show on the waveform).
            val abLabel = when { loop.a == null -> "A\u2013B"; loop.b == null -> "A\u2013"; else -> "A\u2013B" }
            TextCtl(abLabel, loop.a != null, enabled = !dict.on) { if (loop.a == null) vm.markA() else if (loop.b == null) vm.markB() else vm.clearLoop() }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // tap: play or pause (in dictation: hear the line again). Hold: start or leave dictation.
            PlayButton(playing, ring = dict.on,
                onClick = { if (vm.player.isPlaying) vm.player.pause() else if (dict.on) vm.playDictLine() else vm.togglePlay() },   // togglePlay also restarts a video that has ended
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggleDictation(pos) })
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconBtn(Icons.Outlined.Repeat, if (loop.a != null) "Clear loop" else "Loop this line",
                { if (dict.on) Unit else if (loop.a != null) vm.clearLoop() else curLine?.let(vm::loopLine) }, tint = if (dict.on) c.mute.copy(alpha = 0.4f) else if (loop.a != null) c.accent else c.mute)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextCtl(if (lineSaved) "Saved" else "Mine", lineSaved, enabled = actLine != null && !lineSaved && (!dict.on || dict.revealed || dict.shadow), strong = true) { actLine?.let(vm::mineLine) }
        }
    }
    // loop options, only while a loop is running
    if (loop.a != null && loop.b != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextCtl(if (repeats == 0) "Repeat: endless" else "Repeat: ${repeats}x", repeats != 0) { vm.cycleRepeats() }
        TextCtl("Pause: " + listOf("off", "1 s", "2 s", "line length")[gapMode], gapMode != 0) { vm.cycleGap() }
        if (repeats > 0) Sm("Play ${(pass + 1).coerceAtMost(repeats)} of $repeats", size = 12.sp)
    }
    if (!dict.on || dict.revealed) {
    HorizontalDivider(color = c.line)
    // colour switch and legend
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextCtl(mode.label.removePrefix("Colors: ").let { if (mode == ColorMode.OFF) "Colors off" else "Colors: $it" }, mode != ColorMode.OFF, dense = true) { wc.cycle(ja) }
        TextCtl(if (item.translation.isEmpty()) "No translation" else if (showTrans) "Translation on" else "Translation off", showTrans && item.translation.isNotEmpty(), enabled = item.translation.isNotEmpty(), dense = true) { wc.toggleTranslation() }
        if (ja) TextCtl(if (furigana) "Furigana on" else "Furigana off", furigana, dense = true) { wc.toggleFurigana() }
    }
    if (mode != ColorMode.OFF) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Marks.legend(mode, ja).forEach { LegendItem(it) }
        if (mode == ColorMode.JLPT || mode == ColorMode.GRADE) Text("No line: not in the list", color = c.mute, fontSize = 11.sp)
    }
    }

    // subtitle list: the only scrolling part of the page. Follows the video; a manual scroll pauses following for a few seconds.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (cur - 2).coerceAtLeast(0))   // opens near the current line, not at the top
    var firstCentre by remember { mutableStateOf(true) }   // the first centring after the screen is built jumps, later ones animate
    var pausedUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) pausedUntil = System.currentTimeMillis() + 4000L }
    }
    LaunchedEffect(cur, dict.on) {
        if (cur < 0 || dict.on) return@LaunchedEffect
        val wait = pausedUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (System.currentTimeMillis() < pausedUntil) return@LaunchedEffect
        withFrameNanos { }   // let the line grow to its "current" size first, so we measure the real height
        val info = listState.layoutInfo
        val room = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        val size = info.visibleItemsInfo.firstOrNull { it.index == cur }?.size
        // centre the whole line (furigana, subtitle, translation); if it is taller than the list, show its start
        val offset = if (size != null) -((room - size) / 2).coerceAtLeast(0) else -(room * 0.25f).toInt()
        if (firstCentre) { firstCentre = false; listState.scrollToItem(cur, offset) } else listState.animateScrollToItem(cur, offset)
    }
    // the time gutter grows with the video: 10:05 fits in 32 dp, 246:29 needs more (it was cut off before)
    val stampWidth = when (stamp(item.lines.lastOrNull()?.startMs ?: 0L).length) { in 0..4 -> 32.dp; 5 -> 34.dp; else -> 40.dp }
    var answer by rememberSaveable(dict.on, dict.index) { mutableStateOf("") }
    if (dict.on && dictLine != null) DictationPane(vm, ui, dict, dictLine, item.lines.size, answer, { answer = it }, trans.getOrNull(dict.index).orEmpty(), showTrans, colorer, ja && furigana)
    else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 120.dp)) {
        itemsIndexed(item.lines) { n, l ->
            val on = n == cur
            val litInfo = if (on) rememberLitWords(vm, l, ui.tokens.getOrNull(n).orEmpty()) else null   // null for a moment while the current line is split: whole line in accent
            val lit = litInfo?.lit
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // narrow quiet gutter; sits level with the subtitle text, below the furigana when it is on
                Text(stamp(l.startMs), Modifier.widthIn(min = stampWidth).clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = { vm.player.seekTo(l.startMs) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.loopLine(l) }).padding(top = if (ja && furigana) 17.dp else 7.dp), color = if (on) c.accent else c.mute, fontSize = 10.5.sp, maxLines = 1, softWrap = false)
                Column(Modifier.weight(1f)) {
                    LineWords(vm, ui, n, l, if (on && lit == null) c.accent else c.ink, if (on) 24.sp else 19.sp, if (on) 36.sp else 29.sp, colorer, ja && furigana, tokens = litInfo?.tokens, litWords = lit, onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.loopLine(l) }) { w, lemma, reading -> vm.player.pause(); vm.select(l, w, lemma, reading) }
                    // TEST ONLY: says where the fill's timing comes from. Remove once the fill is confirmed.
                    if (on) Text("fill timing: " + (litInfo?.how ?: "waiting for the line's words"), color = c.mute, fontSize = 10.sp)
                    val tr = trans.getOrNull(n).orEmpty()
                    if (showTrans && tr.isNotBlank()) Text(tr, Modifier.padding(top = 2.dp), color = c.mute, fontSize = if (on) 16.sp else 14.sp)
                }
            }
        }
        listOfNotNull(item.uploader, item.subtitleNote).joinToString(" \u00B7 ").takeIf { it.isNotBlank() }
            ?.let { note -> item { Sm(note, Modifier.padding(vertical = 16.dp), size = 12.sp) } }
    }
    VideoLookupSheet(vm, onClose = { vm.clear(); if (!dict.on) vm.player.play() }, onAddCard = onAddCard)
}

/** The current line's words, how many of them have started (`lit`), and where the timing comes from (`how`, shown while testing). */
internal class LitLine(val tokens: List<Token>, val lit: Int, val how: String)

/**
 * Karaoke for the current line: how many of its words have started. The words are timed by real word times when the line has them
 * (YouTube automatic captions), otherwise by an even split of the line's time. If the whole-video word split has not reached this line yet
 * (a long video, or a jump far ahead), the line is split right here, so the fill never waits for it. Null for the moment that takes.
 * Once a word is lit it stays lit until the next line becomes the current one.
 */
@Composable
internal fun rememberLitWords(vm: PlayerViewModel, l: SubLine, ready: List<Token>): LitLine? {
    val produced by produceState(ready, l, ready) {
        value = if (ready.isNotEmpty()) ready
        else withContext(Dispatchers.Default) { runCatching { WordSplitter.split(l.text, vm.lang) }.getOrDefault(emptyList()) }
    }
    // for one frame after the line changes (a skip or a seek) `produced` still holds the previous line's tokens: never use them on this text
    val tokens = produced.takeIf { it.fitsText(l.text) }.orEmpty()
    val pieces = remember(l) { l.words.map { it.startMs to it.text } }
    val starts = remember(l, tokens) { if (tokens.isEmpty()) null else WordTiming.starts(l.text, pieces, tokens, l.startMs, l.endMs) }
    var lit by remember(starts) { mutableStateOf(0) }
    LaunchedEffect(starts) {
        if (starts == null) return@LaunchedEffect
        while (true) {
            val p = vm.player.currentPosition
            lit = starts.count { it - 40 <= p }
            delay(50)
        }
    }
    return if (starts == null) null else LitLine(tokens, lit, WordTiming.source(l.text, pieces))
}

/** True when every token lies inside `text`; tokens of another (older) line can run past its end. */
private fun List<Token>.fitsText(text: String) = all { it.start >= 0 && it.start <= it.end && it.end <= text.length }

private data class W(val text: String, val isWord: Boolean, val lemma: String?, val reading: String?, val hot: Boolean = false)

/**
 * One subtitle line as tappable words. Uses the reader's tokens when ready, the platform word breaker until then.
 * Dictation passes the tokens it graded (`tokens`) and the indices of the missed words (`hot`), which are drawn in the accent colour.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun LineWords(vm: PlayerViewModel, ui: PlayerUi, n: Int, l: SubLine, color: Color, size: TextUnit, lh: TextUnit, colorer: WordColorer?, furigana: Boolean, onLongPress: () -> Unit, center: Boolean = false, tokens: List<Token>? = null, hot: Set<Int> = emptySet(), litWords: Int? = null, onWord: (String, String?, String?) -> Unit) {
    val toks: List<Token> = (tokens?.takeIf { it.isNotEmpty() } ?: ui.tokens.getOrNull(n).orEmpty()).takeIf { it.fitsText(l.text) }.orEmpty()
    val words = if (toks.isEmpty()) splitWords(l.text, vm.lang).map { (p, w) -> W(p, w, null, null) }
    else toks.mapIndexed { i, t -> W(l.text.substring(t.start, t.end).replace("\n", " "), t.isWord, t.lemma, t.reading, hot = i in hot) }
    val missedColor = Fluvour.colors.accent
    val tints = rememberKanjiTints()
    FlowRow(horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start) {
        var wi = 0
        words.forEach { w ->
            // karaoke: the first `litWords` words are filled with the accent colour; punctuation follows the word before it
            val k = if (w.isWord) wi++ else wi - 1
            val filled = litWords != null && k >= 0 && k < litWords
            val tint = if (w.hot || filled) missedColor else color
            val base = if (w.isWord) Modifier.combinedClickable(onClick = { onWord(w.text, w.lemma, w.reading) }, onLongClick = onLongPress) else Modifier
            val mark = if (colorer != null && w.isWord && !w.text.all { it.isDigit() }) colorer.mark(w.text, w.lemma) else null
            // maturity tint: not on missed or karaoke-filled words, and not on the highlighted current line, so those cues stay readable
            val shownText = if (tints != null && !(w.hot || filled) && color != missedColor) tints.annotate(w.text) else androidx.compose.ui.text.AnnotatedString(w.text)
            val mod = if (mark != null) base.markUnderline(mark, ((lh.value - size.value) * 0.55f).dp) else base
            if (!furigana) Text(shownText, mod, color = tint, fontFamily = targetFont(), fontSize = size, lineHeight = lh)
            else {
                // reading above the word; kana and punctuation get an empty line so every word on the row lines up
                val rb = if (w.isWord && w.reading != null && w.reading != w.text && hasKanji(w.text)) w.reading else null
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rb ?: " ", color = tint.copy(alpha = 0.8f), fontFamily = targetFont(), fontSize = size * 0.45f, lineHeight = size * 0.6f, maxLines = 1, softWrap = false)
                    Text(shownText, mod, color = tint, fontFamily = targetFont(), fontSize = size, lineHeight = lh)
                }
            }
        }
    }
}

/** Plain text control in the same quiet style as the sample clips' "1x". */
@Composable
private fun TextCtl(text: String, on: Boolean = false, enabled: Boolean = true, dense: Boolean = false, strong: Boolean = false, onClick: () -> Unit) {
    val c = Fluvour.colors
    Text(text, Modifier.clip(RoundedCornerShape(22.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = if (dense) 8.dp else 12.dp),
        color = if (!enabled) c.mute.copy(alpha = 0.4f) else if (on) c.accent else if (strong) c.ink else c.mute, fontSize = if (strong) 14.sp else 13.sp, maxLines = 1, softWrap = false)
}

private fun hasKanji(s: String) = s.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' || it == '\u3005' }

/** Underline under a word. Solid, dotted or dashed and thin to thick, as set by the mark. */
@Composable
private fun Modifier.markUnderline(m: Mark, inset: androidx.compose.ui.unit.Dp): Modifier {
    val color = if (Fluvour.dark) m.dark else m.light
    return this.drawBehind {
        val w = m.widthDp.dp.toPx()
        val effect = when (m.style) {
            1 -> PathEffect.dashPathEffect(floatArrayOf(w, w * 1.6f))
            2 -> PathEffect.dashPathEffect(floatArrayOf(w * 3.5f, w * 2f))
            else -> null
        }
        val y = size.height - inset.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = w, pathEffect = effect)
    }
}

@Composable
private fun LegendItem(m: Mark) {
    Text(m.label, Modifier.markUnderline(m, 1.dp).padding(bottom = 4.dp), color = Fluvour.colors.mute, fontSize = 12.sp)
}

/** Play button with a hold gesture. While dictation is on it wears a soft ring, so the mode is visible. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayButton(playing: Boolean, ring: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fluvour.colors
    Box(
        Modifier.size(58.dp).then(if (ring) Modifier.border(10.dp, c.accent.copy(alpha = 0.15f), CircleShape) else Modifier)
            .clip(CircleShape).background(c.accent).combinedClickable(onClick = onClick, onLongClick = onLongClick), Alignment.Center
    ) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play. Hold for dictation", Modifier.size(22.dp), tint = c.onPrimary) }
}

/**
 * Dictation: the subtitle list is replaced by a writing area. Listen (play repeats the line), type, Submit; then the real subtitle,
 * its translation and what you wrote appear together. Words in the answer are tappable for lookup like everywhere else.
 * Shadowing (same pane, switched in the header): the real line stays visible, you say it after the audio, and the phone's speech
 * recognition text is graded like a typed answer: "x of y words", missed words in the accent colour. Only words, not tone or pitch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.DictationPane(
    vm: PlayerViewModel, ui: PlayerUi, d: DictateUi, line: SubLine, total: Int, answer: String, onAnswer: (String) -> Unit,
    trans: String, showTrans: Boolean, colorer: WordColorer?, furigana: Boolean
) {
    val c = Fluvour.colors
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val ctx = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.shadowListen() }
    LaunchedEffect(d.index, d.revealed, d.shadow) {
        if (!d.revealed && !d.shadow) runCatching { requester.requestFocus(); keyboard?.show() } else focus.clearFocus()
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // the two practice modes: the one you are in is in the accent colour; tap the other to switch (same line)
            Text("Dictation", Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.setShadow(false) }.padding(vertical = 10.dp), color = if (!d.shadow) c.accent else c.mute, fontSize = 13.sp)
            Text("  \u00B7  ", color = c.mute, fontSize = 13.sp)
            Text("Shadowing", Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.setShadow(true) }.padding(vertical = 10.dp), color = if (d.shadow) c.accent else c.mute, fontSize = 13.sp)
            Sm("   ${d.index + 1} of $total", size = 12.sp)
            Spacer(Modifier.weight(1f))
            TextCtl("Done", dense = true) { vm.exitDictation() }
        }
        if (!d.revealed && d.shadow) {
            // shadowing: the real line is on screen from the start; listen, then say it. The mic opens by itself after the line.
            Box(Modifier.padding(top = 10.dp)) {
                LineWords(vm, ui, d.index, line, c.ink, 22.sp, 33.sp, colorer, furigana, onLongPress = {}) { w, lemma, reading -> vm.player.pause(); vm.select(line, w, lemma, reading) }
            }
            if (showTrans && trans.isNotBlank()) Text(trans, Modifier.padding(top = 4.dp), color = c.mute, fontSize = 15.sp)
            Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // thin ring, not filled: play stays the only filled button. A soft tint shows the mic is open.
                Box(
                    Modifier.size(64.dp).border(if (d.listening) 2.dp else 1.dp, c.accent, CircleShape).clip(CircleShape)
                        .background(if (d.listening) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable {
                            if (d.listening) vm.shadowStop()
                            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.shadowListen()
                            else askMic.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    Alignment.Center
                ) { Icon(Icons.Outlined.Mic, if (d.listening) "Stop listening" else "Say it", Modifier.size(24.dp), tint = c.accent) }
                Sm(d.note ?: if (d.listening) "Listening" else "Listen, then say it", Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp), size = 12.sp, align = TextAlign.Center)
            }
        } else if (!d.revealed) {
            val font = targetFont()
            BasicTextField(
                value = answer, onValueChange = onAnswer,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(requester),
                textStyle = TextStyle(color = c.ink, fontSize = 21.sp, lineHeight = 31.sp, fontFamily = font),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.dictReveal(answer) }),
                decorationBox = { inner -> Box { if (answer.isEmpty()) Text("Type what you hear", color = c.mute.copy(alpha = 0.7f), fontSize = 21.sp, lineHeight = 31.sp); inner() } }
            )
            HorizontalDivider(Modifier.padding(top = 8.dp), color = c.line)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Sm("Tap play to hear it again", Modifier.weight(1f), size = 12.sp)
                FButton("Submit", { vm.dictReveal(answer) })
            }
        } else {
            val res = d.result
            // what you wrote, with the words that did not match the line struck through
            val wrote = buildAnnotatedString {
                val toks = res?.ansTokens.orEmpty()
                if (res == null || toks.isEmpty()) append(d.answer.ifBlank { if (d.shadow) "(nothing heard)" else "(nothing)" })
                else {
                    var at = 0
                    toks.forEachIndexed { i, t ->
                        if (t.start > at) append(d.answer.substring(at, t.start))
                        val piece = d.answer.substring(t.start, t.end)
                        if (i in res.ansWrong) withStyle(SpanStyle(color = c.mute, textDecoration = TextDecoration.LineThrough)) { append(piece) } else append(piece)
                        at = t.end
                    }
                    if (at < d.answer.length) append(d.answer.substring(at))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Sm(if (d.shadow) "You said" else "You wrote", Modifier.weight(1f), size = 12.sp)
                if (res != null && res.words > 0) {
                    val kanji = if (res.kanji > 0) "  \u00B7  Kanji ${res.kanjiRight} of ${res.kanji}" else ""
                    Text(if (res.perfect) "Perfect$kanji" else "${res.right} of ${res.words} words$kanji", color = if (res.perfect) c.accent else c.mute, fontSize = 12.sp)
                }
            }
            Text(wrote, Modifier.padding(top = 2.dp), color = c.ink, fontFamily = targetFont(), fontSize = 17.sp, lineHeight = 26.sp)
            Box(Modifier.padding(top = 14.dp)) {
                LineWords(vm, ui, d.index, line, c.ink, 22.sp, 33.sp, colorer, furigana, onLongPress = {}, tokens = res?.refTokens, hot = res?.refMissed.orEmpty()) { w, lemma, reading -> vm.player.pause(); vm.select(line, w, lemma, reading) }
            }
            if (res != null && res.refMissed.isNotEmpty()) Sm("Tap a missed word to look it up", Modifier.padding(top = 2.dp), size = 12.sp)
            if (showTrans && trans.isNotBlank()) Text(trans, Modifier.padding(top = 4.dp), color = c.mute, fontSize = 15.sp)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextCtl("Try again") { vm.dictRetry() }
                Spacer(Modifier.weight(1f))
                FButton("Next line", { vm.dictNext() }, enabled = d.index + 1 < total)
            }
        }
    }
}
