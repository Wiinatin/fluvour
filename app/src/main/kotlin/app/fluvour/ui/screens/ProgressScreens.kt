package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.sample.Sample
import app.fluvour.ui.sample.forLang
import app.fluvour.ui.theme.Fluvour
import app.fluvour.ui.theme.LanguagePalettes

/** "What are you learning?" sheet. Every language in Languages.kt is listed; the swatches are its palette. */
@Composable
fun LanguageSheet(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val c = Fluvour.colors
    FSheet(onClose) {
        H2("What are you learning?", size = 24.sp, modifier = Modifier.padding(top = 4.dp))
        Sm("Progress is saved for each language.", Modifier.padding(top = 4.dp, bottom = 8.dp))
        Languages.all.forEach { l ->
            val on = l.code == current
            val p = LanguagePalettes.of(l.code, Fluvour.dark)
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { onPick(l.code) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(36.dp).border(1.dp, if (on) c.accent else c.line, CircleShape), Alignment.Center) {
                        Text(l.native.take(1), fontSize = 17.sp, color = if (on) c.accent else c.ink)
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(l.native, fontSize = 18.sp, color = c.ink, fontFamily = serifFont())
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(8.dp).background(p.accent, CircleShape))
                                Box(Modifier.size(8.dp).background(p.tint, CircleShape))
                                Box(Modifier.size(8.dp).background(c.ink, CircleShape))
                            }
                        }
                        Sm(l.name, size = 12.5.sp)
                    }
                    if (on) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = c.accent)
                }
                androidx.compose.material3.HorizontalDivider(color = c.line)
            }
        }
    }
}

/** Profile sheet from the Home header. Words known, streak and the heatmap are real. Skill bars are placeholder. */
@Composable
fun ProfileSheet(wordsKnown: Int, stats: Stats, lang: String, onSettings: () -> Unit, onProgress: () -> Unit, onClose: () -> Unit) {
    val c = Fluvour.colors
    FSheet(onClose, tall = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Profile", Modifier.padding(top = 8.dp))
                H2("Your progress", Modifier.padding(top = 6.dp), size = 26.sp)
            }
            IconBtn(Icons.Outlined.Settings, "Settings", onSettings)
            IconBtn(Icons.Outlined.Close, "Close", onClose)
        }
        StatsTriple(listOf(wordsKnown.toString() to "Words known", stats.streak.toString() to "Day streak", stats.today().toString() to "Cards today"))
        Eyebrow("Last 12 weeks", Modifier.padding(top = 26.dp, bottom = 10.dp))
        Heatmap(stats)
        Eyebrow("Skills", Modifier.padding(top = 26.dp, bottom = 2.dp))
        listOf("Reading", "Listening", "Speaking", "Writing").forEachIndexed { i, n -> SkillRow(n, Sample.skills.forLang(lang)[i]) }
        NotBuiltNote("Skill levels are placeholders until reading, listening, speaking and writing are tracked.")
        LinkText("See all progress", onProgress, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun StatsTriple(items: List<Pair<String, String>>) {
    val c = Fluvour.colors
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        androidx.compose.material3.HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            items.forEach { (v, label) ->
                Column(Modifier.weight(1f)) {
                    Text(v, color = c.ink, fontFamily = serifFont(), fontSize = 30.sp)
                    Text(label.uppercase(), color = c.mute, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp)
                }
            }
        }
    }
}

/** Full progress page (route "progress"). Mined-by-source minutes and skills are placeholders. */
@Composable
fun ProgressScreen(lang: String, onBack: () -> Unit, onWeekly: () -> Unit) {
    val mins = Sample.minutesBySource.forLang(lang)
    val src = listOf("Articles" to mins[0], "Clips" to mins[1], "Topics" to mins[2], "Imports" to mins[3])
    val mx = src.maxOf { it.second }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onBack)
        H1("Progress", Modifier.padding(top = 6.dp), size = 30.sp)
        Sm(Languages.byCode(lang).name, Modifier.padding(top = 6.dp))
        SectionEyebrow("Words mined by source", top = 30.dp)
        src.forEach { (n, v) -> SkillRow(n, v * 100 / mx, v.toString(), labelWidth = 120) }
        SectionEyebrow("Skills", top = 30.dp)
        listOf("Reading", "Listening", "Speaking", "Writing").forEachIndexed { i, n -> SkillRow(n, Sample.skills.forLang(lang)[i], labelWidth = 120) }
        NotBuiltNote("Placeholder numbers. They become real when each activity logs its time.")
        SectionEyebrow("Review", top = 30.dp)
        FRow("Weekly review", subtitle = "This week, in a few lines", onClick = onWeekly)
        Spacer(Modifier.height(30.dp))
    }
}

/** "Your week in review" page (route "weekly"). Text is a template with placeholder numbers. */
@Composable
fun WeeklyReviewScreen(lang: String, onClose: () -> Unit, onStartTopic: () -> Unit) {
    val n = Sample.weekNumbers.forLang(lang)
    val words = Sample.words.forLang(lang).take(3)
    val c = Fluvour.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onClose, closeIcon = true)
        Eyebrow("This week", Modifier.padding(top = 14.dp))
        H1("Your week in review", size = 30.sp)
        StatsTriple(listOf(n[0].toString() to "Cards", n[1].toString() to "New words", n[2].toString() to "Minutes"))
        Text("You kept a steady rhythm, five days out of seven. Reading carried the week, and your listening picked up after the clips on Wednesday.",
            Modifier.padding(top = 14.dp), color = c.ink, fontFamily = serifFont(), fontSize = 19.sp, lineHeight = 31.sp)
        SectionEyebrow("Worth another look", top = 26.dp)
        words.forEach { w -> FRow(w.word, subtitle = w.meaning, titleFont = targetFont(), trailing = { Tag("Missed 3") }) }
        SectionEyebrow("Next week", top = 26.dp)
        Text("Speaking had the fewest sessions. Try one topic recording to even things out.", Modifier.padding(top = 8.dp),
            color = c.ink, fontFamily = serifFont(), fontSize = 19.sp, lineHeight = 31.sp)
        NotBuiltNote("Placeholder text. The weekly summary needs per-activity tracking.")
        FButton("Start a topic", onStartTopic, Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 30.dp))
    }
}
