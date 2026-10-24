package app.fluvour.ocr

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional: knows which app is on screen so a mined sentence can say where it came from ("Mihon", "YouTube").
 * It only listens for "a window came to the front" events and keeps the package name. It declares no permission to read screen content.
 */
class ForegroundAppService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED) return
        ForegroundApp.current = pkg
    }
    override fun onInterrupt() {}

    private companion object {
        val IGNORED = setOf("com.android.systemui", "android")
    }
}

object ForegroundApp {
    /** Package name of the app last seen in front (not Fluvour, not the system UI); null when the accessibility service is off. */
    @Volatile var current: String? = null

    /** The app's name as the user knows it; the package name when Android will not say. */
    fun label(context: Context, pkg: String?): String? {
        if (pkg == null) return null
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    fun isEnabled(context: Context): Boolean {
        val me = ComponentName(context, ForegroundAppService::class.java).flattenToString()
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
