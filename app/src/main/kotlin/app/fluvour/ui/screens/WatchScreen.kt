package app.fluvour.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.data.WatchHistoryEntity
import app.fluvour.ui.*
import app.fluvour.ui.sample.Sample
import app.fluvour.ui.sample.forLang
import app.fluvour.ui.theme.Fluvour
import coil.compose.AsyncImage
import java.text.BreakIterator
import java.util.Locale

/** Splits text into (piece, isWord). Uses the platform word breaker, which has dictionaries for Thai and Japanese. */
fun splitWords(text: String, lang: String): List<Pair<String, Boolean>> {
    val it = BreakIterator.getWordInstance(Locale.forLanguageTag(lang))
    it.setText(text)
    val out = mutableListOf<Pair<String, Boolean>>()
    var start = it.first(); var end = it.next()
    while (end != BreakIterator.DONE) {
        val piece = text.substring(start, end)
        out.add(piece to piece.any { ch -> ch.isLetterOrDigit() })
        start = end; end = it.next()
    }
    return out
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return if (s >= 3600) "${s / 3600}:${(s % 3600 / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Watch tab. Once a video is open, the tab simply is the player: pausing changes nothing on screen, leaving the tab pauses it,
 * and coming back (or reopening the app) finds the same video at the same place.
 * The search icon in the slim bar opens the search page (keyboard up, back arrow beside the bar); the video keeps playing underneath
 * and nothing is closed or lost. Only leaving the tab pauses it.
 * The search page is the one way in: type words to search YouTube, paste a link to open it, leave it empty to see your history.
 * The player belongs to the app root (see FluvourRoot), not to this screen, so it survives tab switches.
 */
@Composable
fun WatchScreen(lang: String, onAddCard: (String, String, String, String?) -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val pui by vm.ui.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val item = pui.item
    var browsing by rememberSaveable { mutableStateOf(false) }   // the search page is showing on top of a loaded video
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.resumeLast() }
    // Leaving the Watch tab no longer pauses: Root shows the floating window (or nothing, if it was paused) via vm.onLeaveWatch().
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A new video finished loading: show it, and leave the search bar empty for next time.
    LaunchedEffect(item?.sourceUrl) { if (item != null) { browsing = false; text = ""; vm.clearSearch() } }
    BackHandler(enabled = browsing) { focus.clearFocus(); browsing = false }

    fun go(t: String) {
        if (t.isBlank()) return
        focus.clearFocus()
        val link = vm.submit(t)
        if (link && item?.sourceUrl == t.trim()) browsing = false   // the same video is already loaded: just go back to it
    }

    AnimatedContent(
        targetState = item != null && !browsing, modifier = Modifier.fillMaxSize(), label = "watch",
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }
    ) { showPlayer ->
        if (showPlayer && item != null) {
            Column(Modifier.fillMaxSize().statusBarsPadding().ambient().languageMark()) {
                // slim title bar: the page itself does not scroll, only the subtitle list does
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), color = c.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconBtn(Icons.Outlined.Search, "Search or open another video", { browsing = true })
                }
                WatchVideo(vm, pui, item, onAddCard)
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).ambient().languageMark()) {
                val onField: (String) -> Unit = { v ->
                    val pasted = vm.looksLikeLink(v) && v.length - text.length > 3   // a paste, not typing
                    text = v
                    if (v.isBlank()) vm.clearSearch()
                    if (pasted) go(v)
                }
                if (browsing) {
                    // Opened from the player: a plain search screen. Back arrow returns to the video, the cursor is already in the bar.
                    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus(); keyboard?.show() } }
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 22.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Icons.Outlined.ArrowBack, "Back to video", { focus.clearFocus(); browsing = false })
                        SearchField(text, onField, "Search YouTube or paste a link", Modifier.weight(1f).focusRequester(searchFocus), onSearch = { go(text) })
                    }
                } else {
                    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp)) {
                        Eyebrow("Video")
                        H1("Watch", Modifier.padding(top = 8.dp))
                        SearchField(text, onField, "Search YouTube or paste a link", Modifier.padding(top = 14.dp), onSearch = { go(text) })
                    }
                }
                Column(Modifier.padding(horizontal = 22.dp)) {
                    pui.error?.let { Sm(it, Modifier.padding(top = 10.dp), color = c.accent) }
                    when {
                        pui.loading -> Sm("Opening...", Modifier.padding(top = 18.dp))
                        text.isBlank() -> HistoryList(hist, current = item?.sourceUrl, onOpen = { h -> vm.open(h.url); if (h.url == item?.sourceUrl) browsing = false }, onRemove = { vm.removeHistory(it.id) })
                        search.loading -> Sm("Searching...", Modifier.padding(top = 18.dp))
                        search.error != null -> Sm(search.error!!, Modifier.padding(top = 18.dp), color = c.accent)
                        search.hits == null -> Sm("Press search on the keyboard to look this up on YouTube.", Modifier.padding(top = 18.dp))
                        search.hits!!.isEmpty() -> Sm("Nothing found. Try other words.", Modifier.padding(top = 18.dp))
                        else -> search.hits!!.forEach { h ->
                            VideoRow(h.thumbUrl, h.title, listOfNotNull(h.uploader, if (h.durationMs > 0) clock(h.durationMs) else null).joinToString(" \u00B7 "),
                                badge = if (h.durationMs > 0) clock(h.durationMs) else null, on = h.url == item?.sourceUrl, frac = 0f,
                                onClick = { focus.clearFocus(); vm.open(h.url); if (h.url == item?.sourceUrl) browsing = false })
                            HorizontalDivider(color = c.line)
                        }
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

/** Recent videos, latest first: thumbnail, title, channel, and how far you got. Tap to reopen at the saved place, swipe left to remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryList(items: List<WatchHistoryEntity>, current: String?, onOpen: (WatchHistoryEntity) -> Unit, onRemove: (WatchHistoryEntity) -> Unit) {
    val c = Fluvour.colors
    if (items.isEmpty()) { Sm("Videos you watch will be listed here, so you can pick up where you stopped.", Modifier.padding(top = 22.dp)); return }
    Eyebrow("Recent", Modifier.padding(top = 26.dp, bottom = 6.dp))
    items.forEach { h ->
        key(h.id) {
            val state = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(h); true } else false })
            SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Box(Modifier.fillMaxSize().padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Outlined.Delete, "Remove from history", Modifier.size(20.dp), tint = c.mute) }
            }) {
                val frac = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
                VideoRow(h.thumbUrl, h.title, listOfNotNull(h.uploader, if (h.durationMs > 0) "${clock(h.positionMs)} of ${clock(h.durationMs)}" else null).joinToString(" \u00B7 "),
                    badge = null, on = h.url == current, frac = frac, onClick = { onOpen(h) })
            }
            HorizontalDivider(color = c.line)
        }
    }
    Sm("Swipe a video left to remove it from the list.", Modifier.padding(top = 14.dp), size = 12.sp)
}

/** One video card, the same for search results and history: thumbnail (with length), title, a line of detail, optional progress. */
@Composable
private fun VideoRow(thumb: String?, title: String, sub: String, badge: String?, on: Boolean, frac: Float, onClick: () -> Unit) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(c.card)) {
            if (thumb != null) AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (badge != null) Text(badge, Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(c.ink.copy(alpha = 0.72f)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = c.paper, fontSize = 10.5.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (on) c.accent else c.ink, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Sm(sub, Modifier.padding(top = 3.dp), size = 12.sp)
            if (frac > 0f) Box(Modifier.padding(top = 6.dp)) { HairlineProgress(frac) }
        }
    }
}

/** Dictate: listen to a line, type it, see what you missed. UI only: the play button does nothing yet. */
@Composable
private fun DictateTab(lang: String, lines: List<String>, modifier: Modifier) {
    val c = Fluvour.colors
    var i by remember { mutableIntStateOf(0) }
    var typed by remember(i) { mutableStateOf("") }
    var checked by remember(i) { mutableStateOf(false) }
    val ref = remember(i, lang) { splitWords(lines[i], lang) }
    val pool = remember(typed, checked) { splitWords(typed, lang).filter { it.second }.map { it.first.lowercase() }.toMutableList() }
    Column(modifier) {
        Sm("Line ${i + 1} of ${lines.size}. Listen, then type what you hear.", Modifier.padding(top = 16.dp))
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RoundAction(Icons.Outlined.PlayArrow, "Play line", { /* TODO(audio): play the clip audio for this line */ }, size = 76.dp)
            Sm("Tap to listen again", Modifier.padding(top = 10.dp))
        }
        UnderlineField("What you heard", typed, { typed = it }, placeholder = "Type the line", font = targetFont(), size = 20.sp, minLines = 2, readOnly = checked)
        if (!checked) FButton("Check", { checked = true }, Modifier.fillMaxWidth().padding(top = 22.dp), enabled = typed.isNotBlank())
        else {
            val shown = buildAnnotatedString {
                val left = pool.toMutableList()
                ref.forEach { (p, w) ->
                    val hit = w && left.remove(p.lowercase())
                    if (w && !hit) withStyle(SpanStyle(background = c.accent.copy(alpha = 0.12f))) { append(p) } else append(p)
                }
            }
            val total = ref.count { it.second }
            val hits = ref.count { it.second } - shown.spanStyles.size
            Column(Modifier.padding(top = 18.dp)) {
                HorizontalDivider(color = c.line)
                Eyebrow("The line", Modifier.padding(top = 14.dp))
                Text(shown, Modifier.padding(top = 6.dp), color = c.ink, fontFamily = targetFont(), fontSize = 22.sp, lineHeight = 37.sp)
                Sm("$hits of $total words right.", Modifier.padding(top = 4.dp))
            }
            FButton(if (i + 1 < lines.size) "Next line" else "Start over", { i = if (i + 1 < lines.size) i + 1 else 0 }, Modifier.fillMaxWidth().padding(top = 18.dp))
        }
        Spacer(Modifier.height(30.dp))
    }
}
