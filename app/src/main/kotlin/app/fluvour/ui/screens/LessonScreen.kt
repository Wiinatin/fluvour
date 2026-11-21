package app.fluvour.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.path.*
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/**
 * One unit's lesson and drills (route "lesson/{unitId}", learning path step 7), copied from the mockup's lesson() and question() (drill kind).
 * Chunks first (tap a word to see its meaning), then the drills. With [drillsOnly] it opens straight on the drills.
 */
@Composable
fun LessonScreen(unitId: String, drillsOnly: Boolean, onClose: () -> Unit, vm: LessonViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(unitId, drillsOnly) { vm.load(unitId, drillsOnly) }
    val base = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)
    when (ui.phase) {
        LessonPhase.LOADING -> Column(base, Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Fluvour.colors.accent, trackColor = Fluvour.colors.line, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Sm("Writing your lesson. This takes a few seconds the first time.", Modifier.padding(top = 14.dp), align = TextAlign.Center)
        }
        LessonPhase.ERROR -> Column(base, Arrangement.Center, Alignment.CenterHorizontally) {
            H2(ui.message ?: "The lesson could not be loaded.", align = TextAlign.Center)
            FButton("Try again", { vm.load(unitId, drillsOnly, fresh = true) }, Modifier.padding(top = 20.dp))
            FButton("Back", onClose, Modifier.padding(top = 10.dp), outlined = true)
        }
        LessonPhase.LESSON -> LessonChunks(ui, vm, onClose, base)
        LessonPhase.DRILLS -> DrillView(ui, vm, onClose, base)
    }
}

@Composable
private fun TopBar(left: String, right: String, fraction: Float, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Outlined.Close, "Close", onClose)
        Sm(left, Modifier.weight(1f), align = TextAlign.Center)
        Sm(right, Modifier.widthIn(min = 44.dp), align = TextAlign.End)
    }
    HairlineProgress(fraction, Modifier.padding(top = 6.dp), height = 3.dp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonChunks(ui: LessonUi, vm: LessonViewModel, onClose: () -> Unit, base: Modifier) {
    val c = Fluvour.colors
    val lesson = ui.lesson ?: return
    val chunk = lesson.chunks[ui.chunk]
    val last = ui.chunk == lesson.chunks.lastIndex
    Column(base) {
        TopBar("Chunk ${ui.chunk + 1} of ${lesson.chunks.size}", "Unit ${ui.unit?.order ?: ""}", (ui.chunk + 1) / lesson.chunks.size.toFloat(), onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 22.dp)) {
            Eyebrow("Tap a word")
            FCard(Modifier.fillMaxWidth().padding(top = 10.dp), padding = 20.dp) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunk.words.forEachIndexed { i, w ->
                        val on = i == ui.word
                        Text(
                            w.text, Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.pickWord(i) }.padding(horizontal = 3.dp),
                            color = if (on) c.accent else c.ink, fontSize = 28.sp, lineHeight = 38.sp,
                            textDecoration = if (on) TextDecoration.Underline else null, fontFamily = serifFont()
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(chunk.en, Modifier.weight(1f), color = c.mute, fontSize = 15.sp)
                    IconBtn(Icons.Outlined.VolumeUp, "Listen", { vm.speak(chunk.jp) }, tint = c.accent)
                }
                val w = chunk.words.getOrNull(ui.word)
                Sm(if (w != null && w.gloss.isNotEmpty()) "${w.text} = ${w.gloss}" else "", Modifier.padding(top = 10.dp).heightIn(min = 22.dp), size = 14.sp)
            }
            Sm("Pattern: ${chunk.pattern}", Modifier.padding(top = 16.dp), size = 13.5.sp)
        }
        FButton(if (last) "Start drills" else "Next chunk", vm::nextChunk, Modifier.fillMaxWidth().padding(vertical = 14.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrillView(ui: LessonUi, vm: LessonViewModel, onClose: () -> Unit, base: Modifier) {
    val c = Fluvour.colors
    val lesson = ui.lesson ?: return
    val d = ui.drill ?: return
    Column(base) {
        TopBar("${ui.mastered} / ${ui.masteryTotal} learned", "${ui.correct} correct", ui.progress, onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 22.dp)) {
            when (d) {
                is BuildDrill -> {
                    Eyebrow("Build the sentence")
                    H2(d.prompt, Modifier.padding(top = 8.dp, bottom = 14.dp), size = 22.sp)
                    FlowRow(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) { ui.tray.forEach { Chip(it, false) { vm.toggleChip(it) } } }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.chips.forEach { Chip(it, it in ui.tray) { vm.toggleChip(it) } }
                    }
                }
                is ChooseDrill -> {
                    val heard = d.audio
                    Eyebrow(if (heard != null) "Listen" else if ('＿' in d.prompt) "Fill the blank" else "Choose")
                    if (heard != null) {
                        // a listening drill plays itself when it appears; the Japanese text stays hidden until the answer is checked
                        LaunchedEffect(ui.index) { vm.speak(heard) }
                        FCard(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), padding = 20.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (ui.checked) Text(heard, Modifier.weight(1f), color = c.ink, fontSize = 28.sp, fontFamily = serifFont()) else Spacer(Modifier.weight(1f))
                                IconBtn(Icons.Outlined.VolumeUp, "Listen again", { vm.speak(heard) }, tint = c.accent)
                            }
                        }
                    }
                    H2(d.prompt, Modifier.padding(top = 8.dp, bottom = 14.dp), size = 22.sp)
                    d.options.forEachIndexed { i, o ->
                        val right = ui.checked && i == d.answer
                        val wrong = ui.checked && ui.choice == i && i != d.answer
                        val picked = !ui.checked && ui.choice == i
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape)
                                .background(if (right || picked) c.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, shape)
                                .border(1.dp, if (right || picked) c.accent else c.line, shape).clickable { vm.choose(i) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(o, Modifier.weight(1f), color = if (wrong) c.mute else c.ink, fontSize = 18.sp, textDecoration = if (wrong) TextDecoration.LineThrough else null)
                            if (right) Icon(Icons.Outlined.Check, "Correct", Modifier.size(18.dp), tint = c.accent)
                            if (wrong) Icon(Icons.Outlined.Close, "Your answer", Modifier.size(18.dp), tint = c.mute)
                        }
                    }
                }
                is TypedDrill -> {
                    Eyebrow(if (ui.proof) "Final check: type it" else "Type it")
                    d.jp?.let { jp ->
                        FCard(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), padding = 20.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(jp, Modifier.weight(1f), color = c.ink, fontSize = 34.sp, fontFamily = serifFont())
                                IconBtn(Icons.Outlined.VolumeUp, "Listen", { vm.speak(jp) }, tint = c.accent)
                            }
                        }
                    }
                    Sm(d.prompt, Modifier.padding(top = 8.dp), size = 14.sp)
                    UnderlineField("Your answer", ui.text, vm::type, placeholder = "Type in English", singleLine = true, readOnly = ui.checked)
                }
            }
            if (ui.checked) {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    if (ui.grading) {
                        Text("Checking...", color = c.accent, fontSize = 14.sp)
                    } else {
                        Text(if (ui.lastOk) "✓ Correct." else "✗ Not quite.", color = c.accent, fontSize = 14.sp)
                        Sm(if (d is TypedDrill && ui.verdictWhy.isNotBlank()) ui.verdictWhy else d.why, Modifier.padding(top = 4.dp), color = c.ink, size = 14.sp)
                        if (d is TypedDrill && !ui.lastOk) Sm("Accepted: ${d.accepted.first()}", Modifier.padding(top = 4.dp), size = 13.sp)
                        if (!ui.lastOk && d.target != null) Sm("Saved with this card.", Modifier.padding(top = 6.dp), size = 12.sp)
                    }
                }
            }
        }
        if (ui.checked) FButton(if (ui.grading) "Checking..." else "Continue", { if (vm.next()) onClose() }, Modifier.fillMaxWidth().padding(vertical = 14.dp))
        else FButton("Check", vm::check, Modifier.fillMaxWidth().padding(vertical = 14.dp), enabled = ui.ready)
    }
}

@Composable
private fun Chip(text: String, used: Boolean, onClick: () -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(12.dp)
    Text(
        text, Modifier.clip(shape).background(if (used) c.line.copy(alpha = 0.4f) else c.card, shape).border(1.dp, if (used) c.line else c.accent, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        color = if (used) c.mute else c.ink, fontSize = 20.sp
    )
}
