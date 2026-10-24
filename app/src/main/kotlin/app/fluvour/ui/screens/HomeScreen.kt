package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.art.artPattern
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.ui.theme.Fluvour
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private enum class HomeSheet { None, Language, Profile }

private data class NextUp(val route: String, val title: String, val meta: String, val cta: String, val seed: Int)

/**
 * Home. Real: due-card count, streak, week dots, language, profile numbers.
 * Placeholder: whether Read, Output and Watch are "done" today (no tracking yet, so they always show as open).
 */
@Composable
fun HomeScreen(vm: AppViewModel, onGoTab: (String) -> Unit, onSettings: () -> Unit, onProgress: () -> Unit) {
    val c = Fluvour.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val due by vm.dueCount.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(HomeSheet.None) }
    val lang = s?.targetLang ?: "ja"
    val today = LocalDate.now()
    val hour = LocalTime.now().hour
    val greeting = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
    val dateLine = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.UK))

    val cardsDone = due == 0 && cards.isNotEmpty()
    val readDone = false; val speakDone = false; val watchDone = false   // not tracked yet
    val doneCount = listOf(cardsDone, readDone, speakDone, watchDone).count { it }
    val next = when {
        cards.isEmpty() -> NextUp("read", "Find a first article", "Tap words to make cards", "Start reading", 2)
        !cardsDone -> NextUp("cards", "Review $due ${if (due == 1) "card" else "cards"}", "About ${maxOf(1, due / 6)} min", "Start review", 0)
        else -> NextUp("read", "Read something new", "Wikipedia in ${Languages.byCode(lang).name}", "Start reading", 1)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).artPattern(1).ambient(e = "eh").languageMark(e = "lmh")) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                append("FLU"); withStyle(SpanStyle(color = c.accent)) { append(" / ") }; append("VOUR")
            }, Modifier.weight(1f), color = c.ink, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.3.sp)
            Row(Modifier.height(34.dp).clickable { sheet = HomeSheet.Language }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Languages.byCode(lang).native, color = c.ink, fontSize = 13.sp, fontFamily = targetFont())
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp), tint = c.mute)
            }
            IconBtn(Icons.Outlined.PersonOutline, "Profile", { sheet = HomeSheet.Profile })
        }
        Column(Modifier.padding(horizontal = 22.dp)) {
            Eyebrow(dateLine, Modifier.padding(top = 34.dp))
            H1(greeting, Modifier.padding(top = 8.dp), accentSuffix = ".")

            FCard(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CoverArt(app.fluvour.ui.art.coverKeyFor(lang, next.route), Modifier.size(84.dp, 108.dp))
                    Column(Modifier.weight(1f).height(108.dp)) {
                        Eyebrow("Next up")
                        H2(next.title, Modifier.padding(top = 6.dp, bottom = 4.dp))
                        Sm(next.meta)
                        Spacer(Modifier.weight(1f))
                        HairlineProgress(doneCount / 4f)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Sm("Today"); Sm("$doneCount of 4 done")
                }
                FButton(next.cta, { onGoTab(next.route) }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = Icons.Outlined.ArrowForward)
            }

            Eyebrow("Today", Modifier.padding(top = 34.dp, bottom = 2.dp))
            TodayRow("Review", if (cardsDone) "Done" else "$due cards due", cardsDone) { onGoTab("cards") }
            TodayRow("Read", if (readDone) "Done" else "Articles in ${Languages.byCode(lang).name}", readDone) { onGoTab("read") }
            TodayRow("Output", if (speakDone) "Done" else "1 call waiting", speakDone) { onGoTab("speak") }
            TodayRow("Watch", if (watchDone) "Done" else "4 short clips", watchDone) { onGoTab("watch") }

            Row(Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Eyebrow("This week")
                Sm(if (stats.streak == 1) "1 day streak" else "${stats.streak} day streak")
            }
            WeekDots(stats)
            Spacer(Modifier.height(28.dp))
        }
    }

    when (sheet) {
        HomeSheet.Language -> LanguageSheet(lang, onPick = { vm.setTarget(it); sheet = HomeSheet.None }, onClose = { sheet = HomeSheet.None })
        HomeSheet.Profile -> ProfileSheet(wordsKnown = cards.size, stats = stats, lang = lang,
            onSettings = { sheet = HomeSheet.None; onSettings() }, onProgress = { sheet = HomeSheet.None; onProgress() }, onClose = { sheet = HomeSheet.None })
        HomeSheet.None -> {}
    }
}

@Composable
private fun TodayRow(name: String, meta: String, done: Boolean, onClick: () -> Unit) {
    val c = Fluvour.colors
    FRow(name, subtitle = meta, onClick = onClick, trailing = {
        if (done) Box(Modifier.size(20.dp).background(c.accent, CircleShape), Alignment.Center) {
            Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = c.onPrimary)
        } else ChevronGo()
    })
}

@Composable
private fun WeekDots(stats: Stats) {
    val c = Fluvour.colors
    val today = LocalDate.now()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0..6) {
            val day = monday.plusDays(i.toLong())
            val done = !day.isAfter(today) && (stats.countsByDay[day] ?: 0) > 0
            val isToday = day == today
            Column(Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).then(
                    if (done) Modifier.background(c.accent, CircleShape)
                    else if (isToday) Modifier.border(1.5.dp, c.accent, CircleShape)
                    else Modifier.background(c.line, CircleShape)))
                Text(labels[i], fontSize = 12.sp, color = if (isToday) c.ink else c.mute)
            }
        }
    }
}
