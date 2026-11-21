package app.fluvour.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Microphone permission for recording. Returns a function to call when Record is tapped: it runs [onGranted] at once if the
 * permission is already there, otherwise asks once and runs [onGranted] or [onDenied] with the answer. The caller shows the one
 * plain sentence before the system dialog and the one muted line after a refusal; Android itself stops showing the dialog after
 * repeated refusals, so there is no prompt loop.
 */
@Composable
fun rememberMicRequest(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val denied by rememberUpdatedState(onDenied)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) granted() else denied() }
    return {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) granted()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}
