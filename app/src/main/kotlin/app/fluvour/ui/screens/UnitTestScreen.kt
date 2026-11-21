package app.fluvour.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.path.*
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/**
 * One unit's graduation test (route "unittest/{unitId}", learning path step 8), copied from the mockup's question() (test kind) and result().
 * The check's own number of questions (it follows the unit), no feedback while answering, everything graded at the end. The pass mark graduates the unit
 * (and any unit without a check that this one covers); a retake writes new questions.
 */
@Composable
fun UnitTestScreen(unitId: String, onClose: () -> Unit, vm: UnitTestViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(unitId) { vm.start(unitId) }
    var confirmLeave by remember { mutableStateOf(false) }
    val base = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)
    BackHandler(enabled = ui.phase == TestPhase.QUESTION) { confirmLeave = true }
    if (confirmLeave) LeaveDialog(onStay = { confirmLeave = false }, onLeave = { confirmLeave = false; onClose() })
    when (ui.phase) {
        TestPhase.LOADING -> Column(base, Arrangement.Center, Alignment.CenterHorizontally) {
            val pr = ui.progress
            val shown by androidx.compose.animation.core.animateFloatAsState(if (pr.total > 0) pr.done / pr.total.toFloat() else 0f, label = "testBuild")
            if (pr.total > 0) Text("${pr.done} / ${pr.total}", color = Fluvour.colors.ink, fontSize = 40.sp, fontFamily = serifFont())
            // the AI answers in one piece, so until the first check there is nothing to count: the bar keeps moving instead
            if (pr.done > 0) HairlineProgress(shown, Modifier.fillMaxWidth(0.7f).padding(top = 14.dp), height = 6.dp)
            else androidx.compose.material3.LinearProgressIndicator(color = Fluvour.colors.accent, trackColor = Fluvour.colors.line, modifier = Modifier.fillMaxWidth(0.7f).padding(top = 14.dp))
            Sm(if (pr.step.isEmpty()) "Writing your test." else pr.step, Modifier.padding(top = 14.dp), align = TextAlign.Center)
            Sm("Questions that pass the checks are counted. This takes up to half a minute.", Modifier.padding(top = 4.dp), size = 12.5.sp, align = TextAlign.Center)
        }
        TestPhase.GRADING -> Column(base, Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Fluvour.colors.accent, trackColor = Fluvour.colors.line, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Sm("Grading your answers.", Modifier.padding(top = 14.dp), align = TextAlign.Center)
        }
        TestPhase.ERROR -> Column(base, Arrangement.Center, Alignment.CenterHorizontally) {
            H2(ui.message ?: "The test could not be loaded.", align = TextAlign.Center)
            FButton("Try again", { vm.start(unitId, resume = true) }, Modifier.padding(top = 20.dp))
            FButton("Back", onClose, Modifier.padding(top = 10.dp), outlined = true)
        }
        TestPhase.QUESTION -> QuestionView(ui, vm, onLeave = { confirmLeave = true }, base)
        TestPhase.RESULT -> ResultView(ui, vm, unitId, onClose, base)
    }
}

@Composable
private fun LeaveDialog(onStay: () -> Unit, onLeave: () -> Unit) {
    val c = Fluvour.colors
    AlertDialog(
        onDismissRequest = onStay, containerColor = c.card, titleContentColor = c.ink, textContentColor = c.mute,
        title = { Text("Leave the test?") }, text = { Text("Your answers are lost. A new test is written next time.") },
        confirmButton = { TextButton(onLeave) { Text("Leave", color = c.accent) } },
        dismissButton = { TextButton(onStay) { Text("Stay", color = c.ink) } }
    )
}

@Composable
private fun TestTopBar(left: String, fraction: Float, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Outlined.Close, "Close", onClose)
        Sm(left, Modifier.weight(1f), align = TextAlign.Center)
        Spacer(Modifier.widthIn(min = 44.dp))
    }
    HairlineProgress(fraction, Modifier.padding(top = 6.dp), height = 3.dp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionView(ui: TestUi, vm: UnitTestViewModel, onLeave: () -> Unit, base: Modifier) {
    val c = Fluvour.colors
    val test = ui.test ?: return
    val d = ui.question?.drill ?: return
    val total = test.questions.size
    val last = ui.index == total - 1
    val flagged = ui.index in ui.flagged
    Column(base) {
        TestTopBar("Question ${ui.index + 1} of $total", ui.index / total.toFloat(), onLeave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 22.dp)) {
            when (d) {
                is BuildDrill -> {
                    Eyebrow("Build the sentence")
                    H2(d.prompt, Modifier.padding(top = 8.dp, bottom = 14.dp), size = 22.sp)
                    FlowRow(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) { ui.tray.forEach { TestChip(it, false) { vm.toggleChip(it) } } }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.chips.forEach { TestChip(it, it in ui.tray) { vm.toggleChip(it) } }
                    }
                }
                is ChooseDrill -> {
                    Eyebrow("Choose")
                    H2(d.prompt, Modifier.padding(top = 8.dp, bottom = 14.dp), size = 22.sp)
                    d.options.forEachIndexed { i, o ->
                        val picked = ui.choice == i
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape)
                                .background(if (picked) c.accent.copy(alpha = 0.12f) else Color.Transparent, shape)
                                .border(1.dp, if (picked) c.accent else c.line, shape).clickable { vm.choose(i) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) { Text(o, Modifier.weight(1f), color = c.ink, fontSize = 18.sp) }
                    }
                }
                is TypedDrill -> {
                    Eyebrow("Type the meaning")
                    d.jp?.let { jp ->
                        FCard(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), padding = 20.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(jp, Modifier.weight(1f), color = c.ink, fontSize = 34.sp, fontFamily = serifFont())
                                IconBtn(Icons.Outlined.VolumeUp, "Listen", { vm.speak(jp) }, tint = c.accent)
                            }
                        }
                    }
                    Sm(d.prompt, Modifier.padding(top = 8.dp), size = 14.sp)
                    UnderlineField("Your answer", ui.text, vm::type, placeholder = "Type in English", singleLine = true, readOnly = false)
                    Sm("Typed answers are graded at the end of the test.", Modifier.fillMaxWidth().padding(top = 8.dp), size = 12.sp, align = TextAlign.Center)
                }
            }
            Text(
                if (flagged) "Flagged. Tap to undo." else "Flag this question",
                Modifier.padding(top = 22.dp).clickable { vm.toggleFlag() }.padding(vertical = 6.dp),
                color = if (flagged) c.accent else c.mute, fontSize = 13.sp
            )
            Sm("Flag a question that looks wrong. It will not count against you. Up to 3 per test (${ui.flagged.size} used).", size = 12.sp)
        }
        FButton(if (last) "Finish test" else "Submit", vm::submit, Modifier.fillMaxWidth().padding(vertical = 14.dp), enabled = ui.ready)
    }
}

@Composable
private fun ResultView(ui: TestUi, vm: UnitTestViewModel, unitId: String, onClose: () -> Unit, base: Modifier) {
    val c = Fluvour.colors
    val r = ui.result ?: return
    val f = ui.finish
    val unit = ui.unit
    Column(base) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Outlined.Close, "Close", onClose)
            Sm("Unit ${unit?.order ?: ""} test", Modifier.weight(1f), align = TextAlign.Center)
            Spacer(Modifier.widthIn(min = 44.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("${r.score} / ${r.total}", Modifier.fillMaxWidth().padding(top = 36.dp), color = c.ink, fontSize = 56.sp, fontFamily = serifFont(), textAlign = TextAlign.Center)
            Text(
                if (r.passed) "Passed. Unit ${unit?.order ?: ""} graduated." else "Not yet. You need ${r.pass} to pass.",
                Modifier.fillMaxWidth().padding(top = 8.dp), color = c.accent, fontSize = 16.sp, textAlign = TextAlign.Center
            )
            Column(Modifier.padding(top = 24.dp)) {
                ResultRow("Pass mark", "${r.pass} / ${r.total}")
                ResultRow("From this unit", "${r.currentRight} / ${r.currentTotal}")
                if (r.olderTotal > 0) ResultRow("From older units", "${r.olderRight} / ${r.olderTotal}")
                ResultRow("Sent to review", "${f?.sentToReview ?: 0} items")
                if (r.flagged.isNotEmpty()) ResultRow("Flagged", "${r.flagged.size}")
            }
            val note = if (r.passed) {
                if (f?.nextOrder != null) "Unit ${f.nextOrder}, \"${f.nextTitle}\", is now open." else "You have finished this path."
            } else "Retake right now. You will get fresh questions. Missed items are already in review."
            Column(Modifier.fillMaxWidth().padding(top = 20.dp).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp)) { Sm(note, color = c.ink, size = 14.sp) }
        }
        if (r.passed) {
            FButton("Back to the path", { vm.showPathList(); onClose() }, Modifier.fillMaxWidth().padding(top = 14.dp))
            FButton("Unit ${unit?.order ?: ""} details", onClose, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), outlined = true)
        } else {
            FButton("Retake test", { vm.start(unitId) }, Modifier.fillMaxWidth().padding(top = 14.dp))
            FButton("Back to the unit", onClose, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), outlined = true)
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    val c = Fluvour.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, color = c.ink, fontSize = 14.sp)
            Text(value, Modifier.weight(1f), color = c.mute, fontSize = 14.sp, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable
private fun TestChip(text: String, used: Boolean, onClick: () -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(12.dp)
    Text(
        text, Modifier.clip(shape).background(if (used) c.line.copy(alpha = 0.4f) else c.card, shape).border(1.dp, if (used) c.line else c.accent, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        color = if (used) c.mute else c.ink, fontSize = 20.sp
    )
}
