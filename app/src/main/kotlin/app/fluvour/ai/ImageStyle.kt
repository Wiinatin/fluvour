package app.fluvour.ai

/** The five illustration inks of a language (Art.kt `ArtPalette`: il1 main, il2 soft, il3 warm, il4 deep ink, il5 paper), as `RRGGBB` hex. */
data class ImageInks(val main: String, val soft: String, val warm: String, val deep: String, val paper: String)

/**
 * The look of the AI card pictures: an editorial spot illustration in the same warm, hand-made style as the app's own art
 * (covers, language marks: layered flat shapes, stipple grain, cream paper), painted only in the target language's inks so a
 * picture sits naturally next to that language's covers and accent. Pure JVM.
 */
object ImageStyle {
    /** Longest meaning put into the prompt (the proxy accepts at most 1000 characters in all). */
    private const val MAX_CONCEPT = 110

    fun prompt(meaning: String, word: String, inks: ImageInks): String {
        val concept = meaning.replace(Regex("\\s+"), " ").replace("\"", "").trim().ifEmpty { word.trim() }.take(MAX_CONCEPT).trim()
        return "Editorial spot illustration for a refined language-learning journal, hand-made look. " +
            "Subject: $concept. Show one clear, simple subject; for an abstract idea show one everyday scene with two or three objects. " +
            "Style: layered flat gouache and cut-paper shapes with a fine stipple grain, soft matte finish, sparing thin ink linework, " +
            "calm and warm like a printed risograph. " +
            palette(inks) +
            "Composition: square, subject centred with a generous empty paper margin, plain background, no border. " +
            "Strictly no text, no letters, no numbers, no captions, no watermark, no logo, no photo realism, no 3D render, no glossy gradients, no neon."
    }

    private fun palette(inks: ImageInks): String {
        val seen = HashSet<String>()
        fun c(hex: String): String {
            var n = colorName(hex)
            if (!seen.add(n)) { n = "lighter $n"; if (!seen.add(n)) { n = "pale $n"; seen.add(n) } }
            return "$n (#$hex)"
        }
        val deep = c(inks.deep); val main = c(inks.main); val soft = c(inks.soft); val warm = c(inks.warm); val paper = c(inks.paper)
        return "Palette limited to: $paper paper background, $deep for line and shadow, $main as the main colour, $soft and $warm as supporting tones. "
    }

    /** A plain-English colour name for a hex value, so the image model gets words as well as numbers. */
    fun colorName(hex: String): String {
        val v = hex.trim().removePrefix("#").toLongOrNull(16) ?: return "warm neutral"
        val r = ((v shr 16) and 0xFF) / 255.0; val g = ((v shr 8) and 0xFF) / 255.0; val b = (v and 0xFF) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2; val d = mx - mn
        val s = if (d == 0.0) 0.0 else d / (1 - Math.abs(2 * l - 1))
        var h = when {
            d == 0.0 -> 0.0
            mx == r -> 60 * (((g - b) / d) % 6)
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        if (h < 0) h += 360
        return when {
            l > 0.88 -> "cream"
            s < 0.12 -> if (l < 0.3) "charcoal" else "warm grey"
            l < 0.27 -> when { h in 190.0..260.0 -> "deep navy"; h in 80.0..170.0 -> "deep forest green"; h in 15.0..60.0 -> "dark umber"; else -> "deep ink" }
            h < 12 || h >= 345 -> if (l < 0.45) "brick red" else "coral"
            h < 28 -> if (l > 0.62) "peach" else "terracotta"
            h < 45 -> if (l > 0.7) "sand" else if (l > 0.55) "warm amber" else "burnt orange"
            h < 65 -> if (l > 0.7) "butter yellow" else "mustard"
            h < 85 -> if (l > 0.65) "pale sage" else "olive"
            h < 165 -> if (s < 0.25 || l > 0.65) "sage green" else "moss green"
            h < 200 -> if (l > 0.7) "pale aqua" else "teal"
            h < 255 -> if (l > 0.7) "powder blue" else "slate blue"
            h < 295 -> "dusty violet"
            else -> "dusty plum"
        }
    }
}
