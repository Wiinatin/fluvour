package app.fluvour.ocr

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible screen that asks Android for permission to capture the screen, then starts the floating button.
 * Android shows its own dialog; on Android 14 and newer choose "Entire screen" so the other app is included.
 */
class CaptureRequestActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) startForegroundService(OverlayService.startIntent(this, r.resultCode, data))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        ask.launch(mgr.createScreenCaptureIntent())
    }
}
