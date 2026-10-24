package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.ui.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.quiz.QKind
import app.fluvour.quiz.Question
import app.fluvour.quiz.QuizEngine
import app.fluvour.quiz.QuizViewModel
import app.fluvour.ui.theme.Fluvour

/**
 * Quiz session (route "quiz/{set}", Phase 3). Questions are built from your own cards (see quiz/QuizRepository): in context,
 * fill in the blank, listening, and "which word means this". Missed words can be put back into review from the result screen.
 */
@Composable
fun QuizScreen(setId: String, lang: String, onClose: () -> Unit, vm: QuizViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(setId, run) { vm.load(setId) }
    when {
        ui.loading -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
            Sm("Putting your quiz together. New sentences are fetched the first time.", Modifier.padding(top = 12.dp), align = TextAlign.Center)
        }
        ui.questions.isEmpty() -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            H2(ui.message ?: "No questions.", align = TextAlign.Center)
            FButton("Back", onClose, Modifier.padding(top = 20.dp), outlined = true)
        }
        else -> QuizSession(ui.questions, vm, onAgain = { run++ }, onClose = onClose)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuizSession(qs: List<Question>, vm: QuizViewModel, onAgain: () -> Unit, onClose: () -> Unit) {
    val c = Fluvour.colors
    var i by remember(qs) { mutableIntStateOf(0) }
    var sel by remember(qs, i) { mutableIntStateOf(-1) }
    var checked by remember(qs, i) { mutableStateOf(false) }
    val ok = remember(qs) { mutableStateListOf<Boolean>() }
    var finished by remember(qs) { mutableStateOf(false) }
    val voiceHint by vm.voiceHint.collectAsStateWithLifecycle()

    if (finished) { QuizResult(qs, ok, vm, onAgain = onAgain, onClose = onClose); return }
    val q = qs[i]
    LaunchedEffect(q) { if (q.kind == QKind.LISTEN) vm.speak(q.speak) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp).ambient(bleed = 22.dp).languageMark(bleed = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBtn(Icons.Outlined.Close, "Close quiz", onClose)
            HairlineProgress((i + if (checked) 1 else 0) / qs.size.toFloat(), Modifier.weight(1f))
            Sm("${i + 1} / ${qs.size}", size = 12.5.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 20.dp)) {
            Tag(when (q.kind) { QKind.CONTEXT -> "In context"; QKind.FILL -> "Fill in the blank"; QKind.LISTEN -> "Listening"; QKind.TRANSLATE -> "Which word?" })
            when (q.kind) {
                QKind.CONTEXT -> {
                    Text(markedSentence(q.prompt), Modifier.padding(top = 16.dp, bottom = 22.dp), fontFamily = targetFont(), fontSize = 24.sp, lineHeight = 42.sp, color = c.ink)
                    H2("What does the marked word mean?", size = 20.sp)
                    q.options.forEachIndexed { n, o -> QuizOption(o, n, sel, checked, q.correct, target = false) { sel = n } }
                }
                QKind.FILL -> {
                    H2("Pick the word that fits", Modifier.padding(top = 16.dp), size = 20.sp)
                    val filled = if (checked) q.options[q.correct] else if (sel >= 0) q.options[sel] else "      "
                    val parts = q.prompt.split("___")
                    Text(buildAnnotatedString {
                        append(parts.getOrElse(0) { "" })
                        withStyle(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)) { append(" $filled ") }
                        append(parts.getOrElse(1) { "" })
                    }, Modifier.padding(top = 12.dp), fontFamily = targetFont(), fontSize = 24.sp, lineHeight = 42.sp, color = c.ink)
                    if (q.hint.isNotBlank()) Sm("Hint: ${q.hint}", Modifier.padding(top = 6.dp))
                    FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        q.options.forEachIndexed { n, o ->
                            val right = checked && n == q.correct; val wrong = checked && n == sel && n != q.correct
                            val on = !checked && n == sel
                            Row(
                                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, if (on || right) c.accent else c.line, RoundedCornerShape(12.dp))
                                    .clickable(enabled = !checked) { sel = n }.padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (right) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp).padding(end = 0.dp), tint = c.accent)
                                Text(o, fontFamily = targetFont(), fontSize = 20.sp, color = if (on || right) c.accent else c.ink,
                                    textDecoration = if (wrong) TextDecoration.LineThrough else null)
                            }
                        }
                    }
                }
                QKind.LISTEN -> {
                    H2("Which word did you hear?", Modifier.padding(top = 16.dp), size = 20.sp)
                    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        RoundAction(Icons.Outlined.PlayArrow, "Play audio", { vm.speak(q.speak) }, size = 76.dp)
                        Sm("Tap to listen again", Modifier.padding(top = 10.dp))
                        if (voiceHint) Sm("No voice for this language yet. Install one under Android's Text-to-speech settings.", Modifier.padding(top = 6.dp), size = 12.sp)
                    }
                    q.options.forEachIndexed { n, o -> QuizOption(o, n, sel, checked, q.correct, target = true) { sel = n } }
                }
                QKind.TRANSLATE -> {
                    H2("Which word means this?", Modifier.padding(top = 16.dp), size = 20.sp)
                    H2(q.prompt, Modifier.padding(top = 14.dp, bottom = 10.dp), size = 26.sp)
                    q.options.forEachIndexed { n, o -> QuizOption(o, n, sel, checked, q.correct, target = true) { sel = n } }
                }
            }
            if (checked && q.kind != QKind.LISTEN && q.kind != QKind.TRANSLATE && q.speak.isNotBlank()) Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundAction(Icons.Outlined.PlayArrow, "Play sentence", { vm.speak(q.speak, q.audioId) }, size = 44.dp)
                Sm(if (q.audioId != null) "Native speaker · Tatoeba" else "Tap to hear the sentence", size = 12.sp)
            }
            if (checked) Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(15.dp).padding(top = 2.dp), tint = c.mute)
                Sm(q.explanation, size = 13.sp)
            }
        }
        FButton(if (checked) (if (i + 1 < qs.size) "Continue" else "See results") else "Check", {
            if (!checked) { if (sel >= 0) { checked = true; ok.add(sel == q.correct) } }
            else if (i + 1 < qs.size) i++ else finished = true
        }, Modifier.fillMaxWidth().padding(bottom = 24.dp), soft = !checked && sel < 0)
    }
}

@Composable
private fun markedSentence(raw: String) = buildAnnotatedString {
    val a = raw.indexOf('<'); val b = raw.indexOf('>')
    if (a < 0 || b < a) { append(raw); return@buildAnnotatedString }
    append(raw.substring(0, a))
    withStyle(SpanStyle(background = Fluvour.colors.accent.copy(alpha = 0.12f))) { append(raw.substring(a + 1, b)) }
    append(raw.substring(b + 1))
}

@Composable
private fun QuizOption(text: String, n: Int, sel: Int, checked: Boolean, correct: Int, target: Boolean, onPick: () -> Unit) {
    val c = Fluvour.colors
    val right = checked && n == correct
    val wrong = checked && n == sel && n != correct
    val dim = checked && !right && !wrong
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !checked, onClick = onPick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(20.dp).then(if (right) Modifier.background(c.accent, CircleShape) else Modifier.border(1.5.dp, if (!checked && n == sel) c.accent else c.line, CircleShape)), Alignment.Center) {
                if (right) Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = c.onPrimary)
                else if (!checked && n == sel) Box(Modifier.size(9.dp).background(c.accent, CircleShape))
            }
            Text(text, Modifier.weight(1f), color = if (wrong || dim) c.mute else c.ink, fontFamily = if (target) targetFont() else serifFont(), fontSize = if (target) 20.sp else 18.sp,
                textDecoration = if (wrong) TextDecoration.LineThrough else null)
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable
private fun QuizResult(qs: List<Question>, ok: List<Boolean>, vm: QuizViewModel, onAgain: () -> Unit, onClose: () -> Unit) {
    val c = Fluvour.colors
    val k = ok.count { it }; val n = qs.size
    val pc = if (n == 0) 0 else k * 100 / n
    val msg = when { pc == 100 -> "Every one right."; pc >= 75 -> "Nicely done. A few to revisit."; pc >= 50 -> "A solid start. Revisit the misses."; else -> "These need another look. That is normal." }
    val added = remember { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onClose, "Your result", closeIcon = true)
        Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RingPercent(pc)
            H2(msg, Modifier.padding(top = 12.dp), align = TextAlign.Center)
            Sm("$k of $n right.", Modifier.padding(top = 4.dp))
        }
        Column(Modifier.padding(top = 20.dp)) {
            qs.forEachIndexed { j, q ->
                val right = ok.getOrNull(j) == true
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(20.dp).then(if (right) Modifier.background(c.accent, CircleShape) else Modifier.border(1.dp, c.line, CircleShape)), Alignment.Center) {
                        Icon(if (right) Icons.Outlined.Check else Icons.Outlined.Close, null, Modifier.size(11.dp), tint = if (right) c.onPrimary else c.mute)
                    }
                    Column(Modifier.weight(1f)) {
                        TargetText(q.word, size = 18.sp)
                        Sm(QuizEngine.shortMeaning(q.meaning), size = 12.5.sp)
                    }
                    if (!right) SmallButton(if (j in added) "Added" else "Add back", { if (j !in added) { added.add(j); vm.addBack(q.cardId) } }, soft = j in added)
                }
                HorizontalDivider(color = c.line)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton("Try again", onAgain, Modifier.weight(1f), outlined = true)
            FButton("Done", onClose, Modifier.weight(1f))
        }
    }
}
