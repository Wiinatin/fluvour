package app.fluvour.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import app.fluvour.data.BookmarkEntity
import app.fluvour.data.HighlightEntity
import app.fluvour.data.ReadMode
import app.fluvour.data.ReadPrefs
import app.fluvour.data.ReadTheme
import app.fluvour.reading.Token
import app.fluvour.reading.WordSplitter
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The reader's building blocks: selectable text, scroll layout, page layout, selection bar, options and notes sheets.
// Positions are always (paragraph number, character inside that paragraph), so they survive a change of font size or screen.

/** Highlight colours offered in the selection bar. Stored as ARGB ints, so this list can change later without breaking old highlights. */
val HighlightPalette = listOf(
    Color(0xFFFFE066), Color(0xFF8FD694), Color(0xFF7DB9F2), Color(0xFFF59BBB), Color(0xFFFFB45E), Color(0xFFB69CF0)
)

/** Where the reader is: paragraph, character in it, and how far through the text (0..1). */
data class Anchor(val para: Int, val ch: Int, val percent: Float)

/** A selected range inside one paragraph (`end` exclusive). */
data class Sel(val para: Int, val start: Int, val end: Int)

/** The part of one paragraph that sits on a page: `[start, end)`. */
data class Slice(val para: Int, val start: Int, val end: Int)

/** One sortable number for a position, so "is this bookmark on this page" is a range check. */
fun posKey(para: Int, ch: Int): Long = para * 1_000_000L + ch

/** Everything the text composables need, gathered in one place. Lambdas are read through `rememberUpdatedState`, so a new instance each recomposition is fine. */
class ReaderCtx(
    val texts: List<String>,
    val tokens: List<List<Token>>,
    val style: TextStyle,
    val highlights: Map<Int, List<HighlightEntity>>,
    val sel: Sel?,
    val lookupWord: String?,
    val lookupPara: Int?,
    val onTap: (para: Int, word: String, sentence: String, lemma: String?, reading: String?, sentenceStart: Int, sentenceEnd: Int) -> Unit,
    val onBlankTap: () -> Unit,
    val onSelect: (Sel) -> Unit
)

/**
 * One paragraph, or the slice `[from, to)` of it when a page cuts it. Tap a word to look it up; press and hold, then drag, to select
 * words (a long press on an existing highlight selects that highlight). Highlights and the selection are painted behind the text.
 */
@Composable
fun ReaderText(x: ReaderCtx, para: Int, from: Int, to: Int, modifier: Modifier = Modifier) {
    val c = Fluvour.colors
    val full = x.texts[para]
    val text = full.substring(from, to)
    val tokens = x.tokens.getOrNull(para).orEmpty()
    val hl = x.highlights[para].orEmpty()
    val xs by rememberUpdatedState(x)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var tapped by remember { mutableStateOf<Token?>(null) }
    val haptic = LocalHapticFeedback.current
    val alpha = if (c.paper.luminance() < 0.3f) 0.42f else 0.62f

    val shown = remember(text, hl, x.sel, tapped, x.lookupWord, x.lookupPara, c.accent, alpha) {
        buildAnnotatedString {
            append(text)
            for (h in hl) {
                val s = (h.startCh - from).coerceIn(0, text.length); val e = (h.endCh - from).coerceIn(0, text.length)
                if (e > s) addStyle(SpanStyle(background = Color(h.color).copy(alpha = alpha)), s, e)
            }
            x.sel?.takeIf { it.para == para }?.let {
                val s = (it.start - from).coerceIn(0, text.length); val e = (it.end - from).coerceIn(0, text.length)
                if (e > s) addStyle(SpanStyle(background = c.accent.copy(alpha = 0.35f)), s, e)
            }
            val t = tapped
            if (x.lookupWord != null && x.lookupPara == para && t != null && t.start >= from && t.end <= to && full.substring(t.start, t.end) == x.lookupWord)
                addStyle(SpanStyle(background = c.accent.copy(alpha = 0.18f)), t.start - from, t.end - from)
        }
    }

    Text(
        shown, style = x.style, onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth()
            // Tap: look the word up. An empty onLongPress tells this detector that a long press is not a tap.
            .pointerInput(tokens, from) {
                detectTapGestures(onLongPress = { }, onTap = { pos ->
                    val l = layout ?: return@detectTapGestures
                    val abs = from + l.getOffsetForPosition(pos)
                    val word = WordSplitter.wordAt(tokens, abs)
                    if (word == null) { xs.onBlankTap(); return@detectTapGestures }
                    tapped = word
                    val (ss, se) = WordSplitter.sentenceSpan(full, abs)
                    xs.onTap(para, full.substring(word.start, word.end), full.substring(ss, se), word.lemma, word.reading, ss, se)
                })
            }
            // Long press, then drag: select from the pressed word to the word under the finger.
            .pointerInput(tokens, from) {
                var a0 = -1; var a1 = -1
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val l = layout ?: return@detectDragGesturesAfterLongPress
                        val abs = from + l.getOffsetForPosition(pos)
                        val old = xs.highlights[para].orEmpty().firstOrNull { abs >= it.startCh && abs < it.endCh }
                        val w = WordSplitter.wordAt(tokens, abs)
                        when {
                            old != null -> { a0 = old.startCh; a1 = old.endCh }
                            w != null -> { a0 = w.start; a1 = w.end }
                            else -> { a0 = -1; return@detectDragGesturesAfterLongPress }
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        xs.onSelect(Sel(para, a0, a1))
                    },
                    onDrag = { change, _ ->
                        if (a0 < 0) return@detectDragGesturesAfterLongPress
                        change.consume()
                        val l = layout ?: return@detectDragGesturesAfterLongPress
                        val w = WordSplitter.wordAt(tokens, from + l.getOffsetForPosition(change.position)) ?: return@detectDragGesturesAfterLongPress
                        xs.onSelect(Sel(para, minOf(a0, w.start), maxOf(a1, w.end)))
                    }
                )
            }
    )
}

// ---------------------------------------------------------------------------------------------
// Scroll layout
// ---------------------------------------------------------------------------------------------

/**
 * The text as one long scroll. `header` and `footer` are each one list item (title and coverage on top, chapter buttons below),
 * so paragraph `i` is list item `i + 1`.
 */
@Composable
fun ScrollBody(
    x: ReaderCtx, anchor: Anchor, starts: IntArray, total: Int,
    jump: Anchor?, onJumpDone: () -> Unit, onAnchor: (Anchor) -> Unit, onRange: (Long, Long) -> Unit,
    header: @Composable () -> Unit, footer: @Composable () -> Unit
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (anchor.para > 0) anchor.para + 1 else 0)
    val lastPara = x.texts.lastIndex
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: list.firstVisibleItemIndex) }
            .collect { (first, last) ->
                val p = (first - 1).coerceIn(0, lastPara)
                val lastP = (last - 1).coerceIn(p, lastPara)
                val atEnd = last >= lastPara + 2
                onAnchor(Anchor(p, 0, if (atEnd) 1f else starts[p] / total.toFloat()))
                onRange(posKey(p, 0), posKey(lastP + 1, 0))
            }
    }
    LaunchedEffect(jump) { jump?.let { list.scrollToItem(it.para.coerceIn(0, lastPara) + 1); onJumpDone() } }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp)) {
        item { header() }
        itemsIndexed(x.texts, key = { i, _ -> "p$i" }) { i, t -> Box(Modifier.padding(vertical = 8.dp)) { ReaderText(x, i, 0, t.length) } }
        item { footer() }
    }
}

// ---------------------------------------------------------------------------------------------
// Page layout
// ---------------------------------------------------------------------------------------------

/**
 * Splits the paragraphs into pages that exactly fill a screen of `widthPx` x `heightPx`, by measuring them with the same text style
 * the page draws with. A paragraph that does not fit is cut between two lines and continues on the next page.
 */
private fun paginate(texts: List<String>, measurer: TextMeasurer, style: TextStyle, widthPx: Int, heightPx: Float, gapPx: Float): List<List<Slice>> {
    val pages = ArrayList<List<Slice>>()
    var cur = ArrayList<Slice>()
    var used = 0f
    for ((pi, text) in texts.withIndex()) {
        val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx))
        var from = 0
        for (l in 0 until layout.lineCount) {
            val h = layout.getLineBottom(l) - layout.getLineTop(l)
            val gap = if (l == 0 && cur.isNotEmpty()) gapPx else 0f
            if (used + gap + h > heightPx && (cur.isNotEmpty() || l > from)) {
                if (l > from) cur.add(Slice(pi, layout.getLineStart(from), layout.getLineEnd(l - 1)))
                pages.add(cur); cur = ArrayList(); used = h; from = l
            } else used += gap + h
        }
        if (layout.lineCount > from) cur.add(Slice(pi, layout.getLineStart(from), layout.getLineEnd(layout.lineCount - 1)))
    }
    if (cur.isNotEmpty()) pages.add(cur)
    return pages
}

/** Index of the page that contains the position (the last page that starts at or before it). */
private fun pageOf(pages: List<List<Slice>>, para: Int, ch: Int): Int {
    val k = posKey(para, ch)
    var best = 0
    for (i in pages.indices) { val s = pages[i].first(); if (posKey(s.para, s.start) <= k) best = i else break }
    return best
}

/**
 * The text as swipeable pages. Pages are rebuilt (off the main thread) when the font size, spacing or screen size change,
 * and the reader lands on the page that holds the position it was at.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagesBody(
    x: ReaderCtx, anchor: Anchor, starts: IntArray, total: Int, title: String, nav: ChapterNav?, onChapter: (String) -> Unit,
    jump: Anchor?, onJumpDone: () -> Unit, onAnchor: (Anchor) -> Unit, onRange: (Long, Long) -> Unit
) {
    val c = Fluvour.colors
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val dir = LocalLayoutDirection.current
    val headH = 28.dp; val footH = 52.dp; val padV = 6.dp; val padH = 22.dp; val gap = 10.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - padH * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - headH - footH - padV * 2 - 8.dp).toPx() }   // 8 dp of slack so a line is never clipped
        val gapPx = with(density) { gap.toPx() }
        val pages by produceState<List<List<Slice>>?>(null, x.texts, x.style, widthPx, heightPx) {
            value = null
            value = withContext(Dispatchers.Default) {
                paginate(x.texts, TextMeasurer(resolver, density, dir, 0), x.style, widthPx, heightPx, gapPx)   // own measurer, no shared cache
            }
        }
        val pg = pages
        if (pg == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp)) }; return@BoxWithConstraints }
        if (pg.isEmpty()) return@BoxWithConstraints
        key(pg) {
            val pager = rememberPagerState(initialPage = pageOf(pg, anchor.para, anchor.ch)) { pg.size }
            LaunchedEffect(pager) {
                snapshotFlow { pager.currentPage }.collect { p ->
                    val s = pg[p].first()
                    onAnchor(Anchor(s.para, s.start, if (p == pg.lastIndex) 1f else (starts[s.para] + s.start) / total.toFloat()))
                    onRange(posKey(s.para, s.start), if (p < pg.lastIndex) pg[p + 1].first().let { posKey(it.para, it.start) } else Long.MAX_VALUE)
                }
            }
            LaunchedEffect(jump) { jump?.let { pager.scrollToPage(pageOf(pg, it.para, it.ch)); onJumpDone() } }
            val cur = pager.currentPage
            Column(Modifier.fillMaxSize().padding(vertical = padV)) {
                Box(Modifier.fillMaxWidth().height(headH).padding(horizontal = padH), contentAlignment = Alignment.CenterStart) {
                    Text(title, color = c.mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { it }) { p ->
                    Column(Modifier.fillMaxSize().padding(horizontal = padH)) {
                        pg[p].forEachIndexed { i, s ->
                            if (i > 0) Spacer(Modifier.height(gap))
                            ReaderText(x, s.para, s.start, s.end)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(footH).padding(horizontal = padH), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (cur == 0 && nav?.prevId != null) SmallButton("Previous", { onChapter(nav.prevId) }, soft = true)
                    }
                    val pct = if (cur == pg.lastIndex) 100 else ((starts[pg[cur].first().para] + pg[cur].first().start) * 100L / total).toInt()
                    Text("${cur + 1} / ${pg.size}  ·  $pct%", color = c.mute, fontSize = 12.sp)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (cur == pg.lastIndex && nav?.nextId != null) SmallButton("Next", { onChapter(nav.nextId) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Selection bar, options, bookmarks and highlights list
// ---------------------------------------------------------------------------------------------

/** Floating bar shown while words are selected: pick a colour to highlight, or look up, copy, add a note, remove. */
@Composable
fun SelectionBar(
    text: String, existing: HighlightEntity?, onColor: (Color) -> Unit, onLookup: () -> Unit, onCopy: () -> Unit,
    onNote: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier
) {
    val c = Fluvour.colors
    Surface(modifier.padding(12.dp), shape = RoundedCornerShape(18.dp), color = c.card, shadowElevation = 10.dp, border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), color = c.mute, fontSize = 13.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClose, Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Close", Modifier.size(18.dp), tint = c.ink) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HighlightPalette.forEach { col ->
                    val on = existing?.color == col.toArgb()
                    Box(Modifier.size(34.dp).clip(CircleShape).background(col).border(if (on) 2.5.dp else 1.dp, if (on) c.ink else c.line, CircleShape).clickable { onColor(col) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BarAction(Icons.Outlined.Search, "Look up", onLookup)
                BarAction(Icons.Outlined.ContentCopy, "Copy", onCopy)
                if (existing != null) {
                    BarAction(Icons.Outlined.Edit, if (existing.note.isNullOrBlank()) "Note" else "Edit note", onNote)
                    BarAction(Icons.Outlined.Delete, "Remove", onRemove)
                }
            }
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = Fluvour.colors
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, Modifier.size(20.dp), tint = c.ink)
        Text(label, color = c.mute, fontSize = 11.sp)
    }
}

/** A row of exclusive choices (a small segmented control). */
@Composable
fun Choices(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Fluvour.colors
    Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.background(if (on) c.primary else c.card).clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (on) c.onPrimary else c.ink, fontSize = 13.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Reading options: layout (scroll or pages), page colour, text size, line spacing, and the reading-aid switch. All saved. */
@Composable
fun ReaderOptionsSheet(prefs: ReadPrefs, onChange: (ReadPrefs) -> Unit, aid: Boolean, onAid: (Boolean) -> Unit, aidLabel: String, onClose: () -> Unit) {
    FSheet(onClose) {
        SheetHeader("Reading", onClose)
        SettingRow("Layout", Modifier.padding(top = 8.dp), sub = if (prefs.mode == ReadMode.Pages) "Swipe sideways to turn the page" else "Scroll down", trailing = {
            Choices(listOf("Scroll", "Pages"), prefs.mode.ordinal) { onChange(prefs.copy(mode = ReadMode.values()[it])) }
        })
        SettingRow("Page colour", trailing = {
            Choices(listOf("App", "Sepia", "Night"), prefs.theme.ordinal) { onChange(prefs.copy(theme = ReadTheme.values()[it])) }
        })
        SettingRow("Text size", trailing = {
            FStepper(listOf("XS", "S", "M", "L", "XL")[prefs.size], { onChange(prefs.copy(size = (prefs.size - 1).coerceAtLeast(0))) }, { onChange(prefs.copy(size = (prefs.size + 1).coerceAtMost(4))) })
        })
        SettingRow("Line spacing", trailing = {
            FStepper(listOf("Tight", "Normal", "Loose")[prefs.spacing], { onChange(prefs.copy(spacing = (prefs.spacing - 1).coerceAtLeast(0))) }, { onChange(prefs.copy(spacing = (prefs.spacing + 1).coerceAtMost(2))) })
        })
        SwitchRow(aidLabel, aid, onAid, sub = "Shown above words")
    }
}

/** Bookmarks and highlights of the open text. Tap one to jump to it. */
@Composable
fun NotesSheet(
    bookmarks: List<BookmarkEntity>, highlights: List<HighlightEntity>, texts: List<String>,
    onJump: (para: Int, ch: Int) -> Unit, onDeleteBookmark: (String) -> Unit, onDeleteHighlight: (String) -> Unit, onClose: () -> Unit
) {
    val c = Fluvour.colors
    var tab by remember { mutableIntStateOf(0) }
    FSheet(onClose, tall = true) {
        SheetHeader("Bookmarks and highlights", onClose)
        Box(Modifier.padding(vertical = 12.dp)) { Choices(listOf("Bookmarks (${bookmarks.size})", "Highlights (${highlights.size})"), tab) { tab = it } }
        if (tab == 0) {
            if (bookmarks.isEmpty()) Sm("No bookmarks yet. Tap the bookmark icon at the top to save your place.")
            bookmarks.forEach { b ->
                NoteRow(null, b.snippet.ifBlank { "(blank)" }, "${(b.percent * 100).toInt()}% through", { onJump(b.para, b.ch) }, { onDeleteBookmark(b.id) })
            }
        } else {
            if (highlights.isEmpty()) Sm("No highlights yet. Press and hold a word, drag to select more, then pick a colour.")
            highlights.forEach { h ->
                NoteRow(Color(h.color), h.text, h.note, { onJump(h.para, h.startCh) }, { onDeleteHighlight(h.id) })
            }
        }
    }
}

@Composable
private fun NoteRow(dot: Color?, title: String, sub: String?, onClick: () -> Unit, onDelete: () -> Unit) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) Box(Modifier.padding(end = 12.dp).size(14.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            TargetText(title, size = 16.sp, maxLines = 3)
            if (!sub.isNullOrBlank()) Sm(sub, Modifier.padding(top = 2.dp))
        }
        IconButton(onDelete, Modifier.size(40.dp)) { Icon(Icons.Outlined.Delete, "Delete", Modifier.size(18.dp), tint = c.mute) }
    }
}
