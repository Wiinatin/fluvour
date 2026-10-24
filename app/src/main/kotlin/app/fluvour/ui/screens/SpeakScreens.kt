package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.sample.Sample
import app.fluvour.ui.sample.SampleTopic
import app.fluvour.ui.sample.forLang
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.delay

private val callPrompts: Map<String, List<Pair<String, String>>> = mapOf(
    "th" to listOf("รับอะไรดีคะ" to "What would you like?", "เผ็ดไหมคะ" to "Would you like it spicy?", "ได้ค่ะ ขอบคุณค่ะ" to "Sure. Thank you."),
    "ja" to listOf("何になさいますか" to "What would you like?", "辛いのは大丈夫ですか" to "Is spicy okay?", "はい、ありがとうございます" to "Sure. Thank you."),
    "en" to listOf("What can I get you?" to "", "Would you like it spicy?" to "", "Sure. Thank you." to ""))

/**
 * Output tab: Conversation and Topic lists, plus the Write entries (journal, practice sheet, handwriting).
 * All content is placeholder (Sample). Real speaking arrives with the speech-recognition phase.
 */
@Composable
fun SpeakScreen(lang: String, onCall: (Int) -> Unit, onTopic: (Int) -> Unit, onJournal: () -> Unit, onHandwriting: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).ambient().languageMark().padding(horizontal = 22.dp)) {
        Eyebrow("Speak and write", Modifier.padding(top = 24.dp))
        H1("Output", Modifier.padding(top = 8.dp))
        UnderlineTabs(listOf("Conversation", "Topic"), tab, { tab = it }, Modifier.padding(top = 6.dp))
        Sm(if (tab == 1) "Pick a topic and cover three points out loud." else "Have a short voice call and reply out loud.", Modifier.padding(top = 16.dp, bottom = 4.dp))
        if (tab == 0) Sample.calls.forEachIndexed { i, call ->
            FRow(call.title, subtitle = "Voice call · about 2 min · ${call.level}", onClick = { onCall(i) },
                leading = { CoverArt(app.fluvour.ui.art.callArt[i % 4], Modifier.size(44.dp, 56.dp), 6.dp, small = true) })
        } else Sample.topics.forLang(lang).forEachIndexed { i, t ->
            FRow(t.title, subtitle = "${t.english} · ${t.level} · ${t.seconds} sec", titleFont = targetFont(), onClick = { onTopic(i) },
                leading = { CoverArt(app.fluvour.ui.art.topicArt[i % 4], Modifier.size(44.dp, 56.dp), 6.dp, small = true) })
        }
        SectionEyebrow("Write", top = 30.dp)
        FRow("Journal", subtitle = "A few lines in ${Languages.byCode(lang).name}", onClick = onJournal)
        FRow("Practice sheet", subtitle = "Ruled for your words", onClick = { onHandwriting("ps") })
        FRow("Handwriting", subtitle = "Write by hand, check with the camera", onClick = { onHandwriting("hw") })
        NotBuiltNote("Calls and topics are sample content. Nothing is recorded or saved yet.")
        Spacer(Modifier.height(30.dp))
    }
}

/** Voice call (route "call/{i}"). Tap the mic to speak and tap again to answer. Three turns, then the result. */
@Composable
fun CallScreen(index: Int, lang: String, onClose: () -> Unit) {
    val c = Fluvour.colors
    val title = Sample.calls.getOrNull(index)?.title ?: "Call"
    val prompts = callPrompts.forLang(lang)
    var turn by remember { mutableIntStateOf(0) }
    var rec by remember { mutableStateOf(false) }
    if (turn >= prompts.size) { CallResult(lang, onClose); return }
    val p = prompts[turn]
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("$title · ${turn + 1} of ${prompts.size}", Modifier.weight(1f))
            IconBtn(Icons.Outlined.Close, "End call", onClose)
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TargetText(p.first, size = 30.sp, align = TextAlign.Center)
            if (p.second.isNotBlank()) Sm(p.second, Modifier.padding(top = 8.dp))
            Wave(rec, Modifier.padding(top = 26.dp, bottom = 6.dp))
            RoundAction(Icons.Outlined.Mic, "Tap to talk", { if (rec) { rec = false; turn++ } else rec = true }, Modifier.padding(top = 16.dp), size = 68.dp, ring = rec)
            Sm(if (rec) "Tap again when you have answered" else "Tap and say your reply", Modifier.padding(top = 12.dp), align = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CallResult(lang: String, onClose: () -> Unit) {
    val c = Fluvour.colors
    val r = Sample.callResult.forLang(lang)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onClose, "Your result", closeIcon = true)
        Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RingPercent(75)
            H2("Nicely done. Clear and natural.", Modifier.padding(top = 12.dp), align = TextAlign.Center)
        }
        Compare("What you said", r.first)
        Compare("Improved", r.second, r.third)
        NotBuiltNote("Sample result. Real feedback arrives with speech recognition.")
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        FButton("Done", onClose, Modifier.fillMaxWidth().padding(bottom = 24.dp))
    }
}

@Composable
private fun Compare(label: String, text: String, note: String? = null) {
    val c = Fluvour.colors
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        HorizontalDivider(color = c.line)
        Eyebrow(label, Modifier.padding(top = 14.dp))
        TargetText(text, Modifier.padding(top = 6.dp), size = 22.sp, lineHeight = 37.sp)
        if (note != null) Sm(note)
    }
}

private fun mmss(s: Int) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

/** Topic session (route "topic/{i}"): cue card, countdown ring while speaking, then the result with the model answer. */
@Composable
fun TopicScreen(index: Int, lang: String, onClose: () -> Unit) {
    val c = Fluvour.colors
    val topics = Sample.topics.forLang(lang)
    val t = topics[index.coerceIn(0, topics.lastIndex)]
    var phase by remember { mutableIntStateOf(0) }       // 0 ready, 1 speaking, 2 result
    var elapsed by remember { mutableIntStateOf(0) }
    var covered by remember { mutableIntStateOf(0) }
    fun cuesDone(e: Int) = listOf(0.3f, 0.58f, 0.82f).count { e >= t.seconds * it }
    LaunchedEffect(phase) {
        if (phase == 1) {
            while (elapsed < t.seconds) { delay(1000); elapsed++ }
            covered = cuesDone(elapsed); phase = 2
        }
    }
    when (phase) {
        2 -> TopicResult(t, covered, elapsed, onAgain = { elapsed = 0; covered = 0; phase = 0 }, onClose = onClose)
        else -> {
            val rec = phase == 1
            val shown = if (rec) cuesDone(elapsed) else 0
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Topic · ${t.seconds} sec", Modifier.weight(1f))
                    IconBtn(Icons.Outlined.Close, "Close", onClose)
                }
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Sm(if (rec) "Speaking now" else "Cover three points out loud")
                    H1(t.title, Modifier.padding(top = 6.dp), size = 30.sp, font = targetFont())
                    Sm(t.english, Modifier.padding(top = 6.dp))
                    TimerRing(1f - elapsed / t.seconds.toFloat(), mmss(t.seconds - elapsed), Modifier.padding(vertical = 18.dp))
                }
                t.cues.forEachIndexed { n, cue ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CheckDot(n < shown)
                        Column { TargetText(cue.first, size = 18.sp); if (cue.second.isNotBlank()) Sm(cue.second, size = 12.5.sp) }
                    }
                    HorizontalDivider(color = c.line)
                }
                if (rec) Wave(true, Modifier.padding(top = 20.dp)) else Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow("Useful words")
                    Text(buildAnnotatedString { t.words.forEachIndexed { i, w -> if (i > 0) append("   ·   "); append(w) } }, Modifier.padding(top = 6.dp),
                        color = c.ink, fontFamily = targetFont(), fontSize = 17.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                if (rec) FButton("Finish", { covered = cuesDone(elapsed); phase = 2 }, Modifier.fillMaxWidth(), outlined = true)
                else FButton("Start speaking", { phase = 1 }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Mic)
            }
        }
    }
}

@Composable
private fun CheckDot(on: Boolean, cross: Boolean = false) {
    val c = Fluvour.colors
    Box(Modifier.size(20.dp).then(if (on) Modifier.background(c.accent, CircleShape) else Modifier.border(1.dp, c.line, CircleShape)), Alignment.Center) {
        if (on) Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = c.onPrimary)
        else if (cross) Icon(Icons.Outlined.Close, null, Modifier.size(11.dp), tint = c.mute)
    }
}

@Composable
private fun TopicResult(t: SampleTopic, covered: Int, elapsed: Int, onAgain: () -> Unit, onClose: () -> Unit) {
    val pc = 40 + covered * 18
    val msg = listOf("Try again with the cue card in view.", "Two points left. Try once more.", "Nearly there. One point to go.", "You covered every point.")[covered.coerceIn(0, 3)]
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onClose, "Your result", closeIcon = true)
        Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RingPercent(pc)
            H2(msg, Modifier.padding(top = 12.dp), align = TextAlign.Center)
            Sm("You spoke for ${mmss(elapsed)}.", Modifier.padding(top = 4.dp))
        }
        Column(Modifier.padding(top = 20.dp)) {
            t.cues.forEachIndexed { n, cue ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CheckDot(n < covered, cross = true)
                    Column { TargetText(cue.first, size = 18.sp); if (cue.second.isNotBlank()) Sm(cue.second, size = 12.5.sp) }
                }
                HorizontalDivider(color = Fluvour.colors.line)
            }
        }
        Compare("Model answer", t.model.first, t.model.second)
        NotBuiltNote("Sample result. The percentage is not measured yet.")
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton("Try again", onAgain, Modifier.weight(1f), outlined = true)
            FButton("Done", onClose, Modifier.weight(1f))
        }
    }
}
