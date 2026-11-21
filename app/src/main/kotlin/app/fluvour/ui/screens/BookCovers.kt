package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.ui.art.ArtPalettes
import app.fluvour.ui.targetFont
import app.fluvour.ui.theme.Fluvour
import coil.compose.AsyncImage
import java.io.File

/**
 * A book cover: the saved file if there is one, else the cover URL, else a generated cover (title and author on a colour block).
 * The generated cover is always drawn underneath, so a cover that is slow or fails to load still leaves a proper cover.
 */
@Composable
fun BookCover(title: String, author: String, coverUrl: String?, coverPath: String?, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val model: Any? = remember(coverPath, coverUrl) { coverPath?.let { File(it) }?.takeIf { it.exists() } ?: coverUrl?.takeIf { it.isNotBlank() } }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        GeneratedCover(title, author, Modifier.fillMaxSize())
        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Title and author on a colour block, with the colours picked from the language's art palette by the title. */
@Composable
fun GeneratedCover(title: String, author: String, modifier: Modifier = Modifier) {
    val p = ArtPalettes.of(Fluvour.lang)
    val pairs = listOf(p.il4 to p.il5, p.il1 to p.il5, p.il3 to p.il4, p.il2 to p.il4)
    val (bg, fg) = pairs[Math.floorMod(title.hashCode(), pairs.size)]
    BoxWithConstraints(modifier.background(bg)) {
        val w = maxWidth.value
        Column(Modifier.fillMaxSize().padding((w * 0.1f).dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = fg, fontFamily = targetFont(), fontWeight = FontWeight.Medium, textAlign = TextAlign.Start,
                fontSize = (w * 0.13f).coerceIn(8f, 20f).sp, lineHeight = (w * 0.13f).coerceIn(8f, 20f).sp * 1.2f,
                maxLines = if (w < 60f) 4 else 5, overflow = TextOverflow.Ellipsis)
            if (w >= 60f && author.isNotBlank()) Text(author, color = fg.copy(alpha = 0.8f), fontSize = (w * 0.08f).coerceIn(7f, 12f).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
