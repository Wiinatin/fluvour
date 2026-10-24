package app.fluvour.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.fluvour.ui.theme.Fluvour
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** The cached AI picture of a card, if it has one. `version` makes a regenerated file (same path) reload. */
@Composable
fun CardImage(path: String?, version: Long, modifier: Modifier = Modifier) {
    if (path == null) return
    val bmp = remember(path, version) { BitmapFactory.decodeFile(path)?.asImageBitmap() } ?: return
    // The pictures are drawn on cream paper (see ai/ImageStyle.kt); a hairline in the theme's line colour sets them on the card like the app's own art.
    val shape = RoundedCornerShape(16.dp)
    Image(bmp, contentDescription = null, modifier = modifier.clip(shape).border(1.dp, Fluvour.colors.line, shape), contentScale = ContentScale.Fit)
}
