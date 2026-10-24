package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.ui.HairlineProgress
import app.fluvour.ui.Stats
import app.fluvour.ui.theme.Fluvour
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Activity grid: columns are weeks (Monday first), rows are weekdays, the last column is this week. Real data from review_log. */
@Composable
fun Heatmap(stats: Stats, modifier: Modifier = Modifier, weeks: Int = 12) {
    val c = Fluvour.colors
    val today = LocalDate.now()
    val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (w in 0 until weeks) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 0 until 7) {
                        val day = firstMonday.plusDays((w * 7 + d).toLong())
                        val color = if (day.isAfter(today)) Color.Transparent else heatColor(level(stats.countsByDay[day] ?: 0))
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(color))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text("Less", fontSize = 11.sp, color = c.mute)
            for (l in 0..4) Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(heatColor(l)))
            Text("More", fontSize = 11.sp, color = c.mute)
        }
    }
}

@Composable
private fun heatColor(level: Int): Color {
    val c = Fluvour.colors
    return when (level) {
        0 -> c.line
        1 -> lerp(c.paper, c.accent, 0.15f)
        2 -> lerp(c.paper, c.accent, 0.40f)
        3 -> lerp(c.paper, c.accent, 0.70f)
        else -> c.accent
    }
}

private fun level(n: Int) = when { n <= 0 -> 0; n < 5 -> 1; n < 15 -> 2; n < 30 -> 3; else -> 4 }

/** A labelled progress line ("Reading ▬▬▬ 62%"). */
@Composable
fun SkillRow(label: String, percent: Int, value: String = "$percent%", labelWidth: Int = 78) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, Modifier.width(labelWidth.dp), fontSize = 14.sp, color = c.ink)
        HairlineProgress(percent / 100f, Modifier.weight(1f))
        Text(value, Modifier.widthIn(min = 38.dp), fontSize = 13.sp, color = c.mute, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
