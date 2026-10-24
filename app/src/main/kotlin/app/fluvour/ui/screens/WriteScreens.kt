package app.fluvour.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.sample.Sample
import app.fluvour.ui.sample.forLang
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.delay

/**
 * Journal (route "journal"): write a few lines, submit, see corrections. UI only: the corrections are canned for the sample
 * text (Sample.journalFixes) and "Add to cards" only toggles. Real corrections need the AI proxy.
 */
@Composable
fun JournalScreen(lang: String, onClose: () -> Unit) {
    val c = Fluvour.colors
    val sample = Sample.journalText.forLang(lang)
    val fixes = Sample.journalFixes.forLang(lang)
    var text by remember(lang) { mutableStateOf(sample) }
    var submitted by remember { mutableStateOf(false) }
    val added = remember { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        PageBar(onClose, if (submitted) "Corrections" else "Today's journal", closeIcon = true)
        if (!submitted) {
            H1("Your morning", Modifier.padding(top = 18.dp), size = 30.sp)
            Sm("Write a few lines in ${Languages.byCode(lang).name}. Mistakes are fine.", Modifier.padding(top = 6.dp))
            TextField(
                text, { text = it }, Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
                textStyle = TextStyle(fontFamily = targetFont(), fontSize = 23.sp, lineHeight = 41.sp, color = c.ink),
                placeholder = { Text("Start here", color = c.mute, fontFamily = targetFont(), fontSize = 23.sp) },
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = c.accent)
            )
            NotBuiltNote("A sample entry is filled in. Corrections are canned until the AI check is connected.")
            FButton("Submit", { if (text.isNotBlank()) submitted = true }, Modifier.fillMaxWidth().padding(vertical = 18.dp), enabled = text.isNotBlank())
        } else {
            val hits = fixes.withIndex().filter { text.contains(it.value.wrong) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                H1(if (hits.isNotEmpty()) "${hits.size} to fix" else "Nicely written", Modifier.padding(top = 18.dp), size = 30.sp)
                Text(buildAnnotatedString {
                    var rest = text
                    // swap each wrong phrase for its fix, marked
                    var out = ""
                    val marks = mutableListOf<Pair<Int, Int>>()
                    hits.forEach { (_, f) ->
                        val k = rest.indexOf(f.wrong)
                        if (k >= 0) { out += rest.substring(0, k); marks.add(out.length to out.length + f.right.length); out += f.right; rest = rest.substring(k + f.wrong.length) }
                    }
                    out += rest
                    append(out)
                    marks.forEach { (a, b) -> addStyle(SpanStyle(background = c.accent.copy(alpha = 0.12f)), a, b) }
                }, Modifier.padding(top = 14.dp), color = c.ink, fontFamily = targetFont(), fontSize = 24.sp, lineHeight = 43.sp)
                if (hits.isNotEmpty()) {
                    SectionEyebrow("What changed", top = 26.dp)
                    hits.forEach { (idx, f) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                TargetText(f.right, size = 18.sp)
                                Sm("Was ${f.wrong}. ${f.why}", size = 12.5.sp)
                            }
                            SmallButton(if (idx in added) "Added" else "Add to cards", { if (idx !in added) added.add(idx) }, soft = idx in added)
                        }
                        HorizontalDivider(color = c.line)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FButton("Edit entry", { submitted = false }, Modifier.weight(1f), outlined = true)
                FButton("Done", onClose, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Handwriting and practice sheet (route "handwriting/{mode}", mode = hw or ps).
 * UI only: the camera step is a mock frame with a shutter, "Print sheet" does nothing, and the result is canned.
 */
@Composable
fun HandwritingScreen(mode: String, lang: String, onClose: () -> Unit) {
    val c = Fluvour.colors
    var sheetMode by remember { mutableStateOf(mode) }
    var step by remember { mutableIntStateOf(1) }     // 1 words, 2 camera, 3 reading, 4 result
    var strokes by remember { mutableStateOf(false) }
    val words = Sample.words.forLang(lang).take(3)
    val ps = sheetMode == "ps"
    LaunchedEffect(step) { if (step == 3) { delay(1100); step = 4 } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        when (step) {
            1 -> {
                PageBar(onClose, if (ps) "Practice sheet" else "Handwriting", closeIcon = true)
                H1(if (ps) "Ruled for your words" else "Write these by hand", Modifier.padding(top = 18.dp), size = 30.sp)
                Sm(if (ps) "Three words from your review, with a faint guide to trace." else "One word per line on paper. The camera checks each one.", Modifier.padding(top = 6.dp))
                Column(Modifier.padding(top = 10.dp)) {
                    words.forEach { w ->
                        if (ps) Column {
                            TargetText("${w.word} ${w.word} ${w.word}", Modifier.padding(vertical = 16.dp), size = 32.sp, color = c.mute.copy(alpha = 0.55f), maxLines = 1)
                            HorizontalDivider(color = c.line)
                        } else FRow(w.word, subtitle = "${w.reading} · ${w.meaning}", titleFont = targetFont(), titleSize = 26.sp, trailing = null)
                    }
                }
                if (ps) LinkText("Write by hand instead", { sheetMode = "hw" }) else LinkText("See stroke order", { strokes = true })
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FButton("Print sheet (PDF)", { /* TODO(pdf): render the sheet to PDF */ }, Modifier.weight(1f), outlined = !ps)
                    if (!ps) FButton("Open camera", { step = 2 }, Modifier.weight(1f))
                }
            }
            2, 3 -> {
                PageBar({ step = 1 }, "Camera")
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.sky).border(1.dp, c.line, RoundedCornerShape(16.dp)), Alignment.Center) {
                    Column(Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.62f).border(1.dp, c.mute, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                        repeat(3) { HorizontalDivider(color = c.line) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = c.mute)
                    Sm(if (step == 3) "  Reading your handwriting" else "  One item per line. Keep the page flat.", size = 12.5.sp)
                }
                if (step == 2) Box(Modifier.padding(vertical = 18.dp).size(68.dp).align(Alignment.CenterHorizontally).border(2.dp, c.accent, CircleShape).clickable { step = 3 }, Alignment.Center) {
                    // TODO(camera): CameraX capture, then handwriting recognition
                    Box(Modifier.size(50.dp).background(c.accent, CircleShape))
                } else Spacer(Modifier.height(104.dp))
            }
            else -> {
                PageBar(onClose, "Your result", closeIcon = true)
                H1("Nicely written", Modifier.padding(top = 18.dp), size = 30.sp)
                Column(Modifier.padding(top = 14.dp)) {
                    words.forEachIndexed { n, w ->
                        val ok = n != 2
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(20.dp).then(if (ok) Modifier.background(c.accent, CircleShape) else Modifier.border(1.dp, c.line, CircleShape)), Alignment.Center) {
                                if (ok) Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = c.onPrimary)
                            }
                            Column { TargetText(w.word, size = 22.sp); Sm(if (ok) "Clear" else "Check the last stroke", size = 12.5.sp) }
                        }
                        HorizontalDivider(color = c.line)
                    }
                }
                NotBuiltNote("Sample result. Real checking needs the camera and handwriting recognition.")
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FButton("Try again", { step = 1 }, Modifier.weight(1f), outlined = true)
                    FButton("Done", onClose, Modifier.weight(1f))
                }
            }
        }
    }
    if (strokes) StrokeSheet(lang) { strokes = false }
}

/** Stroke-order sheet: draws the glyph's strokes one at a time. Paths are the prototype's SVG data on a 100 x 100 grid. */
@Composable
private fun StrokeSheet(lang: String, onClose: () -> Unit) {
    val c = Fluvour.colors
    val g = Sample.glyph.forLang(lang)
    val paths = remember(lang) { g.second.map { PathParser().parsePathString(it).toPath() } }
    var k by remember { mutableIntStateOf(0) }
    val progress = remember(k) { Animatable(0f) }
    LaunchedEffect(k) { progress.snapTo(0f); progress.animateTo(1f, tween(1000)) }
    FSheet(onClose) {
        SheetHeader("Stroke order", onClose)
        Canvas(Modifier.padding(vertical = 14.dp).size(210.dp).align(Alignment.CenterHorizontally)) {
            val s = size.width / 100f
            drawRect(c.card); drawRect(c.line, style = Stroke(1.dp.toPx()))
            scale(s, s, pivot = Offset.Zero) {
                val w = 5f
                paths.forEachIndexed { i, p ->
                    val st = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    when {
                        i < k -> drawPath(p, c.ink.copy(alpha = 0.75f), style = st)
                        i == k -> {
                            val m = PathMeasure(); m.setPath(p, false)
                            val part = Path(); m.getSegment(0f, m.length * progress.value, part, true)
                            drawPath(p, c.line, style = st); drawPath(part, c.accent, style = st)
                        }
                        else -> drawPath(p, c.line, style = st)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Stroke ${k + 1} of ${paths.size}", color = c.ink, fontSize = 15.5.sp); Sm("Follow the line from the start", size = 12.5.sp) }
            FStepper("", { if (k > 0) k-- }, { if (k < paths.lastIndex) k++ })
        }
    }
}
