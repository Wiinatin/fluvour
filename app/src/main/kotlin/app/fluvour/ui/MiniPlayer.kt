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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val MiniW = 208.dp         // about half the width of a phone screen, like the YouTube mini player
internal val MiniH = 117.dp         // 16:9
private val Margin = 10.dp
private val MiniShape = RoundedCornerShape(14.dp)   // the app's own corner

/**
 * The floating video window. Host it in a Box that is already clear of the status bar, bottom bar and keyboard (see Root.kt).
 * Drag it anywhere; it snaps to the nearest side on release. Like the YouTube mini player, the picture is clean until it is tapped:
 * a tap shows bare white icons over a soft dark veil (they hide again after a few seconds while it plays), and the expand button goes
 * back to Watch. Top right: expand, X. Bottom: headphones (hide the picture, keep the sound) and play/pause in the middle.
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
                    .shadow(if (Fluvour.style.flat) 0.dp else 8.dp, MiniShape)
                    .clip(MiniShape).background(Color.Black)
                    .border(1.dp, c.line, MiniShape)
            ) {
                MiniWindowBody(vm, audioOnly, playing, onReturn) { onTap -> Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
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
                }
            }
        }
    }
}

/**
 * Picture, and on tap a soft veil with the controls. Shared by the in-app window and the over-other-apps window.
 * `gesture` builds the tap and drag handling on the picture; it must call the `onTap` it is given for a plain tap.
 */
@Composable
internal fun BoxScope.MiniWindowBody(vm: PlayerViewModel, audioOnly: Boolean, playing: Boolean, onReturn: () -> Unit, gesture: (onTap: () -> Unit) -> Modifier) {
    var shown by remember { mutableStateOf(false) }
    // while it plays the controls go away by themselves; paused, they stay
    LaunchedEffect(shown, playing) { if (shown && playing) { delay(3000); shown = false } }
    if (audioOnly) Box(Modifier.fillMaxSize(), Alignment.Center) { Sm("Audio only", size = 12.sp, color = Color.White) }
    else AndroidView(
        // inflated so it can use a TextureView: a SurfaceView ignores the rounded corners, which showed as sharp corners in the window over other apps
        factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(app.fluvour.R.layout.mini_player_view, null) as PlayerView).apply { player = vm.player } },
        modifier = Modifier.fillMaxSize(), onRelease = { it.player = null }
    )
    Box(Modifier.fillMaxSize().then(gesture { shown = !shown }))
    // darker at the top and bottom, where the white icons sit, so they read over any picture
    AnimatedVisibility(shown, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.5f)))))
    }
    AnimatedVisibility(shown, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize()) { MiniControls(vm, playing, onReturn) } }
}

@Composable
private fun BoxScope.MiniControls(vm: PlayerViewModel, playing: Boolean, onReturn: () -> Unit) {
    Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        MiniBtn(MiniIcons.Expand, "Back to Watch", 32.dp, 20.dp) { onReturn() }
        MiniBtn(MiniIcons.Close, "Close", 32.dp, 20.dp) { vm.closeMini() }
    }
    MiniBtn(Icons.Rounded.Headphones, "Hide, keep playing sound", 36.dp, 22.dp, Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 6.dp)) { vm.minimizeToAudio() }
    MiniBtn(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", 44.dp, 32.dp, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) { vm.togglePlay() }
}

/** A bare white icon, no disc, with a touch area bigger than the icon. */
@Composable
private fun MiniBtn(icon: ImageVector, description: String, area: Dp, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(area).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, description, Modifier.size(size), tint = Color.White)
    }
}
