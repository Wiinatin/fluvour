package app.fluvour.ocr

import android.content.Context

/** Small settings for screen lookup, kept in plain SharedPreferences so the overlay service can read them without the app UI. */
class OcrPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("fluvour_ocr", Context.MODE_PRIVATE)

    var bubbleX: Int get() = sp.getInt("bx", -1); set(v) { sp.edit().putInt("bx", v).apply() }
    var bubbleY: Int get() = sp.getInt("by", -1); set(v) { sp.edit().putInt("by", v).apply() }
    fun resetBubble() { sp.edit().remove("bx").remove("by").apply() }

    /** When the on-device reader finds nothing (or cannot read the language), send the screenshot to Gemini. Off = the picture never leaves the phone. */
    var aiFallback: Boolean get() = sp.getBoolean("ai_fallback", true); set(v) { sp.edit().putBoolean("ai_fallback", v).apply() }

    /** What the user is reading in this app (series and chapter), remembered per app so the next lookup starts with it. */
    fun note(pkg: String?): String = sp.getString("note_${pkg ?: ""}", "").orEmpty()
    fun setNote(pkg: String?, value: String) { sp.edit().putString("note_${pkg ?: ""}", value.trim()).apply() }
}
