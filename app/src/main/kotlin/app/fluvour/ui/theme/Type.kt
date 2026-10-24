package app.fluvour.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.fluvour.R

/**
 * Fonts from the finalized UI: Figtree (sans, with Noto Sans Thai Looped as the Thai fallback), Newsreader (serif),
 * Noto Serif Thai for Thai target text. Files live in res/font and are variable fonts (weight axis).
 * Japanese uses the system serif (Android's CJK serif). Noto Serif JP is 13 MB, so it is not bundled; see docs/UI.md.
 */
@OptIn(ExperimentalTextApi::class)
object FluvourFonts {
    private fun f(res: Int, w: Int, style: FontStyle = FontStyle.Normal) =
        Font(res, FontWeight(w), style, variationSettings = FontVariation.Settings(FontVariation.weight(w)))

    val Sans: FontFamily by lazy {
        FontFamily(f(R.font.figtree, 400), f(R.font.figtree, 500), f(R.font.figtree, 700),
            f(R.font.noto_sans_thai_looped, 400), f(R.font.noto_sans_thai_looped, 500), f(R.font.noto_sans_thai_looped, 700))
    }
    val Serif: FontFamily by lazy {
        FontFamily(f(R.font.newsreader, 400), f(R.font.newsreader, 500), f(R.font.newsreader_italic, 400, FontStyle.Italic))
    }
    val ThaiSerif: FontFamily by lazy {
        FontFamily(f(R.font.noto_serif_thai, 400), f(R.font.noto_serif_thai, 500))
    }

    /** Font for text written in the language being learned (card words, article text, captions). */
    fun target(lang: String): FontFamily = when (lang) {
        "th" -> ThaiSerif
        "ja", "zh", "ko" -> FontFamily.Serif
        else -> Serif
    }

    fun typography(): Typography {
        val d = Typography()
        return d.copy(
            displayLarge = d.displayLarge.copy(fontFamily = Sans), displayMedium = d.displayMedium.copy(fontFamily = Sans),
            displaySmall = d.displaySmall.copy(fontFamily = Sans), headlineLarge = d.headlineLarge.copy(fontFamily = Sans),
            headlineMedium = d.headlineMedium.copy(fontFamily = Sans), headlineSmall = d.headlineSmall.copy(fontFamily = Sans),
            titleLarge = d.titleLarge.copy(fontFamily = Sans), titleMedium = d.titleMedium.copy(fontFamily = Sans),
            titleSmall = d.titleSmall.copy(fontFamily = Sans), bodyLarge = d.bodyLarge.copy(fontFamily = Sans),
            bodyMedium = d.bodyMedium.copy(fontFamily = Sans), bodySmall = d.bodySmall.copy(fontFamily = Sans),
            labelLarge = d.labelLarge.copy(fontFamily = Sans), labelMedium = d.labelMedium.copy(fontFamily = Sans),
            labelSmall = d.labelSmall.copy(fontFamily = Sans)
        )
    }
}
