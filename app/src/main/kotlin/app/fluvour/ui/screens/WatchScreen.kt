package app.fluvour.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.data.LikedVideoEntity
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchScreen(lang: String, onAddCard: (String, String, String, String?) -> Unit, vm: PlayerViewModel = hiltViewModel(), sv: SuggestViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val pui by vm.ui.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    val likedList by vm.liked.collectAsStateWithLifecycle()
    val isLiked by vm.currentLiked.collectAsStateWithLifecycle()
    val likedIds = remember(likedList) { likedList.map { it.id }.toSet() }
    var libTab by rememberSaveable { mutableIntStateOf(0) }   // idle page: 0 Recent, 1 Liked
    val search by vm.search.collectAsStateWithLifecycle()
    val suggest by vm.suggest.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val su by sv.ui.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val item = pui.item
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    OrientationLockRelease()   // the full-screen buttons lock the orientation; this lets go of it again
    var browsing by rememberSaveable { mutableStateOf(false) }   // the search page is showing on top of a loaded video
    var text by rememberSaveable { mutableStateOf("") }
    var clipsOpen by rememberSaveable { mutableStateOf(false) }   // the clips view (roadmap 4.2) replaces the body of the page
    var pickLevel by remember { mutableStateOf(false) }
    // language switched: close the search page, the clips view and the search text of the old language
    var shownLang by rememberSaveable { mutableStateOf(lang) }
    LaunchedEffect(lang) { if (shownLang != lang) { shownLang = lang; browsing = false; clipsOpen = false; text = "" } }
    LaunchedEffect(Unit) { sv.onWatchShown() }   // no auto-open of the last video: after a restart or a language switch Watch shows the list
    // The clips view is open and the row is ready: make sure the clips are there (does nothing when they already are, or a run is going).
    LaunchedEffect(clipsOpen, su.row) { if (clipsOpen && su.row is ClipsRow.Ready) sv.openClips() }
    // Leaving the Watch tab no longer pauses: Root shows the floating window (or nothing, if it was paused) via vm.onLeaveWatch().
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A new video finished loading: show it, and leave the search bar empty for next time.
    LaunchedEffect(item?.sourceUrl) { if (item != null) { browsing = false; clipsOpen = false; text = ""; vm.clearSearch() } }
    BackHandler(enabled = browsing) { focus.clearFocus(); browsing = false }
    BackHandler(enabled = clipsOpen) { clipsOpen = false }   // declared last, so it goes first: back from the clips view returns to the page under it
    if (pickLevel) LevelSheet(lang, null, onPick = { sv.setLevel(lang, it); pickLevel = false }, onClose = { pickLevel = false })

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
        if (showPlayer && item != null && landscape) {
            // immersion: full-screen video with captions on it (no dictation or shadowing here, those stay on the portrait page)
            WatchLandscape(vm, pui, item, onAddCard)
        } else if (showPlayer && item != null) {
            Column(Modifier.fillMaxSize().statusBarsPadding().ambient().languageMark()) {
                // slim title bar: the page itself does not scroll, only the subtitle list does
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeartButton(isLiked) { vm.toggleLike() }
                    Text(item.title, Modifier.weight(1f).padding(start = 2.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 34.dp),
                        color = c.ink, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    IconBtn(Icons.Outlined.Search, "Search or open another video", { browsing = true })
                }
                WatchVideo(vm, pui, item, onAddCard)
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).ambient().languageMark()) {
                val onField: (String) -> Unit = { v ->
                    val pasted = vm.looksLikeLink(v) && v.length - text.length > 3   // a paste, not typing
                    text = v
                    vm.typing(v)
                    if (v.isBlank()) vm.clearSearch()
                    if (pasted) go(v)
                }
                if (clipsOpen) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Icons.Outlined.ArrowBack, "Back", { clipsOpen = false })
                        Text("Clips for you", Modifier.weight(1f), color = c.ink, fontSize = 17.sp)
                        IconBtn(Icons.Outlined.Refresh, "Get new clips", { sv.reshuffle() })
                    }
                } else if (browsing) {
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
                        clipsOpen -> ClipsBody(su, opening = pui.loading, onOpen = { url -> focus.clearFocus(); vm.open(url); if (url == item?.sourceUrl) { clipsOpen = false; browsing = false } })
                        pui.loading -> Sm("Opening...", Modifier.padding(top = 18.dp))
                        text.isBlank() -> {
                            if (recent.isNotEmpty() && browsing) {
                                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Eyebrow("Recent searches", Modifier.weight(1f))
                                    LinkText("Clear", { vm.forgetRecent() })
                                }
                                recent.take(6).forEach { q -> SuggestRow(q, Icons.Outlined.History, onPick = { text = q; go(q) }, onRemove = { vm.forgetRecent(q) }) }
                            }
                            ClipsEntry(su.row, onOpen = { clipsOpen = true }, onSetLevel = { pickLevel = true })
                            LibraryTabs(libTab, likedList.size) { libTab = it }
                            if (libTab == 0) HistoryList(hist, current = item?.sourceUrl, likedIds = likedIds, onOpen = { h -> vm.open(h.url); if (h.url == item?.sourceUrl) browsing = false }, onRemove = { vm.removeHistory(it.id) })
                            else LikedList(likedList, current = item?.sourceUrl, onOpen = { l -> vm.open(l.url); if (l.url == item?.sourceUrl) browsing = false }, onRemove = { vm.unlike(it.id) })
                        }
                        search.loading -> Sm("Searching...", Modifier.padding(top = 18.dp))
                        search.error != null -> Sm(search.error!!, Modifier.padding(top = 18.dp), color = c.accent)
                        search.hits == null -> {
                            if (suggest.isEmpty()) Sm("Press search on the keyboard to look this up on YouTube.", Modifier.padding(top = 18.dp))
                            else suggest.forEach { q -> SuggestRow(q, Icons.Outlined.Search, onPick = { text = q; go(q) }, onFill = { text = q; vm.typing(q) }) }
                        }
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
private fun HistoryList(items: List<WatchHistoryEntity>, current: String?, likedIds: Set<String>, onOpen: (WatchHistoryEntity) -> Unit, onRemove: (WatchHistoryEntity) -> Unit) {
    val c = Fluvour.colors
    if (items.isEmpty()) { Sm("Videos you watch will be listed here, so you can pick up where you stopped.", Modifier.padding(top = 16.dp)); return }
    items.forEach { h ->
        key(h.id) {
            val state = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(h); true } else false })
            SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Box(Modifier.fillMaxSize().padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Outlined.Delete, "Remove from history", Modifier.size(20.dp), tint = c.mute) }
            }) {
                val frac = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
                VideoRow(h.thumbUrl, h.title, listOfNotNull(h.uploader, if (h.durationMs > 0) "${clock(h.positionMs)} of ${clock(h.durationMs)}" else null).joinToString(" \u00B7 "),
                    badge = null, on = h.url == current, frac = frac, onClick = { onOpen(h) }, liked = h.id in likedIds)
            }
            HorizontalDivider(color = c.line)
        }
    }
    Sm("Swipe a video left to remove it from the list.", Modifier.padding(top = 14.dp), size = 12.sp)
}

/** One video card, the same for search results and history: thumbnail (with length), title, a line of detail, optional progress. */
@Composable
private fun VideoRow(thumb: String?, title: String, sub: String, badge: String?, on: Boolean, frac: Float, onClick: () -> Unit, tags: String? = null, liked: Boolean = false) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(c.card)) {
            if (thumb != null) AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (badge != null) Text(badge, Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(c.ink.copy(alpha = 0.72f)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = c.paper, fontSize = 10.5.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (on) c.accent else c.ink, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank() || liked) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sub.isNotBlank()) Sm(sub, Modifier.weight(1f, fill = false), size = 12.sp)
                if (liked) Icon(Icons.Filled.Favorite, "Liked", Modifier.padding(start = 6.dp).size(12.dp), tint = c.accent)
            }
            if (tags != null) Sm(tags, Modifier.padding(top = 2.dp), size = 12.sp)   // clips only: `C1 · C2 knows 98% · you know 94%`
            if (frac > 0f) Box(Modifier.padding(top = 6.dp)) { HairlineProgress(frac) }
        }
    }
}

/** The one quiet row above Recent. Nothing at all when the row is hidden (no error text, no spinner). It only reads what [SuggestViewModel] already has. */
@Composable
private fun ClipsEntry(row: ClipsRow, onOpen: () -> Unit, onSetLevel: () -> Unit) {
    val text = when (row) {
        ClipsRow.Hidden -> return
        ClipsRow.NeedsLevel -> "Set your level to get clips"
        is ClipsRow.Ready -> row.words.let { w ->
            when {
                w.isEmpty() -> "Clips for you"
                w.size == 1 -> "Clips for ${w[0]}"
                w.size == 2 -> "Clips for ${w[0]} and ${w[1]}"
                else -> "Clips for ${w[0]}, ${w[1]} and ${w.size - 2} more"
            }
        }
    }
    Sm("$text  \u203A", Modifier.fillMaxWidth().clickable(onClick = if (row is ClipsRow.Ready) onOpen else onSetLevel).padding(top = 18.dp, bottom = 4.dp))
}

/**
 * The clips view: one muted progress line while subtitles are read, then the clips as the same cards as search results, with a tag line.
 * Clips arrive batch by batch. A part that failed leaves one muted line, never a red error.
 */
@Composable
private fun ClipsBody(su: ClipsUi, opening: Boolean, onOpen: (String) -> Unit) {
    val c = Fluvour.colors
    if (opening) Sm("Opening...", Modifier.padding(top = 14.dp), size = 12.sp)
    else if (su.loading) Sm(if (su.total == 0) "Looking for clips..." else "Reading subtitles, ${su.done.coerceAtMost(su.total)} of ${su.total}", Modifier.padding(top = 14.dp), size = 12.sp)
    su.clips.forEach { k ->
        key(k.url) {
            val len = if (k.durationMs > 0) clock(k.durationMs) else null
            VideoRow(k.thumbUrl, k.title, listOfNotNull(k.uploader, len).joinToString(" \u00B7 "), badge = len, on = false, frac = 0f, onClick = { onOpen(k.url) }, tags = k.tags)
            HorizontalDivider(color = c.line)
        }
    }
    su.note?.let { Sm(it, Modifier.padding(top = 14.dp), size = 12.sp) }
    if (su.clips.isEmpty() && !su.loading && su.note == null && su.row !is ClipsRow.Ready) Sm("No clips right now. Try the reload button.", Modifier.padding(top = 14.dp), size = 12.sp)
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

/** Idle page header: two quiet text tabs, the active one in ink with a thin accent underline. */
@Composable
private fun LibraryTabs(tab: Int, likedCount: Int, onTab: (Int) -> Unit) {
    val c = Fluvour.colors
    Row(Modifier.padding(top = 22.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Recent", if (likedCount > 0) "Liked $likedCount" else "Liked").forEachIndexed { i, label ->
            val on = i == tab
            Text(label, Modifier.clip(RoundedCornerShape(6.dp)).clickable { onTab(i) }
                .drawBehind { if (on) drawRect(c.accent, Offset(10.dp.toPx(), size.height - 2.dp.toPx()), Size(size.width - 20.dp.toPx(), 2.dp.toPx())) }
                .padding(horizontal = 10.dp).padding(top = 6.dp, bottom = 8.dp),
                color = if (on) c.ink else c.mute, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** Liked videos, newest first. Same rows as Recent. Tap to open, swipe left to un-like. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LikedList(items: List<LikedVideoEntity>, current: String?, onOpen: (LikedVideoEntity) -> Unit, onRemove: (LikedVideoEntity) -> Unit) {
    val c = Fluvour.colors
    if (items.isEmpty()) { Sm("Tap the heart on a video to keep it here.", Modifier.padding(top = 16.dp)); return }
    items.forEach { l ->
        key(l.id) {
            val state = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(l); true } else false })
            SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Box(Modifier.fillMaxSize().padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Outlined.FavoriteBorder, "Remove from liked videos", Modifier.size(20.dp), tint = c.mute) }
            }) {
                VideoRow(l.thumbUrl, l.title, listOfNotNull(l.uploader, if (l.durationMs > 0) clock(l.durationMs) else null).joinToString(" \u00B7 "),
                    badge = null, on = l.url == current, frac = 0f, onClick = { onOpen(l) })
            }
            HorizontalDivider(color = c.line)
        }
    }
    Sm("Swipe a video left to remove it from Liked.", Modifier.padding(top = 14.dp), size = 12.sp)
}

/** The heart in the player's title bar: outline and muted, filled in the accent colour once liked, with a small pop. */
@Composable
internal fun HeartButton(liked: Boolean, what: String = "liked videos", onClick: () -> Unit) {
    val c = Fluvour.colors
    val haptic = LocalHapticFeedback.current
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked && !seen) { pop.animateTo(1.28f, tween(90)); pop.animateTo(1f, spring(dampingRatio = 0.45f)) }
        seen = liked
    }
    IconButton({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }, Modifier.size(44.dp)) {
        Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (liked) "Remove from $what" else "Add to $what",
            Modifier.size(21.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = if (liked) c.accent else c.mute)
    }
}

/** One search suggestion or recent search: tap to search it. The arrow puts it in the box to keep typing; the cross forgets a recent search. */
@Composable
private fun SuggestRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onPick: () -> Unit, onFill: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 14.dp).size(20.dp), tint = c.mute)
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), color = c.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onFill != null) IconBtn(Icons.Outlined.NorthWest, "Use this text", onFill, tint = c.mute)
        if (onRemove != null) IconBtn(Icons.Outlined.Close, "Remove", onRemove, tint = c.mute)
    }
}
