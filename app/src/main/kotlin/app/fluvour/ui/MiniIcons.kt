package app.fluvour.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons for the mini player: 24dp grid, 1.6 stroke, round caps and joins, no fill, to sit beside the Material outlined icons used elsewhere.
 * Tint comes from the Icon composable, so use them like any other icon: Icon(MiniIcons.Close, ...) or IconBtn(MiniIcons.Close, ...).
 */
object MiniIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block
        ).build()

    /** X: close the mini player. */
    val Close: ImageVector by lazy { icon("MiniClose") { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) } }

    /** Two corners pointing outward: return to the Watch page. */
    val Expand: ImageVector by lazy {
        icon("MiniExpand") {
            moveTo(14f, 5f); lineTo(19f, 5f); lineTo(19f, 10f)
            moveTo(19f, 5f); lineTo(12.5f, 11.5f)
            moveTo(10f, 19f); lineTo(5f, 19f); lineTo(5f, 14f)
            moveTo(5f, 19f); lineTo(11.5f, 12.5f)
        }
    }

    /** Rounded triangle. */
    val Play: ImageVector by lazy {
        icon("MiniPlay") {
            moveTo(8f, 6.2f); curveTo(8f, 5.5f, 8.7f, 5.1f, 9.3f, 5.5f); lineTo(18.3f, 11.3f)
            curveTo(18.8f, 11.6f, 18.8f, 12.4f, 18.3f, 12.7f); lineTo(9.3f, 18.5f)
            curveTo(8.7f, 18.9f, 8f, 18.5f, 8f, 17.8f); close()
        }
    }

    /** Two bars. */
    val Pause: ImageVector by lazy { icon("MiniPause") { moveTo(9f, 6f); lineTo(9f, 18f); moveTo(15f, 6f); lineTo(15f, 18f) } }

    /** Five sound bars: shrink to audio only, picture goes away. */
    val AudioOnly: ImageVector by lazy {
        icon("MiniAudioOnly") {
            moveTo(4.5f, 10f); lineTo(4.5f, 14f); moveTo(8.25f, 7f); lineTo(8.25f, 17f); moveTo(12f, 4.5f); lineTo(12f, 19.5f)
            moveTo(15.75f, 8f); lineTo(15.75f, 16f); moveTo(19.5f, 10.5f); lineTo(19.5f, 13.5f)
        }
    }
}
