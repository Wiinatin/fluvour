package app.fluvour.ocr

import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/**
 * Remembers what was read from a capture so the same page is only processed once (free AI tiers have limits, and ML Kit takes a moment).
 * One small JSON file per page in the app's cache folder: text and boxes only, never the picture. The oldest files go first.
 */
class OcrCache(parent: File, private val maxFiles: Int = 300) {
    private val dir = File(parent, "ocr").apply { mkdirs() }

    fun get(key: String): OcrPage? = runCatching {
        val f = File(dir, "$key.json")
        if (!f.exists()) null else { f.setLastModified(System.currentTimeMillis()); OcrJson.parse(f.readText()) }
    }.getOrNull()

    fun put(key: String, page: OcrPage) {
        runCatching {
            File(dir, "$key.json").writeText(OcrJson.toJson(page))
            val files = dir.listFiles() ?: return
            if (files.size > maxFiles) files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
        }
    }

    companion object {
        /**
         * A key for "this page in this language": the capture shrunk to 48x48 and blurred to 16 grey levels, so tiny differences
         * (a clock, a fading animation) still give the same key. A scrolled page is a different page.
         */
        fun key(bitmap: Bitmap, lang: String, engine: String): String {
            val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val px = IntArray(48 * 48)
            small.getPixels(px, 0, 48, 0, 0, 48, 48)
            val md = MessageDigest.getInstance("SHA-256")
            md.update("$lang|$engine|${bitmap.width}x${bitmap.height}|".toByteArray())
            for (p in px) md.update((((((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10) shr 4).toByte())
            if (small !== bitmap) small.recycle()
            return md.digest().joinToString("") { "%02x".format(it) }.take(40)
        }
    }
}
