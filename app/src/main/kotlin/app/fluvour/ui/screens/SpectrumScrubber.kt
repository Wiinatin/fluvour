package app.fluvour.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.media.SpectrumTap
import app.fluvour.ui.AbLoop
import app.fluvour.ui.PlayerViewModel
import app.fluvour.ui.theme.Fluvour

/**
 * One scrubber, two layers. Behind: the whole audio as a dim, mirrored waveform (left to right is time; the played part is tinted).
 * In front: the live spectrum, bouncing with the sound (left to right is pitch, low to high), with a soft reflection.
 * Tap or drag anywhere to seek by time; the thin playhead line shows where you are. The A-B loop is shaded.
 */
@Composable
fun SpectrumScrubber(vm: PlayerViewModel, pos: Long, dur: Long, loop: AbLoop, onSeek: (Long) -> Unit) {
    val c = Fluvour.colors
    val ctx = LocalContext.current
    val wave by vm.wave.collectAsStateWithLifecycle()
    val bandsState = vm.spectrum.collectAsStateWithLifecycle()
    val on by vm.spectrumOn.collectAsStateWithLifecycle()

    // Ask once for the microphone permission (Android's audio analysis needs it; nothing is recorded).
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.enableSpectrum() }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.enableSpectrum()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val a = loop.a; val b = loop.b
    Canvas(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(76.dp)
            .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) onSeek((o.x / size.width * dur).toLong().coerceIn(0L, dur)) } }
            .pointerInput(dur) { detectHorizontalDragGestures { change, _ -> if (dur > 0) onSeek((change.position.x / size.width * dur).toLong().coerceIn(0L, dur)) } }
    ) {
        val mid = size.height * 0.58f                       // the mirror line sits a little low: tall bars above, a short reflection below

        // loop shading goes behind everything
        if (dur > 0 && a != null) {
            val ax = a.toFloat() / dur * size.width
            if (b != null) drawRect(c.accent.copy(alpha = 0.13f), Offset(ax, 0f), Size(b.toFloat() / dur * size.width - ax, size.height))
        }

        // back layer: the whole-audio waveform, dim
        val n = wave?.size ?: 120
        val step = size.width / n
        val bw = (step * 0.62f).coerceAtLeast(1f)
        for (i in 0 until n) {
            val h = (wave?.get(i) ?: 0.07f).coerceIn(0.05f, 1f) * size.height * 0.46f
            val played = (i + 0.5f) / n <= frac
            drawRoundRect(
                if (played) c.accent.copy(alpha = 0.30f) else c.mute.copy(alpha = 0.20f),
                Offset(i * step, mid - h), Size(bw, h * 1.5f), CornerRadius(bw / 2f)
            )
        }

        // front layer: the live spectrum
        val bands = bandsState.value
        if (on) {
            val cnt = SpectrumTap.BANDS
            val s = size.width / cnt
            val w = (s * 0.56f).coerceAtLeast(2f)
            val top = mid - 3f
            val up = Brush.verticalGradient(listOf(c.accent, c.accent.copy(alpha = 0.55f)), startY = 0f, endY = mid)
            val down = Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.28f), c.accent.copy(alpha = 0f)), startY = mid, endY = size.height)
            for (i in 0 until cnt) {
                val v = bands.getOrElse(i) { 0f }
                val h = (3f + v * (top - 3f)).coerceAtMost(top)
                val x = i * s + (s - w) / 2f
                drawRoundRect(up, Offset(x, mid - h), Size(w, h), CornerRadius(w / 2f))
                val rh = h * 0.42f
                drawRoundRect(down, Offset(x, mid + 3f), Size(w, rh), CornerRadius(w / 2f))
            }
        }

        // loop ends and playhead
        if (dur > 0 && a != null) {
            drawRect(c.accent, Offset(a.toFloat() / dur * size.width - 1f, 0f), Size(2f, size.height))
            if (b != null) drawRect(c.accent, Offset(b.toFloat() / dur * size.width - 1f, 0f), Size(2f, size.height))
        }
        if (dur > 0) {
            val px = frac * size.width
            drawRoundRect(c.ink.copy(alpha = 0.85f), Offset(px - 1f, 2f), Size(2f, size.height - 4f), CornerRadius(1f))
        }
    }
}
