package app.fluvour.ui

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.fluvour.ui.theme.Fluvour
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Lifecycle for a ComposeView that lives in a WindowManager window instead of an Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    fun start() { ssc.performAttach(); ssc.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
    fun stop() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }
}

/**
 * The mini window drawn over other apps (needs "Display over other apps"). Root.kt shows it while the app is in the background
 * and the video is in the mini state, and hides it when the app comes back or the window's X / hide button is used.
 */
class MiniOverlay(private val ctx: Context, private val vm: PlayerViewModel) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = ctx.resources.displayMetrics
    private val w = (MiniW.value * dm.density).roundToInt()
    private val h = (MiniH.value * dm.density).roundToInt()
    private val minX = (10 * dm.density).roundToInt()
    private val minY = (48 * dm.density).roundToInt()           // clear of the status bar
    private val maxX get() = (dm.widthPixels - w - minX).coerceAtLeast(minX)
    private val maxY get() = (dm.heightPixels - h - 80 * dm.density).roundToInt().coerceAtLeast(minY)   // clear of the nav bar
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var lp: WindowManager.LayoutParams? = null
    private var startX = 0; private var startY = 0
    private var anim: ValueAnimator? = null

    /** Set by Root.kt: the themed window content. */
    var content: @Composable () -> Unit = {}

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val o = OverlayOwner().also { it.start() }
        val params = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (vm.miniRight) maxX else minX
            y = (minY + (maxY - minY) * vm.miniYFrac.coerceIn(0f, 1f)).roundToInt()
        }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o); setViewTreeViewModelStoreOwner(o)
            setContent { content() }
        }
        try { wm.addView(v, params) } catch (e: Exception) { o.stop(); return }
        view = v; owner = o; lp = params
    }

    fun hide() {
        anim?.cancel(); anim = null
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        owner?.stop(); view = null; owner = null; lp = null
    }

    internal fun begin() { anim?.cancel(); startX = lp?.x ?: 0; startY = lp?.y ?: 0 }
    internal fun dragTo(dx: Float, dy: Float) {
        val p = lp ?: return; val v = view ?: return
        p.x = (startX + dx.roundToInt()).coerceIn(0, dm.widthPixels - w)
        p.y = (startY + dy.roundToInt()).coerceIn(minY, maxY)
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }
    /** Finger lifted after a drag: remember the spot and slide to the nearest side. */
    internal fun settle() {
        val p = lp ?: return; val v = view ?: return
        val right = p.x + w / 2 > dm.widthPixels / 2
        vm.miniRight = right; vm.miniYFrac = if (maxY > minY) ((p.y - minY).toFloat() / (maxY - minY)).coerceIn(0f, 1f) else 0f
        anim = ValueAnimator.ofInt(p.x, if (right) maxX else minX).apply {
            duration = 180
            addUpdateListener { p.x = it.animatedValue as Int; try { wm.updateViewLayout(v, p) } catch (_: Exception) {} }
            start()
        }
    }
    /** Tap on the picture or the expand button: bring the app forward and open Watch. */
    fun returnToApp() {
        vm.openWatch.tryEmit(Unit)
        ctx.startActivity(Intent(ctx, app.fluvour.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MiniOverlayWindow(vm: PlayerViewModel, overlay: MiniOverlay) {
    val c = Fluvour.colors
    val playing by vm.playing.collectAsStateWithLifecycle()
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    val slop = ViewConfiguration.get(androidx.compose.ui.platform.LocalContext.current).scaledTouchSlop
    var sx = 0f; var sy = 0f; var moved = false
    Box(Modifier.size(MiniW, MiniH).clip(RoundedCornerShape(14.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(14.dp))) {
        MiniWindowBody(vm, audioOnly, playing, { overlay.returnToApp() }, Modifier.pointerInteropFilter { e ->
            // Raw screen coordinates, because the window itself moves under the finger.
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; moved = false; overlay.begin() }
                MotionEvent.ACTION_MOVE -> { val dx = e.rawX - sx; val dy = e.rawY - sy; if (moved || hypot(dx, dy) > slop) { moved = true; overlay.dragTo(dx, dy) } }
                MotionEvent.ACTION_UP -> if (moved) overlay.settle() else overlay.returnToApp()
            }
            true
        })
    }
}
