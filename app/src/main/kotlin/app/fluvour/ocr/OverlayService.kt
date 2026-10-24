package app.fluvour.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import app.fluvour.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

/**
 * Screen lookup (Phase 2B): a floating button over any app. Tap it: the screen is captured, read (ML Kit, or Gemini as a fallback),
 * and the text is outlined. Tap a block and Fluvour's lookup sheet opens over the app. Long-press the button to read only an area.
 * Drag it to move it. The notification has a "Turn off" action.
 */
@AndroidEntryPoint
class OverlayService : Service() {
    @Inject lateinit var engine: OcrEngine
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var prefs: OcrPrefs
    private lateinit var cache: OcrCache
    private var capturer: ScreenCapturer? = null
    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var lens: LensView? = null
    private var job: Job? = null

    private var lastPage: OcrPage? = null
    private var lastKey = ""
    private var lastLang = "en"
    private var lastApp: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = OcrPrefs(this)
        cache = OcrCache(cacheDir)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                goForeground()
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val cap = ScreenCapturer(this)
                if (data == null || !cap.start(code, data)) {
                    Toast.makeText(this, "Could not start screen capture.", Toast.LENGTH_LONG).show()
                    stopSelf(); return START_NOT_STICKY
                }
                cap.onStopped = { stopSelf() }
                capturer?.release(); capturer = cap
                if (bubble == null) addBubble()
                running.value = true
            }
            ACTION_RESHOW -> lastPage?.let { showLens(it, null) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.value = false
        scope.cancel()
        removeLens()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
        capturer?.release(); capturer = null
        super.onDestroy()
    }

    // ---- notification ----
    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen lookup", NotificationManager.IMPORTANCE_LOW))
        val off = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Screen lookup is on")
            .setContentText("Tap the floating button over any app.")
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, n)
    }

    // ---- the floating button ----
    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun addBubble() {
        val v = BubbleView(this)
        val dm = resources.displayMetrics
        val p = WindowManager.LayoutParams(
            v.sizePx, v.sizePx, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX.takeIf { it >= 0 } ?: (dm.widthPixels - v.sizePx - 12)
            y = prefs.bubbleY.takeIf { it >= 0 } ?: (dm.heightPixels / 3)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longPressed = false
        val longPress = Runnable { if (!dragging) { longPressed = true; v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); scan(null, regionFirst = true) } }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false; longPressed = false; view.postDelayed(longPress, 550) }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 14 || abs(e.rawY - downY) > 14) { dragging = true; view.removeCallbacks(longPress) }
                    if (dragging) {
                        p.x = (startX + (e.rawX - downX)).toInt().coerceIn(0, dm.widthPixels - v.sizePx)
                        p.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, dm.heightPixels - v.sizePx)
                        try { wm.updateViewLayout(view, p) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (dragging) { prefs.bubbleX = p.x; prefs.bubbleY = p.y }
                    else if (!longPressed) scan(null)
                }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
            }
            true
        }
        wm.addView(v, p); bubble = v; bubbleParams = p
    }

    // ---- capture and read ----
    /** Reads the whole screen, or only `region` (screen pixels). `regionFirst` opens the layer straight in "Select area" mode. */
    private fun scan(region: android.graphics.Rect?, regionFirst: Boolean = false) {
        if (job?.isActive == true) return
        val cap = capturer ?: return
        if (regionFirst) { showLens(lastPage ?: OcrPage(emptyList(), "none", 0, 0), null, startInRegion = true); return }
        job = scope.launch {
            bubble?.busy = true
            removeLens()
            bubble?.visibility = View.INVISIBLE
            delay(200)                                  // let our own layers leave the screen before the shot
            val shot = cap.capture()
            bubble?.visibility = View.VISIBLE
            fun fail(msg: String) { bubble?.busy = false; Toast.makeText(this@OverlayService, msg, Toast.LENGTH_LONG).show() }
            if (shot == null) return@launch fail("Could not capture the screen. Try again.")
            if (ScreenCapturer.looksBlocked(shot)) return@launch fail("This app does not allow screen capture.")
            val lang = settings.settings.first().targetLang
            lastLang = lang; lastApp = ForegroundApp.current
            val crop: Bitmap = region?.let {
                val l = it.left.coerceIn(0, shot.width - 2); val t = it.top.coerceIn(0, shot.height - 2)
                Bitmap.createBitmap(shot, l, t, (it.right.coerceAtMost(shot.width) - l).coerceAtLeast(1), (it.bottom.coerceAtMost(shot.height) - t).coerceAtLeast(1))
            } ?: shot
            val key = withContext(Dispatchers.Default) { OcrCache.key(crop, lang, "v1") }
            val page = cache.get(key) ?: when (val o = withContext(Dispatchers.Default) { engine.recognize(crop, lang, prefs.aiFallback) }) {
                is OcrOutcome.Ok -> o.page.also { cache.put(key, it) }
                is OcrOutcome.Failed -> { bubble?.busy = false; showLens(OcrPage(emptyList(), "none", 0, 0), o.message); return@launch }
            }
            bubble?.busy = false
            val placed = if (region != null) OcrGeometry.shift(page, region.left.coerceAtLeast(0), region.top.coerceAtLeast(0)) else page
            lastPage = placed; lastKey = key
            showLens(placed, if (placed.blocks.isEmpty()) "No text found. Try Select area." else null)
        }
    }

    // ---- the lens layer ----
    private fun showLens(page: OcrPage, status: String?, startInRegion: Boolean = false) {
        removeLens()
        val v = LensView(this, page,
            onPick = { block, index, offset -> openLookup(block, index, offset) },
            onRegion = { l, t, r, b -> removeLens(); scan(android.graphics.Rect(l, t, r, b)) },
            onClose = { removeLens() })
        v.status = status
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, p); lens = v
        if (startInRegion) v.enterRegionMode()
    }

    private fun removeLens() {
        lens?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        lens = null
    }

    private fun openLookup(block: OcrBlock, index: Int, offset: Int) {
        removeLens()
        startActivity(Intent(this, LookupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(LookupActivity.EXTRA_TEXT, block.text).putExtra(LookupActivity.EXTRA_OFFSET, offset)
            .putExtra(LookupActivity.EXTRA_LANG, lastLang).putExtra(LookupActivity.EXTRA_PACKAGE, lastApp)
            .putExtra(LookupActivity.EXTRA_PAGE, lastKey).putExtra(LookupActivity.EXTRA_BLOCK, index))
    }

    companion object {
        const val ACTION_START = "app.fluvour.ocr.START"
        const val ACTION_STOP = "app.fluvour.ocr.STOP"
        const val ACTION_RESHOW = "app.fluvour.ocr.RESHOW"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "ocr_overlay"
        private const val NOTIFICATION_ID = 4201

        private val _running = MutableStateFlow(false)
        /** True while the floating button is on screen. */
        val running: MutableStateFlow<Boolean> get() = _running
        val isRunning: StateFlow<Boolean> get() = _running

        fun startIntent(ctx: Context, resultCode: Int, data: Intent) =
            Intent(ctx, OverlayService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_STOP)) }
        /** Brings the outlined text back after the lookup sheet closes, so several words can be looked up from one capture. */
        fun reshow(ctx: Context) { if (_running.value) ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_RESHOW)) }
    }
}
