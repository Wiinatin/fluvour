package app.fluvour.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.path.CurriculumUnit
import app.fluvour.path.PathState
import app.fluvour.path.TestPlan
import app.fluvour.path.TestRules
import app.fluvour.path.UnitNudge
import app.fluvour.path.UnitRow
import app.fluvour.path.UnitStatus
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.delay

/**
 * The "Path" sub-tab of Study (learning path step 6): the unit list, and one unit's detail. Copies the mockup's pathList() and detail().
 * Start lesson and Practice drills open the lesson route (step 7). Take or Retake test opens the unit test route (step 8), once the lesson is done.
 */
@Composable
fun ColumnScope.PathTab(vm: PathViewModel, onLesson: (unitId: String, drillsOnly: Boolean) -> Unit, onTest: (unitId: String) -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val notice by vm.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) { notice?.let { message = it; vm.clearNotice() } }
    LaunchedEffect(message) { if (message != null) { delay(2200); message = null } }

    val path = ui.path
    val row = open?.let { id -> ui.rows.firstOrNull { it.unit.id == id } }
    BackHandler(enabled = row != null) { vm.closeUnit() }

    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            path == null -> if (!ui.loading) Sm("No path for this language yet.", Modifier.padding(top = 20.dp))
            row != null && row.status != UnitStatus.LOCKED -> UnitDetail(
                row, path.title, lessonDone = row.unit.id in ui.lessonDone, mastered = row.unit.id in ui.mastered, onBack = vm::closeUnit,
                plan = TestRules.plan(ui.rows.map { it.unit }, row.unit), coveredBy = TestRules.coveredBy(ui.rows.map { it.unit }, row.unit),
                onLesson = { onLesson(row.unit.id, false) }, onDrills = { onLesson(row.unit.id, true) },
                onTest = { if (row.unit.id !in ui.lessonDone) message = "Finish the lesson first." else if (row.unit.id !in ui.mastered && row.status != UnitStatus.GRADUATED) message = "Practise until every word and sentence is learned first." else onTest(row.unit.id) }
            )
            else -> PathList(ui.rows, path.title, ui.graduated, ui.nudge, onOpen = { vm.openUnit(it.unit.id) },
                onLocked = { message = "Locked. Graduate the current unit first." })
        }
        message?.let { PathToast(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
private fun PathList(rows: List<UnitRow>, title: String, graduated: Int, nudge: UnitNudge?, onOpen: (UnitRow) -> Unit, onLocked: () -> Unit) {
    val c = Fluvour.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        H1(title, Modifier.padding(top = 16.dp), size = 28.sp)
        Sm("$graduated of ${rows.size} units graduated", Modifier.padding(top = 4.dp))
        HairlineProgress(if (rows.isEmpty()) 0f else graduated / rows.size.toFloat(), Modifier.padding(top = 12.dp, bottom = 16.dp), height = 6.dp)
        rows.forEachIndexed { i, r ->
            val locked = r.status == UnitStatus.LOCKED
            val current = r.status == UnitStatus.CURRENT
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).alpha(if (locked) 0.5f else 1f).clip(shape)
                    .background(if (current) c.accent.copy(alpha = 0.10f) else c.card, shape)
                    .border(1.dp, if (current) c.accent else c.line, shape)
                    .clickable { if (locked) onLocked() else onOpen(r) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(if (current) c.accent else Color0).border(1.dp, if (current || r.status == UnitStatus.GRADUATED) c.accent else c.line, CircleShape),
                    Alignment.Center
                ) {
                    when (r.status) {
                        UnitStatus.GRADUATED -> Icon(Icons.Outlined.Check, "Graduated", Modifier.size(16.dp), tint = c.accent)
                        UnitStatus.LOCKED -> Icon(Icons.Outlined.Lock, "Locked", Modifier.size(15.dp), tint = c.mute)
                        UnitStatus.CURRENT -> Text("${r.unit.order}", color = c.paper, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(r.unit.title, color = c.ink, fontFamily = serifFont(), fontSize = 17.sp, lineHeight = 22.sp)
                    Sm(if (locked) PathState.lockedLine(rows, i) else r.unit.canDo, Modifier.padding(top = 2.dp), size = 12.5.sp)
                }
                val badge = if (current) nudge?.badge else null
                if (badge != null) Box(Modifier.clip(RoundedCornerShape(9.dp)).background(c.accent).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(badge, color = c.paper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else if (!locked) ChevronGo()
            }
        }
    }
}

@Composable
private fun UnitDetail(row: UnitRow, pathTitle: String, lessonDone: Boolean, mastered: Boolean, onBack: () -> Unit, plan: TestPlan?, coveredBy: CurriculumUnit?, onLesson: () -> Unit, onDrills: () -> Unit, onTest: () -> Unit) {
    val c = Fluvour.colors
    val u = row.unit
    val done = row.status == UnitStatus.GRADUATED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("‹ $pathTitle", Modifier.clickable(onClick = onBack).padding(top = 10.dp, bottom = 12.dp), color = c.accent, fontSize = 13.sp)
        Eyebrow("Unit ${u.order} · ${u.level} · about ${u.minutes} min" + if (done) " · graduated" else "")
        H1(u.title, Modifier.padding(top = 6.dp), size = 28.sp)
        Sm("Goal: ${u.canDo}", Modifier.padding(top = 6.dp), size = 14.sp)
        Column(Modifier.padding(top = 14.dp)) {
            DetailRow("Words", "${u.words.size}")
            DetailRow("Grammar", u.grammar.joinToString(", "))
        }
        SectionEyebrow("Lesson")
        Sm("Written for you when you start it.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 14.sp)
        FButton(if (lessonDone) "Lesson done ✓ Replay" else "Start lesson", onLesson, Modifier.fillMaxWidth(), outlined = true)
        if (lessonDone) FButton(if (!mastered && !done) "Practice until learned" else "Practice again", onDrills, Modifier.fillMaxWidth().padding(top = 10.dp), outlined = true)
        if (plan != null) {
            SectionEyebrow("Unit check")
            DetailRow("${plan.total} questions", "${plan.pass} to pass")
            if (plan.units.size > 1) DetailRow("Covers", "Units ${TestRules.orders(plan.units)}")
            FButton(if (done) "Retake test" else "Take unit test", onTest, Modifier.fillMaxWidth().padding(top = 14.dp))
            Sm("Retake any time with fresh questions. Missed items go to review. The next unit opens when you pass.", Modifier.padding(top = 14.dp), size = 12.5.sp)
        } else {
            SectionEyebrow("Graduation")
            val later = coveredBy?.let { " The unit check comes after Unit ${it.order} and covers both." }.orEmpty()
            Sm(if (done) "Graduated.$later" else "This unit has no unit check. It graduates when every word and sentence is learned in the practice.$later", Modifier.padding(top = 6.dp), size = 14.sp)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = Fluvour.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, color = c.ink, fontSize = 14.sp)
            Text(value, Modifier.weight(1f), color = c.mute, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable
private fun PathToast(text: String, modifier: Modifier = Modifier) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(12.dp)
    Text(
        text, modifier.padding(bottom = 24.dp).clip(shape).background(c.card, shape).border(1.dp, c.accent, shape).padding(horizontal = 16.dp, vertical = 10.dp),
        color = c.ink, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent
