package app.fluvour.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.LayoutInflater
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.fluvour.media.Playable
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.delay

/** Word colours, furigana and translation lines for one video. The portrait page and the landscape screen share them. */
internal class CaptionData(
    val wc: WordColorViewModel, val mode: ColorMode, val ja: Boolean, val furigana: Boolean, val showTrans: Boolean,
    val trans: List<String>, val colorer: WordColorer?
)

@Composable
internal fun rememberCaptionData(vm: PlayerViewModel, item: Playable): CaptionData {
    // word colours: how well you know each subtitle word (your cards + Heisig kanji)
    val wc: WordColorViewModel = hiltViewModel()
    val mode by wc.mode.collectAsStateWithLifecycle()
    val ja = vm.lang == "ja"
    val furigana by wc.furigana.collectAsStateWithLifecycle()
    val showTrans by wc.translation.collectAsStateWithLifecycle()
    // each translation line goes to the subtitle line it overlaps most
    val trans = remember(item) {
        val out = Array(item.lines.size) { StringBuilder() }
        for (t in item.translation) {
            var best = -1; var bestOv = 0L
            item.lines.forEachIndexed { i, l ->
                val ov = minOf(l.endMs, t.endMs) - maxOf(l.startMs, t.startMs)
                if (ov > bestOv) { bestOv = ov; best = i }
            }
            if (best >= 0) { if (out[best].isNotEmpty()) out[best].append(' '); out[best].append(t.text.replace("\n", " ").trim()) }
        }
        out.map { it.toString() }
    }
    val tablesReady by produceState(wc.isWarm(vm.lang), vm.lang) { wc.warm(vm.lang); value = true }
    val knownFlow = remember(vm.lang) { wc.known(vm.lang) }
    val learnFlow = remember(vm.lang) { wc.learning(vm.lang) }
    val knownW by knownFlow.collectAsStateWithLifecycle()
    val learnW by learnFlow.collectAsStateWithLifecycle()
    val kanjiKnown by produceState(wc.heisigCached(), vm.lang) { value = if (vm.lang == "ja") wc.heisigKnown() else emptySet() }
    val colorer = remember(mode, knownW, learnW, kanjiKnown, tablesReady) {
        if (mode == ColorMode.OFF) null
        else WordColorer(
            mode, ja, KnowSets(knownW.map { it.trim().lowercase() }.toSet(), learnW.map { it.trim().lowercase() }.toSet(), kanjiKnown),
            level = { w, lem -> wc.level(vm.lang, w, lem) }, grade = { ch -> wc.grade(ch) }
        )
    }

    return CaptionData(wc, mode, ja, furigana, showTrans, trans, colorer)
}

private val FsSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${x.toString().padStart(2, '0')}" else "$m:${x.toString().padStart(2, '0')}"
}

internal tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

/** Turns the phone to landscape (full screen) even when auto-rotate is off. WatchScreen's `OrientationLockRelease` lets go of the lock again. */
internal fun Context.enterFullScreen() { findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
internal fun Context.exitFullScreen() { findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }

/**
 * Lets go of the orientation lock the full-screen buttons set, once the phone is physically held that way (so turning it works normally
 * again), and always when the Watch tab is left. With auto-rotate off the lock stays, because letting go would turn the screen back.
 */
@Composable
internal fun OrientationLockRelease() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val act = ctx.findActivity()
        val l = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(deg: Int) {
                val a = act ?: return
                if (deg == ORIENTATION_UNKNOWN) return
                val req = a.requestedOrientation
                if (req == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
                val auto = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                if (!auto) return
                val upright = deg <= 25 || deg >= 335
                val sideways = deg in 65..115 || deg in 245..295
                if ((req == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && upright) || (req == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE && sideways))
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (l.canDetectOrientation()) l.enable()
        onDispose { l.disable(); act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/**
 * Landscape Watch: immersion. The video fills the screen; the current line is drawn on it (furigana, subtitle, translation, word colours,
 * every word tappable). One tap shows the controls (play and time on the left; captions, settings, exit on the right) over a thin progress
 * line that can be dragged. Tapping a word pauses and opens the word card in a side panel, and the video shrinks to make room.
 * No dictation or shadowing here: those stay on the portrait page.
 */
@Composable
fun WatchLandscape(vm: PlayerViewModel, ui: PlayerUi, item: Playable, onAddCard: (String, String, String, String?) -> Unit) {
    val c = Fluvour.colors
    val ctx = LocalContext.current
    val view = LocalView.current
    val cd = rememberCaptionData(vm, item)
    val look by vm.lookup.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var shown by remember { mutableStateOf(true) }          // controls; they start visible and fade out while it plays
    var menuOpen by remember { mutableStateOf(false) }
    var captionsOn by rememberSaveable { mutableStateOf(true) }
    var drag by remember { mutableStateOf<Float?>(null) }   // 0..1 while the progress line is being dragged
    LaunchedEffect(Unit) { while (true) { pos = vm.player.currentPosition; playing = vm.player.isPlaying; dur = vm.player.duration.takeIf { it > 0 } ?: 0L; delay(200) } }
    val total = dur.takeIf { it > 0 } ?: item.durationMs

    // full screen: no tab bar, no system bars, screen stays on
    DisposableEffect(Unit) {
        vm.immersive.value = true
        val window = ctx.findActivity()?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose { vm.immersive.value = false; ctl?.show(WindowInsetsCompat.Type.systemBars()); view.keepScreenOn = false }
    }
    // dictation, shadowing and audio-only belong to the portrait page: entering here switches them off
    LaunchedEffect(Unit) {
        if (vm.dictate.value.on) vm.toggleDictation(vm.player.currentPosition)
        if (vm.audioOnly.value) vm.toggleAudioOnly()
    }
    LaunchedEffect(shown, playing, menuOpen) { if (shown && playing && !menuOpen) { delay(3000); shown = false } }
    LaunchedEffect(look != null) { if (look != null) { shown = false; menuOpen = false } }

    fun closeLookup() { vm.clear(); vm.player.play() }
    BackHandler(true) { if (menuOpen) menuOpen = false else if (look != null) closeLookup() else ctx.exitFullScreen() }

    val panelTarget = (LocalConfiguration.current.screenWidthDp.dp * 0.42f).coerceIn(300.dp, 440.dp)
    val panelW by animateDpAsState(if (look != null) panelTarget else 0.dp, tween(260), label = "lookupPanel")

    Row(Modifier.fillMaxSize().background(Color.Black).displayCutoutPadding()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // a TextureView, so the picture can be resized smoothly when the panel opens
            AndroidView(
                factory = { x -> (LayoutInflater.from(x).inflate(app.fluvour.R.layout.mini_player_view, null) as PlayerView).apply { player = vm.player } },
                modifier = Modifier.fillMaxSize(), onRelease = { it.player = null }
            )
            // a tap on the picture: show or hide the controls (with the word card open: close it and carry on)
            // (double tap left / right = 10 s back / forward, hold right = 2x: see VideoGestures.kt)
            VideoGestures(vm, Modifier.fillMaxSize(), skipEnabled = look == null, onTap = {
                if (menuOpen) menuOpen = false else if (look != null) closeLookup() else shown = !shown
            })

            // the current line, on the video
            val cur = item.lines.indexOfLast { it.startMs <= pos }
            val line = item.lines.getOrNull(cur)?.takeIf { pos <= it.endMs + 500 || look != null }
            val capPad by animateDpAsState(if (shown) 84.dp else 26.dp, label = "captionLift")
            if (captionsOn && line != null) Box(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = capPad)) {
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // karaoke, same as the Watch page: words turn accent as they are spoken, white until then
                    val litLine = rememberLitWords(vm, line, ui.tokens.getOrNull(cur).orEmpty())
                    LineWords(vm, ui, cur, line, Color.White, 22.sp, 32.sp, cd.colorer, cd.ja && cd.furigana, onLongPress = {}, center = true, tokens = litLine?.tokens, litWords = litLine?.lit) { w, lemma, reading ->
                        vm.player.pause(); vm.select(line, w, lemma, reading)
                    }
                    val tr = cd.trans.getOrNull(cur).orEmpty()
                    if (cd.showTrans && tr.isNotBlank()) Text(tr, Modifier.padding(top = 2.dp), color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, textAlign = TextAlign.Center)
                }
            }

            // controls
            Reveal(shown, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                        FsBtn(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play") { vm.togglePlay() }
                        Text("${clock(drag?.let { (it * total).toLong() } ?: pos)} / ${clock(total)}", Modifier.padding(start = 6.dp), color = Color.White, fontSize = 13.sp, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        FsBtn(if (captionsOn) Icons.Outlined.ClosedCaption else Icons.Outlined.ClosedCaptionOff, if (captionsOn) "Hide captions" else "Show captions") { captionsOn = !captionsOn }
                        FsBtn(Icons.Outlined.Settings, "Settings") { menuOpen = !menuOpen }
                        FsBtn(Icons.Outlined.FullscreenExit, "Exit full screen") { ctx.exitFullScreen() }
                    }
                }
            }

            // thin progress line, always there; drag or tap it to seek
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp).padding(horizontal = 12.dp)
                    .pointerInput(total) { detectTapGestures { o -> if (total > 0) vm.player.seekTo((o.x / size.width * total).toLong().coerceIn(0L, total)) } }
                    .pointerInput(total) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); shown = true },
                            onHorizontalDrag = { ch, _ -> ch.consume(); drag = (ch.position.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { drag?.let { f -> if (total > 0) vm.player.seekTo((f * total).toLong()) }; drag = null },
                            onDragCancel = { drag = null }
                        )
                    }
            ) {
                val frac = drag ?: if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f
                val dot = shown || drag != null
                Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)) {
                    val h = (if (dot) 4.dp else 3.dp).toPx(); val y = size.height - 6.dp.toPx() - h / 2
                    drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                    drawRoundRect(c.accent, Offset(0f, y), Size(size.width * frac, h), CornerRadius(h / 2))
                    if (dot) drawCircle(c.accent, 7.dp.toPx(), Offset(size.width * frac, y + h / 2))
                }
            }

            // settings: speed, translation, furigana, word colours
            Reveal(menuOpen, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp)) {
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xF2161616)).widthIn(min = 240.dp).padding(vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        FsSpeeds.forEach { sp ->
                            Text(if (sp == 1f) "1x" else "${sp}x", Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.setSpeed(sp) }.padding(horizontal = 10.dp, vertical = 8.dp),
                                color = if (sp == speed) c.accent else Color.White, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                    MenuRow("Translation", if (item.translation.isEmpty()) "none" else if (cd.showTrans) "on" else "off", cd.showTrans, item.translation.isNotEmpty()) { cd.wc.toggleTranslation() }
                    if (cd.ja) MenuRow("Furigana", if (cd.furigana) "on" else "off", cd.furigana, true) { cd.wc.toggleFurigana() }
                    MenuRow("Word colors", if (cd.mode == ColorMode.OFF) "off" else cd.mode.label.removePrefix("Colors: "), cd.mode != ColorMode.OFF, true) { cd.wc.cycle(cd.ja) }
                }
            }
        }

        // word card: the video above gives up this width while it is open
        if (panelW > 0.dp) Box(Modifier.width(panelW).fillMaxHeight().clipToBounds().background(c.paper)) {
            look?.let { lk ->
                Box(Modifier.requiredWidth(panelTarget).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 36.dp)) { LookupBody(vm, lk, onAddCard) }
                    Box(Modifier.align(Alignment.TopEnd)) { IconBtn(Icons.Outlined.Close, "Close", { closeLookup() }) }
                }
            }
        }
    }
}

/** Fade in and out. A top-level helper, because AnimatedVisibility called inside the Row picks the Row-scope overload and fails to compile. */
@Composable
internal fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) { content() }
}

@Composable
private fun FsBtn(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) { Icon(icon, description, Modifier.size(26.dp), tint = Color.White) }
}

@Composable
private fun MenuRow(label: String, value: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (on) c.accent else Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
    }
}
