package app.fluvour.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.data.CardRepository
import app.fluvour.reading.HeisigStudyRepository
import app.fluvour.reading.KanjiData
import app.fluvour.reading.KanjiRepository
import app.fluvour.reading.LevelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** How well you know a subtitle word. */
enum class WordKnow { KNOWN, LEARNING, KANJI_KNOWN, NEW }

/** What the subtitle underlines mean. */
enum class ColorMode(val label: String) {
    OFF("Colors off"), KNOW("Colors: what you know"), JLPT("Colors: JLPT level"), GRADE("Colors: kanji grade")
}

/** One kind of underline. [style]: 0 solid, 1 dotted, 2 dashed. Okabe-Ito and viridis colours, plus line width and style, so colour is never the only cue. */
data class Mark(val label: String, val light: Color, val dark: Color, val widthDp: Float, val style: Int)

/** Everything the subtitle colours need: your cards and your Heisig kanji. */
data class KnowSets(val known: Set<String>, val learning: Set<String>, val kanji: Set<String>) {
    fun of(surface: String, lemma: String?, ja: Boolean): WordKnow {
        val s = surface.trim().lowercase(); val l = lemma?.trim()?.lowercase()
        if (s in known || (l != null && l in known)) return WordKnow.KNOWN
        if (s in learning || (l != null && l in learning)) return WordKnow.LEARNING
        if (ja && kanji.isNotEmpty()) {
            val han = KanjiData.hanChars(lemma ?: surface, 12)
            if (han.isNotEmpty() && han.all { it in kanji }) return WordKnow.KANJI_KNOWN
        }
        return WordKnow.NEW
    }
    companion object { val EMPTY = KnowSets(emptySet(), emptySet(), emptySet()) }
}

object Marks {
    val KNOW = mapOf(
        WordKnow.KNOWN to Mark("Known", Color(0xFF0072B2), Color(0xFF56B4E9), 1.5f, 0),
        WordKnow.LEARNING to Mark("Learning", Color(0xFFD08400), Color(0xFFF0E442), 2f, 1),
        WordKnow.KANJI_KNOWN to Mark("Kanji known", Color(0xFF009E73), Color(0xFF3FD2A5), 2f, 2),
        WordKnow.NEW to Mark("New", Color(0xFFD55E00), Color(0xFFFF8A57), 3f, 0)
    )
    // Five steps, easiest to hardest. Harder = darker on light themes, brighter on dark themes, and a thicker line.
    private val steps = listOf(
        Triple(Color(0xFF9CCB1F), Color(0xFF3B7DBF), 1.5f),
        Triple(Color(0xFF4DB85A), Color(0xFF21908C), 2f),
        Triple(Color(0xFF21908C), Color(0xFF5DC863), 2.5f),
        Triple(Color(0xFF3B528B), Color(0xFFB5DE2B), 3f),
        Triple(Color(0xFF440154), Color(0xFFFDE725), 3.5f)
    )
    private fun step(i: Int, label: String) = Mark(label, steps[i].first, steps[i].second, steps[i].third, 0)
    val JLPT = listOf(step(0, "N5"), step(1, "N4"), step(2, "N3"), step(3, "N2"), step(4, "N1"))
    val GRADE = listOf(step(0, "Grade 1-2"), step(1, "Grade 3-4"), step(2, "Grade 5-6"), step(3, "Junior high"), step(4, "Rarer"))

    fun legend(mode: ColorMode, ja: Boolean): List<Mark> = when (mode) {
        ColorMode.KNOW -> listOfNotNull(KNOW[WordKnow.KNOWN], KNOW[WordKnow.LEARNING], if (ja) KNOW[WordKnow.KANJI_KNOWN] else null, KNOW[WordKnow.NEW])
        ColorMode.JLPT -> JLPT
        ColorMode.GRADE -> GRADE
        ColorMode.OFF -> emptyList()
    }
}

/** Works out the underline for one word. Words with no entry in the chosen list get null (no underline). */
class WordColorer(
    val mode: ColorMode, private val ja: Boolean, private val know: KnowSets,
    private val level: (String, String?) -> String?, private val grade: (String) -> Int?
) {
    fun mark(surface: String, lemma: String?): Mark? = when (mode) {
        ColorMode.OFF -> null
        ColorMode.KNOW -> Marks.KNOW[know.of(surface, lemma, ja)]
        ColorMode.JLPT -> level(surface, lemma)?.let { code ->
            if (code.length == 2 && code[0] == 'N' && code[1] in '1'..'5') Marks.JLPT[5 - (code[1] - '0')] else null
        }
        ColorMode.GRADE -> {
            val g = KanjiData.hanChars(lemma ?: surface, 12).mapNotNull { grade(it) }.maxOrNull()
            when {
                g == null -> null
                g <= 2 -> Marks.GRADE[0]
                g <= 4 -> Marks.GRADE[1]
                g <= 6 -> Marks.GRADE[2]
                g == 8 -> Marks.GRADE[3]
                else -> Marks.GRADE[4]
            }
        }
    }
}

@HiltViewModel
class WordColorViewModel @Inject constructor(
    @ApplicationContext ctx: Context,
    private val cards: CardRepository,
    private val study: HeisigStudyRepository,
    private val levels: LevelRepository,
    private val kanji: KanjiRepository
) : ViewModel() {
    private val prefs = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        prefs.getString("color_mode", null)?.let { n -> ColorMode.values().firstOrNull { it.name == n } }
            ?: if (prefs.getBoolean("word_colors", true)) ColorMode.KNOW else ColorMode.OFF
    )
    val mode: StateFlow<ColorMode> = _mode.asStateFlow()

    private val _furigana = MutableStateFlow(prefs.getBoolean("furigana", false))
    val furigana: StateFlow<Boolean> = _furigana.asStateFlow()
    fun toggleFurigana() { val v = !_furigana.value; _furigana.value = v; prefs.edit().putBoolean("furigana", v).apply() }

    private val _translation = MutableStateFlow(prefs.getBoolean("translation", false))
    val translation: StateFlow<Boolean> = _translation.asStateFlow()
    fun toggleTranslation() { val v = !_translation.value; _translation.value = v; prefs.edit().putBoolean("translation", v).apply() }

    /** Off, what you know, then (Japanese only) JLPT level and kanji grade, and round again. */
    fun cycle(ja: Boolean) {
        val order = if (ja) ColorMode.values().toList() else listOf(ColorMode.OFF, ColorMode.KNOW)
        val next = order[(order.indexOf(_mode.value) + 1).mod(order.size)]
        _mode.value = next
        prefs.edit().putString("color_mode", next.name).apply()
    }

    // Cached per language so a rebuilt Watch screen starts with the last word lists instead of empty ones (no colour pop-in).
    private val knownCache = HashMap<String, StateFlow<List<String>>>()
    private val learnCache = HashMap<String, StateFlow<List<String>>>()
    fun known(lang: String): StateFlow<List<String>> = knownCache.getOrPut(lang) { cards.observeKnownWords(lang).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()) }
    fun learning(lang: String): StateFlow<List<String>> = learnCache.getOrPut(lang) { cards.observeLearningWords(lang).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()) }

    @Volatile private var heisigCache: Set<String>? = null
    fun heisigCached(): Set<String> = heisigCache ?: emptySet()
    suspend fun heisigKnown(): Set<String> = study.known().also { heisigCache = it }

    private val warmed = HashSet<String>()
    fun isWarm(lang: String): Boolean = synchronized(warmed) { lang in warmed }
    /** Loads the level and kanji tables so [level] and [grade] answer instantly. */
    suspend fun warm(lang: String) { levels.warm(lang); if (lang == "ja") kanji.warm(); synchronized(warmed) { warmed.add(lang) } }
    fun level(lang: String, surface: String, lemma: String?): String? = levels.cached(lang, lemma, surface)?.code
    fun grade(ch: String): Int? = kanji.gradeOf(ch)
}
