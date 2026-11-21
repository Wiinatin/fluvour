package app.fluvour.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.fluvour.reading.SarawakItem
import app.fluvour.reading.SarawakMastery
import app.fluvour.reading.SarawakStatus
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/**
 * The Sarawak tab (Malay only). Opening it unlocks today's new cards into the Malay deck; you review them in Cards, mode Sarawak.
 * [onUnlocked] lets the Cards tab pick up the new cards.
 */
@Composable
fun SarawakTab(onUnlocked: () -> Unit, vm: SarawakViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.open(onUnlocked) }
    val s = ui.stats
    Column(Modifier.fillMaxSize().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            s == null -> CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
            s.total == 0 -> Sm("The Sarawak deck is missing from this build.")
            else -> {
                H2("${s.unlocked} / ${s.total} Sarawak cards")
                LinearProgressIndicator(progress = s.unlocked.toFloat() / s.total, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                Sm(if (s.newLeftToday > 0) "${s.newLeftToday} more can unlock today" else "Today's new cards are unlocked. Review them in Cards, mode Sarawak.")
                FButton("Learn 10 more now", { vm.learnMore(onUnlocked) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !ui.busy && s.unlocked < s.total, outlined = true)
                val today = ui.items.filter { it.today }; val earlier = ui.items.filter { !it.today }
                if (ui.items.isEmpty()) Sm("No cards unlocked yet.", Modifier.padding(top = 8.dp))
                else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (today.isNotEmpty()) item("h-today") { Eyebrow("Unlocked today", Modifier.padding(top = 10.dp)) }
                    items(today, key = { it.entry.key }) { WordRow(it) }
                    if (earlier.isNotEmpty()) item("h-earlier") { Eyebrow("Earlier", Modifier.padding(top = 10.dp)) }
                    items(earlier, key = { it.entry.key }) { WordRow(it) }
                }
            }
        }
    }
}

/** One word: the Sarawak word and its meaning on the left, how well the card is known on the right. */
@Composable
private fun WordRow(t: SarawakItem) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(t.entry.sarawak, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Sm(listOf(t.entry.standard.takeIf { it.isNotBlank() }, t.entry.english.takeIf { it.isNotBlank() }).filterNotNull().joinToString("  ·  "))
            if (t.status == SarawakStatus.SKIPPED) Sm("Already in your deck, so no new card", size = 12.sp)
        }
        t.mastery?.let { MasteryBadge(it) }
    }
}

/** A gold tick once the card is mastered (memory stable for about 21 days, like Gold in the Heisig deck); until then a ring with the percentage. */
@Composable
private fun MasteryBadge(m: SarawakMastery) {
    val c = Fluvour.colors
    val gold = Color(0xFFE9A912)
    if (m.mastered) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFF6C84C), gold))), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, "Mastered", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    } else {
        val track = c.line; val arc = c.accent
        Box(Modifier.size(34.dp).semantics { contentDescription = "${m.percent} percent known" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 3.dp.toPx(); val inset = w / 2
                val sz = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
                if (m.percent > 0) drawArc(arc, -90f, 360f * m.percent / 100f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("${m.percent}%", color = c.mute, fontSize = 9.sp)
        }
    }
}

/** Settings > Sarawak deck (Malay only): how many new cards unlock a day. Same row as the Heisig one. */
@Composable
internal fun SarawakGroup(vm: SarawakViewModel = hiltViewModel()) {
    val perDay by vm.newPerDay.collectAsStateWithLifecycle()
    SettingRow("New Sarawak cards per day", sub = if (perDay == 0) "Paused: no new cards" else "Unlocked in order when you open the Sarawak tab", trailing = {
        FStepper(perDay.toString(), { vm.setNewPerDay(perDay - 1) }, { vm.setNewPerDay(perDay + 1) })
    })
}
