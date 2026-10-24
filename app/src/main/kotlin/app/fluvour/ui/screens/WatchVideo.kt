package app.fluvour.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.fluvour.media.Playable
import app.fluvour.media.SubLine
import app.fluvour.reading.Token
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.delay

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
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var dur by remember { mutableLongStateOf(0L) }
    val mined by vm.mined.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val loop by vm.loop.collectAsStateWithLifecycle()
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { pos = vm.player.currentPosition; playing = vm.player.isPlaying; dur = vm.player.duration.takeIf { it > 0 } ?: 0L; delay(200) } }
    // No pause here: WatchScreen pauses when you leave the tab, so opening the search page keeps the video playing.

    val cur = item.lines.indexOfLast { it.startMs <= pos }
    val curLine = item.lines.getOrNull(cur)
    val lineSaved = curLine != null && subLineText(curLine, vm.lang) in mined

    // video frame: same corner as the art banner, tighter vertical padding so the list gets the room
    val frame = Modifier.padding(horizontal = 22.dp, vertical = 8.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.card)
    if (audioOnly) Box(frame, contentAlignment = Alignment.Center) { Sm("Audio only. The video is off to save data and battery.", size = 13.sp) }
    else AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = vm.player; useController = false } }, modifier = frame)

    SpectrumScrubber(vm, pos, dur, loop) { vm.player.seekTo(it) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Sm(stamp(pos), size = 12.sp)
        if (item.audioUrl != null) TextCtl(if (audioOnly) "Show video" else "Audio only", audioOnly) { vm.toggleAudioOnly() }
        Sm(stamp(dur), size = 12.sp)
    }

    // play row: speed, play, loop
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        TextCtl(if (speed == 1f) "1x" else "${speed}x", speed != 1f) { vm.setSpeed(SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]) }
        RoundAction(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "Play or pause", { if (vm.player.isPlaying) vm.player.pause() else vm.player.play() }, size = 58.dp)
        IconBtn(Icons.Outlined.Repeat, if (loop.a != null) "Clear loop" else "Loop this line",
            { if (loop.a != null) vm.clearLoop() else curLine?.let(vm::loopLine) }, tint = if (loop.a != null) c.accent else c.mute)
    }
    // A-B markers (plain text) with "Mine this line" beside them
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextCtl(loop.a?.let { "A ${stamp(it)}" } ?: "Set A", loop.a != null) { vm.markA() }
        TextCtl(loop.b?.let { "B ${stamp(it)}" } ?: "Set B", loop.b != null, enabled = loop.a != null) { vm.markB() }
        Spacer(Modifier.weight(1f))
        FButton(if (lineSaved) "Line saved" else "Mine this line", { curLine?.let(vm::mineLine) },
            outlined = !lineSaved, soft = lineSaved, enabled = curLine != null && !lineSaved)
    }
    HorizontalDivider(Modifier.padding(top = 8.dp), color = c.line)

    // subtitle list: the only scrolling part of the page. Follows the video; a manual scroll pauses following for a few seconds.
    val listState = rememberLazyListState()
    var pausedUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) pausedUntil = System.currentTimeMillis() + 4000L }
    }
    LaunchedEffect(cur) {
        if (cur < 0) return@LaunchedEffect
        val wait = pausedUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (System.currentTimeMillis() >= pausedUntil) listState.animateScrollToItem(cur)
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 6.dp)) {
        itemsIndexed(item.lines) { n, l ->
            val on = n == cur
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stamp(l.startMs), Modifier.width(40.dp).clickable { vm.player.seekTo(l.startMs) }.padding(top = 6.dp), color = if (on) c.accent else c.mute, fontSize = 12.5.sp)
                LineWords(vm, ui, n, l, if (on) c.accent else c.ink, if (on) 24.sp else 19.sp, if (on) 36.sp else 29.sp) { w, lemma, reading -> vm.player.pause(); vm.select(l, w, lemma, reading) }
            }
        }
        listOfNotNull(item.uploader, item.subtitleNote).joinToString(" \u00B7 ").takeIf { it.isNotBlank() }
            ?.let { note -> item { Sm(note, Modifier.padding(vertical = 16.dp), size = 12.sp) } }
    }
    VideoLookupSheet(vm, onClose = { vm.clear(); vm.player.play() }, onAddCard = onAddCard)
}

private data class W(val text: String, val isWord: Boolean, val lemma: String?, val reading: String?)

/** One subtitle line as tappable words. Uses the reader's tokens when ready, the platform word breaker until then. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineWords(vm: PlayerViewModel, ui: PlayerUi, n: Int, l: SubLine, color: Color, size: TextUnit, lh: TextUnit, onWord: (String, String?, String?) -> Unit) {
    val toks: List<Token> = ui.tokens.getOrNull(n).orEmpty()
    val words = if (toks.isEmpty()) splitWords(l.text, vm.lang).map { (p, w) -> W(p, w, null, null) }
    else toks.map { t -> W(l.text.substring(t.start, t.end).replace("\n", " "), t.isWord, t.lemma, t.reading) }
    FlowRow {
        words.forEach { w ->
            Text(w.text, if (w.isWord) Modifier.clickable { onWord(w.text, w.lemma, w.reading) } else Modifier,
                color = color, fontFamily = targetFont(), fontSize = size, lineHeight = lh)
        }
    }
}

/** Plain text control in the same quiet style as the sample clips' "1x". */
@Composable
private fun TextCtl(text: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Fluvour.colors
    Text(text, Modifier.clip(RoundedCornerShape(22.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        color = if (!enabled) c.mute.copy(alpha = 0.4f) else if (on) c.accent else c.mute, fontSize = 13.sp)
}
