package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import app.fluvour.reading.HeisigSearch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.reading.HeisigEntry
import app.fluvour.reading.KanjiEntry
import app.fluvour.reading.KanjiStrokes
import app.fluvour.reading.Tier
import app.fluvour.reading.PadDrawing
import app.fluvour.srs.Rating
import app.fluvour.ui.*
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.ui.theme.Fluvour

/** Heisig study: the keyword comes first, you recall the kanji, then check it against the answer and rate yourself. */
@Composable
fun HeisigStudyScreen(onClose: () -> Unit, vm: HeisigStudyViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val reveal = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.reveal() }
    // The practice page opens as an overlay on top of the study column (which stays composed), so the YOU drawing is still here when you come back.
    var practiceCh by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = practiceCh != null) { practiceCh = null }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().ambient().languageMark().statusBarsPadding().navigationBarsPadding().padding(bottom = 16.dp)) {
        PageBar(onClose, "Heisig", closeIcon = true, trailing = {
            if (!ui.loading && !ui.finished && !ui.noDeck) Sm("${ui.answered + 1} / ${ui.answered + ui.left}", Modifier.padding(end = 14.dp))
        })
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            when {
                ui.loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                ui.noDeck -> Column(Modifier.weight(1f).fillMaxWidth().padding(top = 60.dp)) {
                    H1("No deck yet", accentSuffix = ".")
                    Sm("Import your Heisig deck in Settings, then come back.", Modifier.padding(top = 10.dp))
                    LinkText("Close", onClose, Modifier.padding(top = 18.dp))
                }
                ui.finished -> Column(Modifier.weight(1f).fillMaxWidth().padding(top = 60.dp)) {
                    H1(if (ui.answered == 0) "Nothing due" else "All done", accentSuffix = ".")
                    Sm(
                        if (ui.answered == 0) "No kanji are waiting. Come back later." else "${ui.answered} reviewed, ${ui.remembered} remembered.",
                        Modifier.padding(top = 10.dp)
                    )
                    if (ui.canLearnMore) FButton("Learn 5 more new kanji", vm::learnMore, Modifier.fillMaxWidth().padding(top = 22.dp), outlined = true)
                    LinkText("Close", onClose, Modifier.padding(top = 18.dp))
                }
                else -> {
                    val card = ui.current!!
                    // The drawing lives here, not in the view model (that one is rebuilt on every card), so it survives Front to Back.
                    // ui.answered is in the key so a card that comes straight back after "Again" starts with a clean pad.
                    var drawing by remember(card.ch, ui.answered) { mutableStateOf(PadDrawing()) }
                    var overlay by remember(card.ch, ui.answered) { mutableStateOf(false) }
                    var showGrid by remember { mutableStateOf(true) }
                    val strokes by produceState<KanjiStrokes?>(null, card.ch) { value = vm.strokes(card.ch) }
                    val tier by produceState(Tier.PLAIN, card.ch, ui.answered) { value = vm.tier(card.ch) }
                    HairlineProgress(ui.progress, Modifier.padding(top = 10.dp, bottom = 16.dp))
                    // No tap-to-reveal on the face: a tap on the pad must not reveal. Only the Show answer button does.
                    StudyFace(Modifier.fillMaxWidth().weight(1f), onClick = null) {
                        if (!ui.showAnswer) Front(card, drawing, { drawing = it }, showGrid, { showGrid = it })
                        else Back(card, ui.kanji, strokes, drawing, showGrid, overlay, tier, { practiceCh = card.ch }) { overlay = it }
                    }
                    if (!ui.showAnswer) {
                        FButton("Show answer", reveal, Modifier.fillMaxWidth().padding(top = 18.dp))
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Rating.AGAIN to "Wrong", Rating.HARD to "Close", Rating.GOOD to "Good", Rating.EASY to "Perfect").forEach { (r, label) ->
                                StudyRatingButton(label, ui.previews[r]?.let(::formatInterval) ?: "", r == Rating.GOOD, Modifier.weight(1f)) { vm.rate(r) }
                            }
                        }
                    }
                }
            }
        }
    }
    practiceCh?.let { ch -> Box(Modifier.fillMaxSize().background(c.page)) { HeisigPracticeHost(ch, "", { practiceCh = null }) } }
    }
}

@Composable
private fun ColumnScope.Front(card: HeisigEntry, drawing: PadDrawing, onDrawing: (PadDrawing) -> Unit, showGrid: Boolean, onShowGrid: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        // 280dp is the design size; shrink on short screens so the pad and its button row still fit
        val pad = minOf(280.dp, maxWidth, maxHeight - 190.dp).coerceAtLeast(160.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Eyebrow("Frame ${card.frame}")
            H2(card.keyword, Modifier.padding(top = 10.dp), size = 38.sp, align = TextAlign.Center)
            Sm("Write it from memory with your finger, then check.", Modifier.padding(top = 10.dp, bottom = 18.dp), align = TextAlign.Center)
            WritingPad(drawing, onDrawing, showGrid, onShowGrid, padSize = pad)
        }
    }
}

@Composable
private fun Back(card: HeisigEntry, kanji: KanjiEntry?, strokes: KanjiStrokes?, drawing: PadDrawing, showGrid: Boolean, overlay: Boolean, tier: Tier, onPractice: () -> Unit, onOverlay: (Boolean) -> Unit) {
    val c = Fluvour.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = (maxWidth - 12.dp) / 2
            val glyph = with(LocalDensity.current) { (side * 0.72f).toSp() }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("You")
                    PadPreview(drawing, size = side, showGrid = showGrid, ghost = {
                        if (overlay) Box(Modifier.fillMaxSize(), Alignment.Center) { TargetText(card.ch, size = glyph, lineHeight = glyph, color = c.accent.copy(alpha = 0.4f), align = TextAlign.Center) }
                    })
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Answer")
                    val shape = RoundedCornerShape(16.dp)
                    Box(Modifier.size(side).clip(shape).background(c.page, shape).border(1.dp, c.line, shape), Alignment.Center) {
                        TargetText(card.ch, size = glyph, lineHeight = glyph, color = TierPalette.tint(tier) ?: c.ink, align = TextAlign.Center)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOverlay(!overlay) }, verticalAlignment = Alignment.CenterVertically) {
            Text("Show answer over my drawing", Modifier.weight(1f), color = c.ink, fontSize = 15.sp)
            Switch(overlay, null, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.page))
        }
        FButton("Practice writing", onPractice, Modifier.fillMaxWidth(), outlined = true)
        // numbered stroke animation, tap to replay; kanji without stroke data simply have no box (the ANSWER box already shows the glyph)
        strokes?.let { st ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StrokeOrderView(st, boxSize = 96.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Stroke order", color = c.accent, fontSize = 15.sp)
                    Text("Tap to replay", color = c.mute, fontSize = 14.sp)
                    Text("${st.d.size} strokes", color = c.mute, fontSize = 14.sp)
                }
            }
        }
        Text("${card.keyword}  ·  Heisig ${card.frame}", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        kanji?.let { k ->
            val bits = listOfNotNull(k.strokes?.takeIf { strokes == null }?.let { "$it strokes" }, k.on.takeIf { it.isNotEmpty() }?.let { "On  " + it.joinToString("  ") }, k.kun.takeIf { it.isNotEmpty() }?.let { "Kun  " + it.joinToString("  ") })
            bits.forEach { Text(it, color = c.mute, fontSize = 14.sp, fontFamily = targetFont()) }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (card.components.isNotBlank()) { Eyebrow("Components"); Text(card.components, color = c.ink, fontSize = 14.sp) }
            if (card.story1.isNotBlank()) { Eyebrow("Story", Modifier.padding(top = 6.dp)); Text(card.story1, color = c.ink, fontSize = 14.sp) }
            if (card.story2.isNotBlank()) Text(card.story2, color = c.mute, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StudyRatingButton(label: String, interval: String, emphasized: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(12.dp)
    val fg = if (emphasized) c.onPrimary else c.ink
    Column(
        modifier.clip(shape).then(if (emphasized) Modifier.background(c.accent, shape) else Modifier.background(c.card, shape).border(1.dp, c.line, shape))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
        Text(interval, fontSize = 11.5.sp, color = if (emphasized) fg.copy(alpha = 0.9f) else c.mute)
    }
}

/**
 * The "Heisig" tab on the Review screen (Japanese only): progress, the Study button, then a search box and a grid of every Heisig kanji.
 * Tapping a tile calls [onPractice] with that kanji and the current search text. The search text and the scroll position survive leaving this screen and coming back.
 */
@Composable
fun HeisigTab(onStudy: () -> Unit, onPractice: (String, String) -> Unit = { _, _ -> }, vm: HeisigHomeViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    LaunchedEffect(Unit) { vm.refresh() }
    val st by vm.stats.collectAsStateWithLifecycle()
    val all by vm.entries.collectAsStateWithLifecycle()
    val tiers by vm.tiers.collectAsStateWithLifecycle()
    val due by vm.due.collectAsStateWithLifecycle()
    val s = st
    var query by rememberSaveable { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    // one clock for the whole grid; it stands still while scrolling, and when no Gold, Jade or Radiant tile exists
    val clock = rememberTierClock(active = !gridState.isScrollInProgress && tiers.values.any { it >= Tier.GOLD })
    Column(Modifier.fillMaxSize().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            s == null -> CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
            s.total == 0 -> Sm("Import your Heisig deck in Settings (Heisig deck) to study it here.")
            else -> {
                H2("${s.learned} of ${s.total} kanji learned")
                Sm("${s.due} due  ·  ${s.newLeftToday} new today  ·  ${s.learning} in learning")
                FButton("Study", onStudy, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = s.due + s.newLeftToday > 0)
                PracticeSearchBox(query, { query = it }, Modifier.padding(top = 6.dp))
                val shown = remember(all, query) { HeisigSearch.filter(all, query, { it.ch }, { it.frame }, { it.keyword }) }
                if (query.isNotBlank()) Sm(
                    when (shown.size) { 0 -> "No match"; 1 -> "1 match"; else -> "${shown.size} matches" },
                    size = 12.sp
                )
                // A new search starts at the top of the grid.
                LaunchedEffect(query) { gridState.scrollToItem(0) }
                LazyVerticalGrid(
                    GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f), gridState,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.ch }) { e -> TierTile(e.ch, e.keyword, tiers[e.ch] ?: Tier.PLAIN, { onPractice(e.ch, query) }, clock = clock, due = e.ch in due) }
                }
            }
        }
    }
}

@Composable
private fun PracticeSearchBox(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Fluvour.colors; val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().height(46.dp).clip(shape).background(c.card, shape)
            .border(1.dp, if (focused) c.accent else c.line, shape).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = c.mute)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search kanji to practise", fontSize = 14.sp, color = c.mute)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, singleLine = true,
                textStyle = TextStyle(color = c.ink, fontSize = 14.sp), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
            )
        }
        if (value.isNotEmpty()) IconBtn(Icons.Outlined.Close, "Clear search", { onChange("") })
    }
}
