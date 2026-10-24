package app.fluvour.media

import org.json.JSONObject

/**
 * Turns subtitle text into lines, whatever the format. The format is detected from the content itself, not from the file extension.
 * Supported: WebVTT, SRT, SBV, TTML/DFXP, YouTube srv1/srv2/srv3 (XML), YouTube json3, ASS/SSA, LRC.
 */
object SubtitleParser {
    private val TAG = Regex("<[^>]+>")
    private val BRACES = Regex("\\{[^}]*\\}")
    private val SPACES = Regex("\\s+")
    private val NUM_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
    private val CUE_TIME = Regex("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
    private val SBV_TIME = Regex("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{3}),(\\d+):(\\d{2}):(\\d{2})\\.(\\d{3})\\s*$")
    private val LRC_LINE = Regex("^\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?](.*)$")
    private val XML_ITEM = Regex("<(p|text)\\b([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("([\\w:]+)=\"([^\"]*)\"")

    fun parse(body: String): List<SubLine> {
        val b = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val raw = try {
            when {
                b.startsWith("{") -> json3(b)
                b.startsWith("<") -> xml(b)
                b.contains("Dialogue:") -> ass(b)
                b.contains("-->") -> cues(b)
                b.lines().any { SBV_TIME.matches(it.trim()) } -> sbv(b)
                b.lines().any { LRC_LINE.matches(it.trim()) } -> lrc(b)
                else -> emptyList()
            }
        } catch (e: Exception) { emptyList() }
        return tidy(raw)
    }

    private fun decode(s: String): String {
        var t = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ")
        t = NUM_ENTITY.replace(t) { m -> m.groupValues[2].toIntOrNull(if (m.groupValues[1] == "x") 16 else 10)?.let { String(Character.toChars(it)) } ?: m.value }
        return t
    }

    private fun clean(s: String) = SPACES.replace(decode(TAG.replace(BRACES.replace(s, ""), "")), " ").trim()

    private fun tidy(list: List<SubLine>): List<SubLine> {
        val out = ArrayList<SubLine>()
        for (l in list.sortedBy { it.startMs }) {
            if (l.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.text == l.text && l.startMs <= last.endMs + 50) out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, l.endMs)) else out += l
        }
        return out
    }

    private fun ms(h: String?, m: String, s: String, f: String) = ((h?.toLongOrNull() ?: 0L) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.toLong()

    /** WebVTT and SRT. */
    private fun cues(body: String): List<SubLine> {
        val out = ArrayList<SubLine>()
        val rows = body.replace("\r", "").lines()
        var i = 0
        while (i < rows.size) {
            val m = CUE_TIME.find(rows[i])
            if (m == null) { i++; continue }
            val g = m.groupValues
            val start = ms(g[1].ifEmpty { null }, g[2], g[3], g[4]); val end = ms(g[5].ifEmpty { null }, g[6], g[7], g[8])
            val text = StringBuilder()
            i++
            while (i < rows.size && rows[i].isNotBlank()) { if (text.isNotEmpty()) text.append(' '); text.append(rows[i].trim()); i++ }
            out += SubLine(start, end, clean(text.toString()))
        }
        return out
    }

    /** YouTube SBV: "0:00:01.000,0:00:03.000" then the text. */
    private fun sbv(body: String): List<SubLine> {
        val out = ArrayList<SubLine>()
        val rows = body.replace("\r", "").lines()
        var i = 0
        while (i < rows.size) {
            val m = SBV_TIME.find(rows[i].trim())
            if (m == null) { i++; continue }
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4]); val end = ms(g[5], g[6], g[7], g[8])
            val text = StringBuilder()
            i++
            while (i < rows.size && rows[i].isNotBlank()) { if (text.isNotEmpty()) text.append(' '); text.append(rows[i].trim()); i++ }
            out += SubLine(start, end, clean(text.toString()))
        }
        return out
    }

    /** "00:01:02.345", "1:02.345", "62.345s" or "62.345" to milliseconds, -1 when unreadable. */
    private fun clock(t: String): Long {
        val v = t.trim()
        if (v.contains(':')) {
            val parts = v.split(':')
            val sec = parts.last().replace(',', '.').toDoubleOrNull() ?: return -1
            val min = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0L
            val hr = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0L
            return (hr * 3600 + min * 60) * 1000 + (sec * 1000).toLong()
        }
        return v.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1
    }

    /** TTML/DFXP, YouTube srv1 (seconds), srv2 and srv3 (milliseconds). */
    private fun xml(body: String): List<SubLine> {
        val out = ArrayList<SubLine>()
        XML_ITEM.findAll(body).forEach { m ->
            val a = ATTR.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            val start: Long; val end: Long
            when {
                a["begin"] != null -> { start = clock(a["begin"]!!); end = a["end"]?.let { clock(it) } ?: (a["dur"]?.let { clock(it) }?.let { d -> start + d } ?: -1) }
                a["start"] != null -> { start = clock(a["start"]!!); end = a["dur"]?.let { clock(it) }?.let { d -> start + d } ?: (start + 3000) }
                a["t"] != null -> { start = a["t"]!!.toLongOrNull() ?: -1; end = a["d"]?.toLongOrNull()?.let { d -> start + d } ?: (start + 3000) }
                else -> return@forEach
            }
            if (start < 0 || end < 0) return@forEach
            out += SubLine(start, end, clean(m.groupValues[3].replace(Regex("<br\\s*/?>"), " ")))
        }
        return out
    }

    /** YouTube json3: {"events":[{"tStartMs":..,"dDurationMs":..,"segs":[{"utf8":".."}]}]}. */
    private fun json3(body: String): List<SubLine> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<SubLine>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val segs = e.optJSONArray("segs") ?: continue
            val text = StringBuilder()
            for (j in 0 until segs.length()) text.append(segs.getJSONObject(j).optString("utf8", ""))
            val start = e.optLong("tStartMs", -1)
            if (start < 0) continue
            out += SubLine(start, start + e.optLong("dDurationMs", 3000), clean(text.toString().replace("\n", " ")))
        }
        return out
    }

    /** ASS and SSA: "Dialogue: layer,start,end,style,name,l,r,v,effect,text". */
    private fun ass(body: String): List<SubLine> {
        val out = ArrayList<SubLine>()
        body.lines().forEach { line ->
            if (!line.startsWith("Dialogue:")) return@forEach
            val f = line.substringAfter("Dialogue:").split(",", limit = 10)
            if (f.size < 10) return@forEach
            val start = clock(f[1]); val end = clock(f[2])
            if (start < 0 || end < 0) return@forEach
            out += SubLine(start, end, clean(f[9].replace("\\N", " ").replace("\\n", " ")))
        }
        return out
    }

    /** LRC: "[mm:ss.xx]text". Each line ends where the next one starts. */
    private fun lrc(body: String): List<SubLine> {
        val rows = body.lines().mapNotNull { l ->
            val m = LRC_LINE.find(l.trim()) ?: return@mapNotNull null
            val frac = m.groupValues[3]
            val fracMs = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.toLong() }
            (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + fracMs to clean(m.groupValues[4])
        }
        return rows.mapIndexed { i, (start, text) -> SubLine(start, rows.getOrNull(i + 1)?.first ?: (start + 4000), text) }
    }
}
