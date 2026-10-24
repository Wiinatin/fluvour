package app.fluvour.ocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One screen capture session (Phase 2B step 1). The user agrees once ("Start now"); after that every lookup is a single screenshot.
 * Android 14 allows one virtual display per permission, so the display is created once and its surface is only attached for the
 * moment of a capture, then detached again so nothing is recorded in between.
 */
class ScreenCapturer(private val context: Context) {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    var width = 0; private set
    var height = 0; private set
    /** Called when Android or the user ends the capture permission (for example from the status bar). */
    var onStopped: (() -> Unit)? = null

    @Suppress("DEPRECATION")
    fun start(resultCode: Int, data: Intent): Boolean {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try { mgr.getMediaProjection(resultCode, data) } catch (e: Exception) { null } ?: return false
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { release(); onStopped?.invoke() }
        }, handler)
        val dm = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        width = dm.widthPixels; height = dm.heightPixels
        return try {
            val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = r
            display = p.createVirtualDisplay("fluvour-screen-lookup", width, height, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null)
            true
        } catch (e: Exception) { release(); false }
    }

    /** One screenshot, or null if none arrived within the time limit. */
    suspend fun capture(timeoutMs: Long = 1800): Bitmap? {
        val r = reader ?: return null
        val d = display ?: return null
        drain(r)
        val bmp = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                r.setOnImageAvailableListener({ rd ->
                    val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
                    if (img != null) {
                        rd.setOnImageAvailableListener(null, null)
                        val b = try { toBitmap(img) } finally { img.close() }
                        if (cont.isActive) cont.resume(b)
                    }
                }, handler)
                cont.invokeOnCancellation { r.setOnImageAvailableListener(null, null) }
                d.surface = r.surface
            }
        }
        try { d.surface = null } catch (e: Exception) { }
        return bmp
    }

    private fun drain(r: ImageReader) {
        try { while (true) (r.acquireLatestImage() ?: break).close() } catch (e: Exception) { }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val padding = plane.rowStride - pixelStride * img.width
        val wide = Bitmap.createBitmap(img.width + padding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        return if (padding == 0) wide else Bitmap.createBitmap(wide, 0, 0, img.width, img.height).also { wide.recycle() }
    }

    fun release() {
        val d = display; val r = reader; val p = projection
        display = null; reader = null; projection = null
        try { d?.release() } catch (e: Exception) { }
        try { r?.close() } catch (e: Exception) { }
        try { p?.stop() } catch (e: Exception) { }
    }

    companion object {
        /** Apps that set FLAG_SECURE (banking, some video players) give an all-black capture. */
        fun looksBlocked(b: Bitmap): Boolean {
            val xs = 12; val ys = 20
            for (i in 0 until xs) for (j in 0 until ys) {
                val p = b.getPixel((i + 0.5f).times(b.width / xs).toInt().coerceIn(0, b.width - 1), (j + 0.5f).times(b.height / ys).toInt().coerceIn(0, b.height - 1))
                if ((p and 0xFFFFFF) != 0) return false
            }
            return true
        }
    }
}
