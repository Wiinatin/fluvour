package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.sp
import app.fluvour.reading.HeisigEntry
import app.fluvour.reading.KanjiStrokes
import app.fluvour.reading.PadDrawing
import app.fluvour.ui.*
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.ui.theme.Fluvour

/**
 * Practice page for one Heisig kanji: write it as often as you like. Nothing is saved.
 *
 * Top to bottom: bar (close, frame number, previous / next), two small switches (Trace guide, Story), the keyword with the
 * story strip and a small stroke-order thumbnail, the large pad, Undo / Grid / Clear, then Submit and a counter.
 *
 * Submit clears the pad and raises the counter, so it feels like handing the page in. The drawing and the counter live here
 * (`remember`, keyed on the kanji): moving to another kanji starts a clean pad. The counter is kept per kanji across visits (`submits`). The two switch values
 * come from the caller (the settings store, step 5b) so they are remembered across visits.
 *
 * [entries] is the list the arrows move through (the filtered grid list); [startCh] is the kanji to open on.
 */
@Composable
fun HeisigPracticeScreen(
    entries: List<HeisigEntry>,
    startCh: String?,
    traceGuide: Boolean,
    onTraceGuide: (Boolean) -> Unit,
    story: Boolean,
    onStory: (Boolean) -> Unit,
    strokesFor: suspend (String) -> KanjiStrokes?,
    onClose: () -> Unit,
    tiers: Map<String, app.fluvour.reading.Tier> = emptyMap(),
    submits: Map<String, Int> = emptyMap(),
    onSubmit: (String) -> Unit = {}
) {
    val c = Fluvour.colors
    var ch by rememberSaveable { mutableStateOf(startCh ?: entries.firstOrNull()?.ch) }
    val index = entries.indexOfFirst { it.ch == ch }
    val entry = entries.getOrNull(index)
    val canPrev = index > 0
    val canNext = index in 0 until entries.lastIndex
    val go = { to: Int -> entries.getOrNull(to)?.let { ch = it.ch } }

    Column(Modifier.fillMaxSize().ambient().languageMark().statusBarsPadding().navigationBarsPadding().padding(bottom = 16.dp)) {
        PageBar(onClose, entry?.let { e -> "Frame ${e.frame}" + (tiers[e.ch]?.let { " · " + tierName(it) } ?: "") }, closeIcon = true, trailing = {
            IconBtn(Icons.Outlined.ChevronLeft, "Previous kanji", { if (canPrev) go(index - 1) }, Modifier.alpha(if (canPrev) 1f else 0.3f))
            IconBtn(Icons.Outlined.ChevronRight, "Next kanji", { if (canNext) go(index + 1) }, Modifier.alpha(if (canNext) 1f else 0.3f))
        })
        if (entry == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 40.dp)) {
                Sm("This kanji is not in the list.")
                LinkText("Close", onClose)
            }
            return@Column
        }
        // the drawing, the grid switch and the counter start fresh for every kanji
        var drawing by remember(entry.ch) { mutableStateOf(PadDrawing()) }
        var showGrid by remember { mutableStateOf(true) }
        val submitted = submits[entry.ch] ?: 0   // kept across visits, never reset
        val strokes by produceState<KanjiStrokes?>(null, entry.ch) { value = strokesFor(entry.ch) }
        val haptic = LocalHapticFeedback.current
        val storyText = entry.story1.ifBlank { entry.story2 }

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp)) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PracticePill("Trace guide", traceGuide, onTraceGuide)
                PracticePill("Story", story, onStory)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    H2(entry.keyword, size = 28.sp)
                    if (story && storyText.isNotBlank()) {
                        Text(
                            storyText, Modifier.padding(top = 4.dp), color = c.mute, fontSize = 12.5.sp, lineHeight = 18.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // kanji without stroke data simply have no thumbnail
                strokes?.let { StrokeOrderView(it, boxSize = 52.dp) }
            }
            // the pad takes the full width; on a short screen it shrinks (like Front does) before anything else gives way
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                val pad = minOf(maxWidth, maxHeight - 60.dp).coerceAtLeast(160.dp)
                val glyph = with(LocalDensity.current) { (pad * 0.78f).toSp() }
                WritingPad(
                    drawing, { drawing = it }, showGrid, { showGrid = it }, padSize = pad,
                    ghost = {
                        // the trace guide: the kanji in the normal ink colour, very faint (different from the accent ghost on the card back)
                        if (traceGuide) Box(Modifier.fillMaxSize(), Alignment.Center) {
                            TargetText(entry.ch, size = glyph, lineHeight = glyph, color = c.ink.copy(alpha = 0.18f), align = TextAlign.Center)
                        }
                    }
                )
            }
            FButton(
                "Submit",
                { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); drawing = PadDrawing(); onSubmit(entry.ch) },
                Modifier.fillMaxWidth().padding(top = 10.dp),
                enabled = !drawing.isEmpty
            )
            Sm(
                when (submitted) { 0 -> "Not submitted yet"; 1 -> "Submitted 1 time"; else -> "Submitted $submitted times" },
                Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center
            )
        }
    }
}

/** Small pill switch: accent outline and dot when on, `line` outline and mute text when off. At least 36dp high. */
@Composable
private fun PracticePill(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 36.dp).clip(shape).border(1.dp, if (on) c.accent else c.line, shape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).background(if (on) c.accent else c.mute, CircleShape))
        Text(label, fontSize = 13.sp, fontWeight = uiWeight(), color = if (on) c.ink else c.mute)
    }
}

/**
 * Loads the deck and the remembered switches, then shows [HeisigPracticeScreen]. Used by the route (grid taps) and by the overlay on the card back.
 * [query] is the grid's search text, so the arrows walk the same filtered list the grid showed; empty means the whole deck.
 */
@Composable
fun HeisigPracticeHost(startCh: String?, query: String, onClose: () -> Unit, vm: app.fluvour.ui.HeisigPracticeViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val all by vm.all.collectAsStateWithLifecycle()
    val trace by vm.traceGuide.collectAsStateWithLifecycle()
    val story by vm.story.collectAsStateWithLifecycle()
    val tiers by vm.tiers.collectAsStateWithLifecycle()
    val submits by vm.submits.collectAsStateWithLifecycle()
    val deck = all
    if (deck == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = Fluvour.colors.accent) }
        return
    }
    val entries = remember(deck, query, startCh) {
        val f = app.fluvour.reading.HeisigSearch.filter(deck, query, { it.ch }, { it.frame }, { it.keyword })
        if (startCh == null || f.any { it.ch == startCh }) f else deck
    }
    HeisigPracticeScreen(entries, startCh, trace, vm::setTraceGuide, story, vm::setStory, vm::strokes, onClose, tiers, submits, vm::addSubmit)
}
