package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.fluvour.ui.*
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.delay

private fun stamp(ms: Long) = "${ms / 60000}:${((ms / 1000) % 60).toString().padStart(2, '0')}"

/** Phase 4.1 / 4.1b / item 4: paste a YouTube link, watch it in Fluvour's own player, follow the live transcript, tap any word for the full lookup sheet, mine a line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    onAddCard: (word: String, sentence: String, meaning: String, sentenceId: String?) -> Unit,
    vm: PlayerViewModel = hiltViewModel()
) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    val item = ui.item
    DisposableEffect(Unit) { onDispose { vm.player.pause() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().ambient().languageMark()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Outlined.Close, "Close", { if (item != null) vm.backToLink() else onClose() })
            Text(item?.title ?: "Watch a video", Modifier.padding(start = 6.dp), color = c.ink, fontSize = 17.sp, maxLines = 1)
        }
        if (item == null) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Sm("Paste a YouTube link. The video plays here, with its subtitles in your learning language.")
                OutlinedTextField(value = link, onValueChange = { v: String -> link = v }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), singleLine = true, placeholder = { Text("https://www.youtube.com/watch?v=...") })
                ui.error?.let { Sm(it, Modifier.padding(top = 10.dp), color = c.accent) }
                FButton(if (ui.loading) "Opening..." else "Open", { if (!ui.loading) vm.open(link) }, Modifier.fillMaxWidth().padding(top = 18.dp), soft = ui.loading)
            }
            return@Column
        }

        var pos by remember { mutableLongStateOf(0L) }
        var playing by remember { mutableStateOf(true) }
        var dur by remember { mutableLongStateOf(0L) }
        val mined by vm.mined.collectAsStateWithLifecycle()
        val speed by vm.speed.collectAsStateWithLifecycle()
        val loop by vm.loop.collectAsStateWithLifecycle()
        val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { while (true) { pos = vm.player.currentPosition; playing = vm.player.isPlaying; dur = vm.player.duration.takeIf { it > 0 } ?: 0L; delay(200) } }

        if (audioOnly) {
            Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(84.dp).clip(RoundedCornerShape(14.dp)).background(c.card), contentAlignment = Alignment.Center) {
                Sm("Audio only. The video is off to save data and battery.", size = 13.sp)
            }
        } else AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = vm.player; useController = false } },
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.card)
        )
        val cur = item.lines.indexOfLast { it.startMs <= pos }
        val curLine = item.lines.getOrNull(cur)
        val lineSaved = curLine != null && subLineText(curLine, vm.lang) in mined
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundAction(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play",
                { if (vm.player.isPlaying) vm.player.pause() else vm.player.play() }, size = 52.dp)
            Sm(stamp(pos), size = 13.sp)
            Spacer(Modifier.weight(1f))
            FButton(if (lineSaved) "Line saved" else "Mine this line", { curLine?.let(vm::mineLine) }, outlined = true, enabled = curLine != null && !lineSaved)
        }
        SpectrumScrubber(vm, pos, dur, loop) { vm.player.seekTo(it) }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f).forEach { s -> CtlChip(if (s == 1f) "1×" else "$s×", speed == s) { vm.setSpeed(s) } }
        }
        FlowRow(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CtlChip(loop.a?.let { "A ${stamp(it)}" } ?: "Set A", loop.a != null) { vm.markA() }
            CtlChip(loop.b?.let { "B ${stamp(it)}" } ?: "Set B", loop.b != null, enabled = loop.a != null) { vm.markB() }
            CtlChip("Loop line", enabled = curLine != null) { curLine?.let(vm::loopLine) }
            if (loop.a != null) CtlChip("Clear") { vm.clearLoop() }
            CtlChip("Audio only", audioOnly, enabled = item.audioUrl != null) { vm.toggleAudioOnly() }
        }
        listOfNotNull(item.uploader, item.subtitleNote).joinToString(" · ").takeIf { it.isNotBlank() }
            ?.let { Sm(it, Modifier.padding(horizontal = 22.dp, vertical = 4.dp), size = 12.sp) }

        val listState = rememberLazyListState()
        LaunchedEffect(cur) { if (cur >= 0) listState.animateScrollToItem(cur) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 22.dp), state = listState) {
            itemsIndexed(item.lines) { n, l ->
                val on = n == cur
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stamp(l.startMs), Modifier.clickable { vm.player.seekTo(l.startMs) }, color = c.mute, fontSize = 11.sp)
                    FlowRow {
                        val toks = ui.tokens.getOrNull(n).orEmpty()
                        if (toks.isEmpty()) {
                            splitWords(l.text, vm.lang).forEach { (piece, isWord) ->
                                Text(piece, color = if (on) c.accent else c.ink, fontFamily = targetFont(), fontSize = 19.sp, lineHeight = 30.sp,
                                    modifier = if (isWord) Modifier.clickable { vm.player.pause(); vm.select(l, piece, null, null) } else Modifier)
                            }
                        } else toks.forEach { t ->
                            val piece = l.text.substring(t.start, t.end).replace("\n", " ")
                            Text(piece, color = if (on) c.accent else c.ink, fontFamily = targetFont(), fontSize = 19.sp, lineHeight = 30.sp,
                                modifier = if (t.isWord) Modifier.clickable { vm.player.pause(); vm.select(l, piece, t.lemma, t.reading) } else Modifier)
                        }
                    }
                }
            }
        }
    }
    VideoLookupSheet(vm, onClose = { vm.clear(); vm.player.play() }, onAddCard = onAddCard)
}

/** Small pill button for the speed and A-B loop controls. */
@Composable
private fun CtlChip(text: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(50)
    Text(
        text,
        Modifier.clip(shape).background(if (on) c.accent.copy(alpha = 0.12f) else c.card)
            .border(1.dp, if (on) c.accent else c.mute.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (!enabled) c.mute.copy(alpha = 0.5f) else if (on) c.accent else c.ink, fontSize = 13.sp
    )
}
