package app.fluvour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.fluvour.ui.theme.Fluvour

/** The card face of the Cards tab, for the Heisig and Word Power study screens: rounded, hairline border, a soft tint fading to the card colour. */
@Composable
fun StudyFace(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).border(1.dp, c.line, shape)
            .background(Brush.verticalGradient(listOf(lerp(c.card, c.tint, 0.42f), c.card), endY = 900f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(24.dp),
        content = content
    )
}
