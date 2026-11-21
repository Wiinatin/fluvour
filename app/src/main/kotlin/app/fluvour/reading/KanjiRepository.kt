package app.fluvour.reading

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One kanji from KANJIDIC2. [jlptOld] uses the old four-level JLPT (4 = easiest, 1 = hardest). */
data class KanjiEntry(
    val ch: String, val grade: Int?, val strokes: Int?, val jlptOld: Int?, val freq: Int?,
    val on: List<String>, val kun: List<String>, val meanings: List<String>
)

data class KanjiWord(val word: String, val level: String?, val rank: Int?)

/** A kanji plus common words that contain it (from the app's own frequency list). */
data class KanjiCard(val entry: KanjiEntry, val words: List<KanjiWord>, val heisig: HeisigEntry? = null)

object KanjiData {
    const val CREDIT = "Kanji data: KANJIDIC2, © Electronic Dictionary Research and Development Group, CC BY-SA 4.0 (edrdg.org), " +
        "via jmdict-simplified. JLPT shown is the old four-level scale (4 = easiest). Common words come from the app's frequency list."

    /** Columns: kanji, grade, strokes, old JLPT, frequency, on readings (、), kun readings (、), meanings (; ). */
    fun parse(lines: Sequence<String>): Map<String, KanjiEntry> {
        val m = HashMap<String, KanjiEntry>(12_000)
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 8 || p[0].isEmpty()) continue
            fun list(s: String, sep: String) = s.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
            m[p[0]] = KanjiEntry(
                p[0], p[1].toIntOrNull(), p[2].toIntOrNull(), p[3].toIntOrNull(), p[4].toIntOrNull(),
                list(p[5], "、"), list(p[6], "、"), list(p[7], ";")
            )
        }
        return m
    }

    /** The distinct kanji in `text`, in order, at most `max`. */
    fun hanChars(text: String, max: Int = 5): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) out.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        return out.take(max)
    }

    fun gradeLabel(g: Int?): String? = when (g) {
        null -> null
        in 1..6 -> "Grade $g"
        8 -> "Junior high"
        9, 10 -> "Names"
        else -> null
    }
}

/** Loads `assets/kanji/kanjidic2.tsv` once (about 650 KB, off the main thread) and answers kanji lookups. Nothing is downloaded. */
@Singleton
class KanjiRepository @Inject constructor(@ApplicationContext private val ctx: Context, private val levels: LevelRepository, private val heisig: HeisigRepository) {
    @Volatile private var table: Map<String, KanjiEntry>? = null

    private fun load(): Map<String, KanjiEntry> = table ?: runCatching {
        ctx.assets.open("kanji/kanjidic2.tsv").bufferedReader().useLines { KanjiData.parse(it) }
    }.getOrDefault(emptyMap()).also { table = it }

    /** Loads the kanji table ahead of time so [gradeOf] can answer without waiting. */
    suspend fun warm() { withContext(Dispatchers.IO) { load() } }

    /** School grade of a kanji (1-6, 8 = junior high, 9 = names or rarer); null if the kanji is not in the table. Call [warm] first. */
    fun gradeOf(ch: String): Int? = table?.get(ch)?.let { it.grade ?: 9 }

    suspend fun entry(ch: String): KanjiEntry? = withContext(Dispatchers.IO) { load()[ch] }

    suspend fun cards(lang: String, text: String): List<KanjiCard> = withContext(Dispatchers.IO) {
        val t = load()
        KanjiData.hanChars(text).mapNotNull { ch ->
            val e = t[ch] ?: return@mapNotNull null
            val words = levels.wordsWith(lang, ch.first(), 7).filter { it.word != text }.take(6)
            KanjiCard(e, words, heisig.get(ch))
        }
    }
}
