package app.fluvour.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ThemeId { Calm, Playful, Warm }     // Warm = the finalized Fluvour UI. Doodle and Game from the old mockup are still only planned.
enum class ThemeMode { System, Light, Dark }

/** Every colour a screen may use. Screens never hard-code colours; they read these tokens. */
@Immutable data class FluvourColors(
    val paper: Color,        // screen background
    val card: Color, val ink: Color, val mute: Color, val line: Color, val sky: Color, val page: Color,
    val nav: Color, val navContent: Color, val navActive: Color,
    val accent: Color,       // accent (underline, waveform, highlights). In Warm it follows the language being learned.
    val highlight: Color,    // butter, for marked words
    val primary: Color, val onPrimary: Color,   // main buttons
    val tint: Color = Color(0xFFEBD3B8),        // soft wash behind card faces and hero areas (language colour in Warm)
    val scrim: Color = Color(0x591F2B3A)        // dimmer behind sheets
)

@Immutable data class FluvourShapes(val card: Dp, val button: Dp, val sheet: Dp = 20.dp)

/** Structural switches: things that differ in layout, not just colour. */
@Immutable data class FluvourStyle(
    val heroHeader: Boolean, val cardShadow: Dp,
    val flat: Boolean = false,              // hairline borders instead of shadows
    val languageAccent: Boolean = false,    // accent and tint come from the target language (see LanguagePalettes)
    val designFonts: Boolean = false        // Figtree, Newsreader and the Thai serif from res/font
)

@Immutable data class ThemeSpec(
    val id: ThemeId, val label: String, val blurb: String,
    val light: FluvourColors, val dark: FluvourColors,
    val shapes: FluvourShapes, val style: FluvourStyle
)

@Immutable data class ResolvedTheme(val spec: ThemeSpec, val colors: FluvourColors, val dark: Boolean, val lang: String)

val LocalFluvour = staticCompositionLocalOf<ResolvedTheme> { error("Wrap content in FluvourTheme") }

object Fluvour {
    val colors: FluvourColors @Composable @ReadOnlyComposable get() = LocalFluvour.current.colors
    val shapes: FluvourShapes @Composable @ReadOnlyComposable get() = LocalFluvour.current.spec.shapes
    val style: FluvourStyle @Composable @ReadOnlyComposable get() = LocalFluvour.current.spec.style
    /** Code of the language being learned, for picking the right font for target-language text. */
    val lang: String @Composable @ReadOnlyComposable get() = LocalFluvour.current.lang
    val dark: Boolean @Composable @ReadOnlyComposable get() = LocalFluvour.current.dark
}

@Composable
fun FluvourTheme(spec: ThemeSpec, mode: ThemeMode, lang: String = "ja", content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val base = if (dark) spec.dark else spec.light
    val c = if (spec.style.languageAccent) {
        val p = LanguagePalettes.of(lang, dark)
        base.copy(accent = p.accent, tint = p.tint, primary = p.accent, onPrimary = if (dark) base.paper else Color.White)
    } else base
    // Feed Material components too, so dialogs, text fields and switches pick up the theme.
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary, onPrimary = c.onPrimary, background = c.paper, onBackground = c.ink,
        surface = c.card, onSurface = c.ink, surfaceVariant = c.sky, onSurfaceVariant = c.mute,
        outline = c.line, outlineVariant = c.line, secondaryContainer = c.sky, onSecondaryContainer = c.ink,
        scrim = c.scrim
    )
    val typography = if (spec.style.designFonts) FluvourFonts.typography() else MaterialTheme.typography
    CompositionLocalProvider(LocalFluvour provides ResolvedTheme(spec, c, dark, lang)) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** One accent per language, from the design's data-lang palettes. Languages without their own row borrow the nearest family. */
data class LanguagePalette(val accent: Color, val tint: Color)

object LanguagePalettes {
    private fun c(hex: Long) = Color(0xFF000000 or hex)
    private val ja = LanguagePalette(c(0xA94B28), c(0xEBD3B8))   // terracotta
    private val th = LanguagePalette(c(0x4F6B3A), c(0xE3E8D3))   // moss
    private val en = LanguagePalette(c(0x3E5C7A), c(0xD5E0E8))   // slate blue
    // The design is light only. These dark values are an addition: same hues, lifted for contrast on a dark paper.
    private val jaD = LanguagePalette(c(0xE0875F), c(0x3A2A20))
    private val thD = LanguagePalette(c(0x9DB87F), c(0x252B1E))
    private val enD = LanguagePalette(c(0x8FB0D0), c(0x1E2831))
    // zh, ko, es, fr, de, vi, id: accents from the language marks design (accent = the board's --ac, tint = il2 at 50% toward white).
    private val zh = LanguagePalette(c(0xB3302B), c(0xBFD6CC))
    private val zhD = LanguagePalette(c(0xD18380), c(0x19231F))   // dark values: an addition, same hue lifted
    private val ko = LanguagePalette(c(0x2A7570), c(0xD2E7E2))
    private val koD = LanguagePalette(c(0x7FACA9), c(0x212927))   // dark values: an addition, same hue lifted
    private val es = LanguagePalette(c(0x99600F), c(0xF0D9AC))
    private val esD = LanguagePalette(c(0xC2A06F), c(0x2D2412))   // dark values: an addition, same hue lifted
    private val fr = LanguagePalette(c(0x6D5CA6), c(0xDBD5EC))
    private val frD = LanguagePalette(c(0xA79DCA), c(0x25222B))   // dark values: an addition, same hue lifted
    private val de = LanguagePalette(c(0x2F5F4B), c(0xCEDDD2))
    private val deD = LanguagePalette(c(0x829F93), c(0x1F2521))   // dark values: an addition, same hue lifted
    private val vi = LanguagePalette(c(0xA8405F), c(0xF4D3DC))
    private val viD = LanguagePalette(c(0xCB8C9F), c(0x2E2125))   // dark values: an addition, same hue lifted
    private val id = LanguagePalette(c(0x8C5434), c(0xE8D0BA))
    private val idD = LanguagePalette(c(0xBA9885), c(0x2A2017))   // dark values: an addition, same hue lifted
    fun of(code: String, dark: Boolean): LanguagePalette = when (code) {
        "zh" -> if (dark) zhD else zh
        "ko" -> if (dark) koD else ko
        "es" -> if (dark) esD else es
        "fr" -> if (dark) frD else fr
        "de" -> if (dark) deD else de
        "vi" -> if (dark) viD else vi
        "id" -> if (dark) idD else id
        "th" -> if (dark) thD else th
        "en" -> if (dark) enD else en
        else -> if (dark) jaD else ja      // ja
    }
}
