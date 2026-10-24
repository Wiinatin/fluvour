package app.fluvour.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun c(hex: Long) = Color(0xFF000000 or hex)

/** Calm: white, ink and one blue accent. Values come from fluvour-ui-mockup-v8 (.calm and .calm.dark). */
val CalmTheme = ThemeSpec(
    id = ThemeId.Calm, label = "Calm", blurb = "Quiet, white and ink. Nothing competes for attention.",
    light = FluvourColors(
        paper = c(0xFFFFFF), card = c(0xFFFFFF), ink = c(0x262626), mute = c(0x6A6A6A), line = c(0xEAEAEA),
        sky = c(0xF1F1F1), page = c(0xEDEDED), nav = c(0xF1F1F1), navContent = c(0x6A6A6A), navActive = c(0x262626),
        accent = c(0x6C9BD8), highlight = c(0xF4E27A), primary = c(0x262626), onPrimary = c(0xFFFFFF)
    ),
    dark = FluvourColors(
        paper = c(0x121212), card = c(0x1C1C1C), ink = c(0xF2F2F2), mute = c(0xA8A8A8), line = c(0x2E2E2E),
        sky = c(0x2A2A2A), page = c(0x0A0A0A), nav = c(0x1A1A1A), navContent = c(0xA8A8A8), navActive = c(0xF2F2F2),
        accent = c(0x6C9BD8), highlight = c(0xF4E27A), primary = c(0xF2F2F2), onPrimary = c(0x121212)
    ),
    shapes = FluvourShapes(card = 20.dp, button = 28.dp),
    style = FluvourStyle(heroHeader = false, cardShadow = 3.dp)
)

/** Playful: warm paper, navy and a blue hero header. Values from the mockup's :root and .dark. */
val PlayfulTheme = ThemeSpec(
    id = ThemeId.Playful, label = "Playful", blurb = "Warm paper, navy and a bold blue header.",
    light = FluvourColors(
        paper = c(0xF4F1EA), card = c(0xFFFDF8), ink = c(0x1F2A44), mute = c(0x5B6785), line = c(0xE6E0D2),
        sky = c(0xD6E6F8), page = c(0xE6E8EC), nav = c(0x1F2A44), navContent = c(0xA9B4CE), navActive = c(0xF4F1EA),
        accent = c(0x6C9BD8), highlight = c(0xF4E27A), primary = c(0x3F6FB5), onPrimary = c(0xFFFFFF)
    ),
    dark = FluvourColors(
        paper = c(0x151C31), card = c(0x1E2742), ink = c(0xF4F1EA), mute = c(0xA9B4CE), line = c(0x2E3858),
        sky = c(0x2B3A63), page = c(0x0B1020), nav = c(0x0E1424), navContent = c(0xA9B4CE), navActive = c(0xF4F1EA),
        accent = c(0x6C9BD8), highlight = c(0xF4E27A), primary = c(0x9DBDEA), onPrimary = c(0x151C31)
    ),
    shapes = FluvourShapes(card = 22.dp, button = 99.dp),
    style = FluvourStyle(heroHeader = true, cardShadow = 6.dp)
)


/**
 * Warm: the finalized Fluvour UI (Fluvour-UI Finalized.html). Cream paper, ink text, hairline borders, serif headings.
 * The accent and tint are replaced per language in FluvourTheme (see LanguagePalettes), so the accent here is the Japanese one.
 * Light values are the design's :root tokens. The dark set is new (the design is light only).
 */
val WarmTheme = ThemeSpec(
    id = ThemeId.Warm, label = "Fluvour", blurb = "Warm paper, serif type and one accent per language.",
    light = FluvourColors(
        paper = c(0xFAF6EE), card = c(0xFFFDF8), ink = c(0x1F2B3A), mute = c(0x574F43), line = c(0xE7DECE),
        sky = c(0xF1E9DA), page = c(0xEFE8DA), nav = c(0xFAF6EE), navContent = c(0x574F43), navActive = c(0xA94B28),
        accent = c(0xA94B28), highlight = c(0xEBD3B8), primary = c(0xA94B28), onPrimary = c(0xFFFFFF),
        tint = c(0xEBD3B8), scrim = Color(0x591F2B3A)
    ),
    dark = FluvourColors(
        paper = c(0x17140F), card = c(0x211D17), ink = c(0xF3ECDF), mute = c(0xB8AE9C), line = c(0x3A342A),
        sky = c(0x2A251D), page = c(0x0F0D0A), nav = c(0x17140F), navContent = c(0xB8AE9C), navActive = c(0xE0875F),
        accent = c(0xE0875F), highlight = c(0x3A2A20), primary = c(0xE0875F), onPrimary = c(0x17140F),
        tint = c(0x3A2A20), scrim = Color(0x99000000)
    ),
    shapes = FluvourShapes(card = 18.dp, button = 12.dp, sheet = 20.dp),
    style = FluvourStyle(heroHeader = false, cardShadow = 0.dp, flat = true, languageAccent = true, designFonts = true)
)

object Themes {
    val all = listOf(WarmTheme, CalmTheme, PlayfulTheme)
    fun byId(id: ThemeId) = all.first { it.id == id }
}
