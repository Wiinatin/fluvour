package app.fluvour.reading

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stroke outlines for one kanji, in writing order. [d] are SVG path strings on a 109 x 109 grid; [nums] are where each stroke number sits. */
data class KanjiStrokes(val d: List<String>, val nums: List<Pair<Float, Float>>)

object StrokeData {
    const val CREDIT = "Stroke order: KanjiVG, © Ulrich Apel, CC BY-SA 3.0 (kanjivg.tagaini.net)."

    /** One line of strokes.tsv: kanji, paths joined by |, number positions "x,y" joined by |. */
    fun parseLine(line: String): KanjiStrokes? {
        val p = line.split('\t')
        if (p.size < 3) return null
        val d = p[1].split('|').filter { it.isNotBlank() }
        val nums = p[2].split('|').mapNotNull { s ->
            val xy = s.split(',')
            val x = xy.getOrNull(0)?.toFloatOrNull(); val y = xy.getOrNull(1)?.toFloatOrNull()
            if (x != null && y != null) x to y else null
        }
        return if (d.isEmpty()) null else KanjiStrokes(d, nums)
    }
}

/** Looks a kanji up in assets/kanji/strokes.tsv (built from KanjiVG). Scans the file instead of keeping it all in memory. */
@Singleton
class StrokeRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val cache = HashMap<String, KanjiStrokes?>()

    suspend fun strokes(ch: String): KanjiStrokes? = withContext(Dispatchers.IO) {
        synchronized(cache) { if (cache.containsKey(ch)) return@withContext cache[ch] }
        val found = runCatching {
            ctx.assets.open("kanji/strokes.tsv").bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith(ch + "\t") }?.let { StrokeData.parseLine(it) }
            }
        }.getOrNull()
        synchronized(cache) { cache[ch] = found }
        found
    }
}
