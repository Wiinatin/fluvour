package app.fluvour.lyrics

import app.fluvour.media.SubLine
import java.text.Normalizer
import kotlin.math.abs

/**
 * Pure helpers for the lyrics finder: work out artist and title from a video title, compare texts, check the script of a language,
 * and find how far lyrics are shifted from the video's own captions. No Android, no network.
 */
object LyricsText {
    /** What a video title says about the song. [alt] is the same title read the other way round ("Title - Artist"). */
    class Guess(val rawTitle: String, val artist: String, val title: String, val artistFromTitle: Boolean) {
        var alt: Guess? = null
        fun all(): List<Guess> = listOfNotNull(this, alt)
    }

    private val NOISE = Regex("""(?i)official|music\s*video|\bm/?v\b|lyrics?|audio|visuali[sz]er|\bvideo\b|\bhd\b|\bhq\b|\b4k\b|1080p|remaster|full\s*(song|version)|\bsubs?\b|subtitles?|color\s*coded|\bcc\b|公式|歌词|歌詞|字幕|フルver""")
    private val BRACKET = Regex("""[(\[{（［【][^)\]}）］】]*[)\]}）］】]""")
    private val FEAT = Regex("""(?i)\s*(feat\.?|ft\.?|featuring)\s+.*$""")
    private val SPLIT = Regex("""\s+[-–—|:]\s+|\s*[|｜]\s*""")
    private val JP_QUOTE = Regex("""^(.*?)\s*[「『]([^」』]+)[」』]""")
    private val ARTIST_TAIL = Regex("""(?i)\s*(-\s*topic|vevo|official( channel)?|channel|records?|music|公式チャンネル|公式)\s*$""")
    private val VERSION = Regex("""(?i)\b(live|remix|cover|karaoke|instrumental|acoustic|nightcore|sped\s*up|slowed|reverb|8d|piano|unplugged|demo)\b|ライブ|カバー|リミックス|カラオケ""")

    fun guess(rawTitle: String, uploader: String?): Guess {
        val t0 = Normalizer.normalize(rawTitle, Normalizer.Form.NFKC)
        val jp = JP_QUOTE.find(t0)
        if (jp != null && jp.groupValues[2].isNotBlank()) {
            val a = cleanArtist(jp.groupValues[1].ifBlank { uploader.orEmpty() })
            return Guess(rawTitle, a, tidy(jp.groupValues[2]), jp.groupValues[1].isNotBlank())
        }
        val t = stripNoise(t0)
        val parts = t.split(SPLIT).map { tidy(it) }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val g = Guess(rawTitle, cleanArtist(parts[0]), parts[1], true)
            g.alt = Guess(rawTitle, cleanArtist(parts[1]), parts[0], true)
            return g
        }
        return Guess(rawTitle, cleanArtist(uploader.orEmpty()), tidy(t), false)
    }

    /** Removes bracketed noise words (Official MV, Lyric Video, 4K ...), then every bracket group and "feat." tails. */
    fun stripNoise(s: String): String {
        var t = BRACKET.replace(s) { m -> if (NOISE.containsMatchIn(m.value)) "" else m.value }
        t = NOISE.replace(t, "")
        return t.trim()
    }

    private fun tidy(s: String): String = FEAT.replace(BRACKET.replace(s, " "), "").replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '|', ':')

    fun cleanArtist(s: String): String = tidy(ARTIST_TAIL.replace(s.trim(), ""))

    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (c in n) if (Character.isLetterOrDigit(c)) sb.append(c)
        return sb.toString()
    }

    private fun bigrams(n: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        if (n.length == 1) { m[n] = 1; return m }
        for (i in 0 until n.length - 1) m.merge(n.substring(i, i + 2), 1, Int::plus)
        return m
    }

    /** 0..1: same text 1.0, one inside the other 0.9, otherwise the overlap of letter pairs (Dice). */
    fun sim(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (minOf(x.length, y.length) >= 3 && (x.contains(y) || y.contains(x))) return 0.9
        val bx = bigrams(x); val by = bigrams(y)
        var inter = 0
        for ((k, v) in bx) inter += minOf(v, by[k] ?: 0)
        return 2.0 * inter / (bx.values.sum() + by.values.sum())
    }

    /** Live, remix, cover and similar tags must be the same on both sides, or it is a different recording. */
    fun sameVersion(a: String, b: String): Boolean = tags(a) == tags(b)
    private fun tags(s: String): Set<String> = VERSION.findAll(s).map { it.value.lowercase().replace(Regex("\\s+"), "") }.toSet()

    /**
     * Does a database entry fit this video? The title must look alike and the version must match. When the artist came from the video
     * title it must be near the entry's artist too; when it came from the channel name (often a label or a lyrics channel) the title
     * and the length have to be a closer fit instead.
     */
    fun accepts(candTitle: String, candArtist: String, candDurSec: Int, g: Guess, videoSec: Int): Boolean {
        if (!sameVersion(g.rawTitle, candTitle)) return false
        val ts = sim(stripNoise(candTitle), g.title)
        if (ts < 0.6) return false
        val delta = if (videoSec > 0 && candDurSec > 0) abs(candDurSec - videoSec) else 0
        val artistOk = g.artistFromTitle || sim(candArtist, g.artist) >= 0.5
        return if (artistOk) delta <= 30 else (ts >= 0.85 && delta <= 8 && videoSec > 0 && candDurSec > 0)
    }

    fun delta(candDurSec: Int, videoSec: Int): Int = if (videoSec > 0 && candDurSec > 0) abs(candDurSec - videoSec) else 0

    /** Is this text in the script the learner is studying? (Japanese needs kana, Chinese must not have kana, Latin languages mostly Latin.) */
    fun scriptFits(lang: String, lines: List<SubLine>): Boolean {
        var letters = 0; var latin = 0; var han = 0; var kana = 0; var hangul = 0; var thai = 0; var arabic = 0
        for (l in lines.take(30)) for (ch in l.text) {
            if (!Character.isLetter(ch)) continue
            letters++
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in 'À'..'ɏ' || ch in 'Ḁ'..'ỿ' -> latin++
                ch in '぀'..'ヿ' -> kana++
                ch in '一'..'鿿' -> han++
                ch in '가'..'힯' || ch in 'ᄀ'..'ᇿ' -> hangul++
                ch in '฀'..'๿' -> thai++
                ch in '؀'..'ۿ' -> arabic++
            }
        }
        if (letters < 8) return false
        fun f(n: Int) = n.toDouble() / letters
        return when (lang.substringBefore('-').lowercase()) {
            "ja" -> f(kana) >= 0.1
            "zh" -> f(han) >= 0.5 && f(kana) < 0.05
            "ko" -> f(hangul) >= 0.4
            "th" -> f(thai) >= 0.5
            "ar" -> f(arabic) >= 0.5
            else -> f(latin) >= 0.7
        }
    }

    /** The lyrics must roughly fit the video: they do not start long after it ends and do not stop far too early. */
    fun durationFits(lines: List<SubLine>, videoMs: Long): Boolean {
        if (videoMs <= 0 || lines.isEmpty()) return true
        val last = lines.last().startMs
        return last <= videoMs + 10_000 && last >= videoMs * 0.3
    }

    /** How well lyrics agree with a reference (the video's own captions): [pairs] matched lines of [sampled], and the median shift. */
    class Agreement(val pairs: Int, val sampled: Int, val offsetMs: Long, val spreadMs: Long) {
        val ratio: Double get() = if (sampled == 0) 0.0 else pairs.toDouble() / sampled
    }

    /**
     * Matches each reference line to the lyric line that contains most of its letter pairs. The shift is the median of
     * (reference start minus lyric start). Auto captions are noisy and often hold half a lyric line, so containment is used, not equality.
     */
    fun agreement(lyrics: List<SubLine>, ref: List<SubLine>): Agreement {
        val usable = ref.filter { norm(it.text).length >= 6 }
        val step = maxOf(1, usable.size / 80)
        val sample = usable.filterIndexed { i, _ -> i % step == 0 }
        val ly = lyrics.map { it to bigrams(norm(it.text)) }.filter { it.second.isNotEmpty() }
        val offsets = ArrayList<Long>()
        for (r in sample) {
            val rb = bigrams(norm(r.text)); val rTotal = rb.values.sum()
            if (rTotal < 4) continue
            var best = 0.0; var bestLine: SubLine? = null
            for ((l, lb) in ly) {
                var inter = 0
                for ((k, v) in rb) inter += minOf(v, lb[k] ?: 0)
                val s = inter.toDouble() / rTotal
                if (s > best) { best = s; bestLine = l }
            }
            if (best >= 0.6 && bestLine != null) offsets += r.startMs - bestLine.startMs
        }
        if (offsets.isEmpty()) return Agreement(0, sample.size, 0, Long.MAX_VALUE)
        offsets.sort()
        val med = offsets[offsets.size / 2]
        val devs = offsets.map { abs(it - med) }.sorted()
        return Agreement(offsets.size, sample.size, med, devs[devs.size / 2])
    }

    private fun thaiDependent(c: Char) = c == '\u0E30' || c == '\u0E31' || c in '\u0E32'..'\u0E3A' || c == '\u0E45' || c in '\u0E47'..'\u0E4E'

    /**
     * Do these lines look cut in the middle of words? True when lines start with a Thai vowel or tone mark (no Thai word does) or hold
     * a single letter. Some YouTube captions are split by time, not by meaning, and give lines like that; other sources are then better.
     */
    fun looksBroken(lines: List<SubLine>): Boolean {
        val sample = lines.take(80).filter { it.text.isNotBlank() }
        if (sample.size < 6) return false
        val bad = sample.count { l ->
            val t = l.text.trim()
            thaiDependent(t.first()) || t.count { Character.isLetter(it) } <= 1
        }
        return bad >= 2 && bad.toDouble() / sample.size >= 0.04
    }
}
