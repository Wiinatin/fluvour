package app.fluvour.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val MiniW = 176.dp
internal val MiniH = 99.dp          // 16:9
private val Margin = 10.dp
private val Btn = 28.dp

/**
 * The floating video window. Host it in a Box that is already clear of the status bar, bottom bar and keyboard (see Root.kt).
 * Drag it anywhere; it snaps to the nearest side on release. Tap the picture or the expand button to go back to Watch.
 * Corners: expand (top left), X (top right), play/pause (bottom left), hide-but-keep-sound (bottom right).
 */
@Composable
fun BoxScope.MiniPlayer(vm: PlayerViewModel, visible: Boolean, onReturn: () -> Unit) {
    val c = Fluvour.colors
    val mini by vm.mini.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    val item = vm.ui.collectAsStateWithLifecycle().value.item
    AnimatedVisibility(visible && mini == MiniMode.MINI && item != null, Modifier.matchParentSize(), enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = LocalDensity.current
            val w = with(d) { MiniW.toPx() }; val h = with(d) { MiniH.toPx() }; val m = with(d) { Margin.toPx() }
            val maxX = (constraints.maxWidth - w - m).coerceAtLeast(m)
            val maxY = (constraints.maxHeight - h - m).coerceAtLeast(m)
            fun yOf(frac: Float) = m + (maxY - m) * frac.coerceIn(0f, 1f)
            val pos = remember(maxX, maxY) { Animatable(Offset(if (vm.miniRight) maxX else m, yOf(vm.miniYFrac)), Offset.VectorConverter) }
            val scope = rememberCoroutineScope()
            Box(
                Modifier.offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
                    .size(MiniW, MiniH)
                    .shadow(if (Fluvour.style.flat) 0.dp else 8.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp)).background(c.card)
                    .border(1.dp, c.line, RoundedCornerShape(14.dp))
            ) {
                MiniWindowBody(vm, audioOnly, playing, onReturn, Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { onReturn() }) }
                    .pointerInput(maxX, maxY) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                val next = Offset((pos.value.x + drag.x).coerceIn(m, maxX), (pos.value.y + drag.y).coerceIn(m, maxY))
                                scope.launch { pos.snapTo(next) }
                            },
                            onDragEnd = {
                                val right = pos.value.x + w / 2 > (maxX + w + m) / 2
                                vm.miniRight = right; vm.miniYFrac = if (maxY > m) (pos.value.y - m) / (maxY - m) else 0f
                                scope.launch { pos.animateTo(Offset(if (right) maxX else m, pos.value.y)) }
                            }
                        )
                    }
                )
            }
        }
    }
}

/** Picture plus the four corner buttons. Shared by the in-app window and the over-other-apps window. `gesture` handles tap and drag on the picture. */
@Composable
internal fun BoxScope.MiniWindowBody(vm: PlayerViewModel, audioOnly: Boolean, playing: Boolean, onReturn: () -> Unit, gesture: Modifier) {
    if (audioOnly) Box(Modifier.fillMaxSize(), Alignment.Center) { Sm("Audio only", size = 12.sp) }
    else AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = vm.player; useController = false } },
        modifier = Modifier.fillMaxSize(), onRelease = { it.player = null }
    )
    Box(Modifier.fillMaxSize().then(gesture))
    MiniBtn(MiniIcons.Expand, "Back to Watch", Modifier.align(Alignment.TopStart).padding(5.dp)) { onReturn() }
    MiniBtn(MiniIcons.Close, "Close", Modifier.align(Alignment.TopEnd).padding(5.dp)) { vm.closeMini() }
    MiniBtn(if (playing) MiniIcons.Pause else MiniIcons.Play, if (playing) "Pause" else "Play", Modifier.align(Alignment.BottomStart).padding(5.dp)) { vm.togglePlay() }
    MiniBtn(MiniIcons.AudioOnly, "Hide, keep playing sound", Modifier.align(Alignment.BottomEnd).padding(5.dp)) { vm.minimizeToAudio() }
}

/** Small round button on a soft paper disc so it stays readable over any picture. */
@Composable
private fun MiniBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Fluvour.colors
    Box(modifier.size(Btn).clip(CircleShape).background(c.paper.copy(alpha = 0.82f)).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, description, Modifier.size(17.dp), tint = c.ink)
    }
}
