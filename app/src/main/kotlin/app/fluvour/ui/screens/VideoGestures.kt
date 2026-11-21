package app.fluvour.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.ui.PlayerViewModel
import kotlinx.coroutines.delay

/** The arithmetic behind the gestures, kept free of Android so it can be tested on its own. */
internal object SkipMath {
    const val STEP_MS = 10_000L
    /** How long after a skip a plain tap on either side still skips again (and the running total keeps counting). */
    const val STREAK_MS = 900L

    /** -1 = left third, 0 = middle third, 1 = right third. */
    fun zone(x: Float, width: Float): Int = if (width <= 0f) 0 else when {
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    /** Where a skip of `dir` (-1 or 1) lands; never before 0:00, never past the end (when the length is known). */
    fun target(pos: Long, dur: Long, dir: Int): Long {
        val t = pos + dir * STEP_MS
        return if (dur > 0) t.coerceIn(0L, dur) else t.coerceAtLeast(0L)
    }

    /** The running label: 10, 20, 30 ... on the same side inside the streak window; back to 10 on a new side or after a pause. */
    fun total(prevSeconds: Int, prevDir: Int, dir: Int, msSinceLast: Long): Int =
        if (prevDir == dir && msSinceLast < STREAK_MS) prevSeconds + (STEP_MS / 1000).toInt() else (STEP_MS / 1000).toInt()
}

/**
 * The picture's touch layer, shared by the small window and full screen.
 *  - double tap left / right third: back / forward 10 s; keep tapping the same side to keep skipping (running total shown)
 *  - hold the right third while it plays: 2x until you let go (back to your own speed, not just 1x)
 *  - a single tap (after the double-tap wait) calls `onTap`, the old behaviour of each screen
 * Anything drawn above it that handles touches (caption words, buttons) takes the touch first, so the gestures only act on the bare picture.
 */
@Composable
internal fun VideoGestures(
    vm: PlayerViewModel, modifier: Modifier = Modifier, skipEnabled: Boolean = true,
    onTap: () -> Unit, content: @Composable BoxScope.() -> Unit = {}
) {
    val view = LocalView.current
    var flashDir by remember { mutableIntStateOf(0) }
    var flashTotal by remember { mutableIntStateOf(0) }
    var flashTick by remember { mutableIntStateOf(0) }
    var flashOn by remember { mutableStateOf(false) }   // visibility only: the side and icon stay put while it fades out
    var lastAt by remember { mutableLongStateOf(0L) }
    var boosting by remember { mutableStateOf(false) }
    val onTapNow by rememberUpdatedState(onTap)
    val enabledNow by rememberUpdatedState(skipEnabled)

    // the label fades about 0.8 s after the last skip
    LaunchedEffect(flashTick) { if (flashTick > 0) { delay(800); flashOn = false } }
    // never leave the video stuck at 2x
    DisposableEffect(Unit) { onDispose { vm.boost(false) } }
    LaunchedEffect(skipEnabled) { if (!skipEnabled && boosting) { vm.boost(false); boosting = false } }

    fun skip(dir: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        flashTotal = SkipMath.total(flashTotal, flashDir, dir, now - lastAt)
        flashDir = dir; flashOn = true; lastAt = now; flashTick++
        vm.skipBy(dir)
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    Box(
        modifier.pointerInput(Unit) {
            val longPress = viewConfiguration.longPressTimeoutMillis
            val dbl = viewConfiguration.doubleTapTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown()
                val zone = SkipMath.zone(down.position.x, size.width.toFloat())
                // hold: 2x on the right third while it plays
                val up = try { withTimeout(longPress) { waitForUpOrCancellation() } ?: return@awaitEachGesture }
                catch (_: PointerEventTimeoutCancellationException) {
                    if (enabledNow && zone == 1 && vm.player.isPlaying) {
                        vm.boost(true); boosting = true
                        try { waitForUpOrCancellation() } finally { vm.boost(false); boosting = false }
                    } else waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                up.consume()
                val now = android.os.SystemClock.uptimeMillis()
                // inside a streak a single tap on either side keeps skipping at once
                if (enabledNow && zone != 0 && flashDir != 0 && now - lastAt < SkipMath.STREAK_MS) { skip(zone); return@awaitEachGesture }
                val second = withTimeoutOrNull(dbl) { awaitFirstDown() }
                if (second == null) { onTapNow(); return@awaitEachGesture }
                val z2 = SkipMath.zone(second.position.x, size.width.toFloat())
                waitForUpOrCancellation()?.consume()
                if (enabledNow && zone != 0 && z2 == zone) skip(zone) else onTapNow()
            }
        }
    ) {
        content()
        // the skip label on the side that skipped (no background box); stays on its side while it fades out
        Reveal(flashOn, Modifier.align(if (flashDir < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxWidth(0.4f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (flashDir < 0) Icons.Outlined.Replay10 else Icons.Outlined.Forward10, null, Modifier.size(34.dp), tint = Color.White)
                    Text("$flashTotal seconds", color = Color.White, fontSize = 12.sp, maxLines = 1, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)))
                }
            }
        }
        // 2x pill while holding
        Reveal(boosting, Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
            Row(
                Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.FastForward, null, Modifier.size(16.dp), tint = Color.White)
                Text("2×", Modifier.padding(start = 4.dp), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
