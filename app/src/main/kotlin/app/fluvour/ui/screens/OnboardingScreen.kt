package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.fluvour.ui.art.LanguageMotif
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import app.fluvour.ui.theme.LanguagePalettes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove

/**
 * Three-step intro: language, level, daily goal (route "onboarding"). Language and goal are saved through the callbacks.
 * The level is not stored anywhere yet (TODO(level): save it once placement uses it).
 * It is reached from Settings, "Replay intro". Launching it on first run needs a first-run flag in SettingsRepository.
 */
@Composable
fun OnboardingScreen(initialLang: String, initialGoal: Int, onLanguage: (String) -> Unit, onGoal: (Int) -> Unit, onDone: () -> Unit) {
    val c = Fluvour.colors
    var step by remember { mutableIntStateOf(1) }
    var lang by remember { mutableStateOf(initialLang) }
    var level by remember { mutableIntStateOf(2) }
    var goal by remember { mutableIntStateOf(initialGoal) }
    val title = listOf("What are you learning", "Where are you today", "How much each day")[step - 1]
    val sub = listOf("Pick one to start. You can add more later.", "A rough guess is fine. It adjusts as you go.", "A small, steady amount works best.")[step - 1]
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 1) IconBtn(Icons.Outlined.ArrowBack, "Back", { step-- }) else Spacer(Modifier.size(44.dp))
            Row(Modifier.weight(1f).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..3).forEach { i -> Box(Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (i <= step) c.accent else c.line)) }
            }
            Text("Skip", Modifier.clickable(onClick = onDone).padding(horizontal = 8.dp, vertical = 10.dp), color = c.mute, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth()) {
                LanguageMotif(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-16).dp))
                Column {
                    Eyebrow("Step $step of 3", Modifier.padding(top = 26.dp))
                    H1(title, Modifier.padding(top = 8.dp), accentSuffix = "?")
                    Sm(sub, Modifier.padding(top = 8.dp))
                }
            }
            Column(Modifier.padding(top = 12.dp)) {
                when (step) {
                    1 -> Languages.all.forEach { l ->
                        val on = l.code == lang
                        val p = LanguagePalettes.of(l.code, Fluvour.dark)
                        Column {
                            Row(Modifier.fillMaxWidth().clickable { lang = l.code }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(36.dp).border(1.dp, if (on) p.accent else c.line, CircleShape), Alignment.Center) { Text(l.native.take(1), fontSize = 17.sp, color = if (on) p.accent else c.ink) }
                                Column(Modifier.weight(1f)) {
                                    Text(l.native, fontSize = 18.sp, color = c.ink, fontFamily = serifFont())
                                    Sm(l.name, size = 12.5.sp)
                                }
                                if (on) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = p.accent)
                            }
                            HorizontalDivider(color = c.line)
                        }
                    }
                    2 -> listOf("Just starting" to "A1", "Know the basics" to "A2", "Hold a simple chat" to "B1", "Comfortable most days" to "B2+").forEachIndexed { i, (t, tag) ->
                        OptionRow(t, level == i, { level = i }, trailing = { Tag(tag) })
                    }
                    else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.padding(top = 36.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).border(1.dp, c.line, CircleShape).clip(CircleShape).clickable { goal = (goal - 5).coerceAtLeast(5) }, Alignment.Center) { Icon(Icons.Outlined.Remove, "Fewer cards", Modifier.size(16.dp), tint = c.ink) }
                            Text(goal.toString(), Modifier.widthIn(min = 96.dp), color = c.ink, fontFamily = serifFont(), fontSize = 64.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Box(Modifier.size(48.dp).border(1.dp, c.line, CircleShape).clip(CircleShape).clickable { goal = (goal + 5).coerceAtMost(200) }, Alignment.Center) { Icon(Icons.Outlined.Add, "More cards", Modifier.size(16.dp), tint = c.ink) }
                        }
                        Sm("cards a day, about ${Math.round(goal * 0.4)} minutes")
                        Sm("You can change this later in Settings.", Modifier.padding(top = 18.dp))
                    }
                }
            }
        }
        FButton(if (step < 3) "Continue" else "Start learning", {
            if (step < 3) step++ else { onLanguage(lang); onGoal(goal); onDone() }
        }, Modifier.fillMaxWidth().padding(vertical = 22.dp))
    }
}
