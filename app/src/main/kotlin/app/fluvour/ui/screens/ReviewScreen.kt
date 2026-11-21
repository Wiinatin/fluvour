package app.fluvour.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import android.view.TextureView
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.fluvour.srs.FacetKind
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.data.CardEntity
import app.fluvour.reading.CardText
import app.fluvour.reading.ClipRef
import app.fluvour.reading.WordSplitter
import app.fluvour.reading.HeisigEntry
import app.fluvour.srs.Rating
import app.fluvour.ui.*
import app.fluvour.ui.art.LanguageMotif
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.ui.sample.Sample
import app.fluvour.ui.theme.Fluvour

/**
 * The "Review" tab: flashcards (real, FSRS-backed) and the Quiz list (placeholder content).
 * The front shows the word, the meaning, or the clip (Front: toggle). A clip card shows its few seconds of video in the card itself, plays it by
 * itself when the card appears, and replays it when the video is tapped. The video comes from this screen's own player, never the Watch player.
 * Flip by tapping the card or swiping it sideways. Rating is by the four buttons only (a swipe that rates is too easy to do by mistake); Undo takes the last rating back.
 * Not in the app yet: reading line on the card back, "first met in" source link, leech "new angle".
 */
@Composable
fun ReviewTab(onBrowse: () -> Unit, onNewCard: () -> Unit, onEdit: (String) -> Unit, onQuiz: (String) -> Unit, onHeisig: () -> Unit, onPractice: (String, String) -> Unit, onWordPower: () -> Unit, onLesson: (String, Boolean) -> Unit = { _, _ -> }, onTest: (String) -> Unit = {}, vm: ReviewViewModel = hiltViewModel(), pathVm: PathViewModel = hiltViewModel()) {
    DisposableEffect(Unit) { onDispose { vm.stopClip() } }   // leaving Review stops a clip that is still playing
    // The session queue is built once; pick up cards that became due since (new cards, learning steps) when the tab is shown again.
    LaunchedEffect(Unit) { vm.refresh() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val voiceMissing by vm.voiceMissing.collectAsStateWithLifecycle()
    val front by vm.front.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val dueByKind by vm.dueByKind.collectAsStateWithLifecycle()
    val pathAvailable by pathVm.available.collectAsStateWithLifecycle()
    // The sub-tab is remembered by name: Path comes and goes with the language, so a position would point at the wrong tab.
    var tabName by rememberSaveable { mutableStateOf("Cards") }
    val tabs = when (Fluvour.lang) { "ja" -> listOf("Cards", "Quiz", "Heisig"); "en" -> listOf("Cards", "Quiz", "Word Power"); "ms" -> listOf("Cards", "Quiz", "Sarawak"); else -> listOf("Cards", "Quiz") }
        .let { if (pathAvailable) listOf(it[0], "Path") + it.drop(1) else it }
    val shown = if (tabName in tabs) tabName else "Cards"
    // The drifting background art is repainted every frame; behind a playing video that makes the whole screen lag. It rests while a clip card is shown.
    val blocked by vm.ytBlocked.collectAsStateWithLifecycle()
    val quiet = front == Front.CLIP && ui.clip != null && !blocked && shown == "Cards"
    val base = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)
    Column(if (quiet) base else base.ambient(bleed = 22.dp).languageMark(bleed = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Study", Modifier.weight(1f).padding(top = 8.dp))
            if (shown == "Cards" && !ui.loading && !ui.finished) {
                val total = ui.answered + ui.newLeft + ui.learningLeft + ui.reviewLeft
                Sm("${ui.answered + 1} / $total")
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconBtn(Icons.Outlined.MoreHoriz, "More", { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = Fluvour.colors.card) {
                    DropdownMenuItem(text = { Text("New card", color = Fluvour.colors.ink, fontSize = 14.sp) }, onClick = { menu = false; onNewCard() })
                    DropdownMenuItem(text = { Text("Browse cards", color = Fluvour.colors.ink, fontSize = 14.sp) }, onClick = { menu = false; onBrowse() })
                }
            }
        }
        UnderlineTabs(tabs, tabs.indexOf(shown), { tabName = tabs[it] }, Modifier.padding(top = 2.dp))
        when (shown) {
            "Path" -> PathTab(pathVm, onLesson, onTest)
            "Quiz" -> QuizList(onQuiz)
            "Heisig" -> HeisigTab(onHeisig, onPractice)
            "Word Power" -> WordPowerTab(onWordPower)
            "Sarawak" -> SarawakTab(onUnlocked = { vm.refresh() })
            else -> {
                ModeRow(mode, dueByKind, vm::selectMode)
                Session(ui, voiceMissing, front, vm, onEdit)
            }
        }
    }
}

@Composable
private fun ColumnScope.Session(ui: ReviewUi, voiceMissing: Boolean, front: Front, vm: ReviewViewModel, onEdit: (String) -> Unit) {
    val c = Fluvour.colors
    val blocked by vm.ytBlocked.collectAsStateWithLifecycle()   // YouTube is blocking: clip cards show their word
    when {
        ui.loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        ui.finished -> Box(Modifier.weight(1f).fillMaxWidth()) {
          LanguageMotif(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-16).dp))
          if (ui.canUndo) UndoButton(vm::undo, Modifier.align(Alignment.TopStart).padding(top = 6.dp))
          Column(Modifier.fillMaxWidth().padding(top = 90.dp)) {
            H1(if (ui.answered == 0) "Nothing due" else "All done", accentSuffix = ".")
            Sm(if (ui.answered == 0) "Come back later or add more cards." else "${ui.answered} reviewed, ${ui.remembered} remembered. Nice rhythm.", Modifier.padding(top = 10.dp))
          }
        }
        else -> {
            val card = ui.current!!
            // a card with no clip shows its word in clip mode
            // (also while YouTube is blocking this connection: the word shows instead of a clip that cannot load)
            val shown = if (front == Front.CLIP && (ui.clip == null || blocked)) Front.WORD else front
            // the clip plays by itself when the card appears (not after Undo, which brings the card back already flipped)
            LaunchedEffect(card.id, shown, ui.showAnswer) { if (shown == Front.CLIP && !ui.showAnswer) ui.clip?.let(vm::playClip) }
            // meanwhile the next clip card's video is looked up, so it starts without waiting
            LaunchedEffect(card.id, ui.nextClips) { ui.nextClips.forEach { vm.prefetch(it.url) } }
            val aspect by vm.clipAspect.collectAsStateWithLifecycle()
            val status by vm.clipStatus.collectAsStateWithLifecycle()
            val ended by vm.clipEnded.collectAsStateWithLifecycle()
            val frame by vm.clipFrame.collectAsStateWithLifecycle()
            val playing by vm.clipPlaying.collectAsStateWithLifecycle()
            val paused by vm.clipPaused.collectAsStateWithLifecycle()
            val now by vm.clipNow.collectAsStateWithLifecycle()
            val host = ui.clip?.takeIf { !blocked }?.let { ref -> ClipHost(vm.clipPlayer(), ref, aspect, status, ended, now == ref, frame, playing, paused) { vm.toggleClip(ref) } }
            HairlineProgress(ui.progress, Modifier.padding(top = 14.dp, bottom = 6.dp))
            FrontBar(front, ui.canUndo, vm::undo)
            VanishBox(ui.vanishing == card.id, vm::finishVanish, Modifier.fillMaxWidth().weight(1f)) {
                FlipCard(card, shown, host, ui.heisig, ui.showAnswer, vm::reveal, vm::speak, onEdit, Modifier.fillMaxSize())
            }
            val rateOn = ui.showAnswer && ui.vanishing == null
            Row(Modifier.fillMaxWidth().padding(top = 18.dp).alpha(if (rateOn) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Rating.AGAIN to "Again", Rating.HARD to "Hard", Rating.GOOD to "Good", Rating.EASY to "Easy").forEach { (r, label) ->
                    RatingButton(label, ui.previews[r]?.let(::formatInterval) ?: "", r == Rating.GOOD, rateOn, Modifier.weight(1f)) { vm.rate(r) }
                }
            }
            if (voiceMissing) Sm("No voice installed for this language. Add one in Android settings under Text-to-speech.", Modifier.padding(top = 8.dp), size = 12.sp)
            Sm(if (ui.showAnswer) "Tap a button." else "Tap or swipe to flip.", Modifier.fillMaxWidth().padding(top = 14.dp), align = TextAlign.Center)
        }
    }
}

@Composable
private fun UndoButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    IconBtn(Icons.Outlined.Undo, "Undo last rating", onClick, modifier, tint = Fluvour.colors.mute)

/**
 * The mode row, between the sub-tab row and the card: Mixed (every open facet interleaved) and one entry per kind, each with the number due.
 * The chosen entry is outlined. It is kept while the app stays open and starts on Mixed (see ReviewViewModel.mode).
 */
@Composable
private fun ModeRow(mode: StudyMode, due: Map<FacetKind, Int>, onPick: (StudyMode) -> Unit) {
    val c = Fluvour.colors
    val malay = Fluvour.lang == "ms"   // Malay holds two forms of a word: the mode row says Standard / Sarawak
    val entries = listOf(
        Triple(StudyMode.MIXED, Icons.Outlined.Shuffle, "Mixed"), Triple(StudyMode.CLIP, Icons.Outlined.PlayCircleOutline, "Clip"),
        Triple(StudyMode.MEANING, Icons.Outlined.Translate, "Meaning"), Triple(StudyMode.WORD, Icons.Outlined.TextFields, if (malay) "Standard" else "Word")
    ) + if (malay) listOf(Triple(StudyMode.SARAWAK, Icons.Outlined.Language, "Sarawak")) else emptyList()
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { (m, icon, label) ->
            val n = m.kind?.let { due[it] ?: 0 } ?: due.values.sum()
            val on = m == mode
            val shape = RoundedCornerShape(12.dp)
            val tint = if (on) c.accent else c.mute
            Box(Modifier.weight(1f).height(56.dp).semantics(mergeDescendants = true) { contentDescription = "$label, $n due" + if (on) ", selected" else "" }) {
                Column(
                    Modifier.fillMaxSize().clip(shape).background(if (on) c.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, if (on) c.accent else c.line, shape).clickable { onPick(m) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = tint)
                    Text(label, color = tint, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                if (n > 0) Box(
                    Modifier.align(Alignment.TopEnd).offset(4.dp, (-7).dp).defaultMinSize(minWidth = 18.dp).height(18.dp)
                        .clip(RoundedCornerShape(9.dp)).background(c.accent).padding(horizontal = 4.dp),
                    Alignment.Center
                ) { Text(if (n > 99) "99+" else "$n", color = c.paper, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Above the card: Undo on the left (only when the last rating can be taken back) and, centered, which front this card uses. The line is always there, so nothing below shifts. */
@Composable
private fun FrontBar(front: Front, canUndo: Boolean, onUndo: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp), Alignment.Center) {
        if (canUndo) UndoButton(onUndo, Modifier.align(Alignment.CenterStart))
        Sm(when (front) { Front.WORD -> "Front: word"; Front.MEANING -> "Front: meaning"; Front.CLIP -> "Front: clip"; Front.SARAWAK -> "Front: Sarawak" }, size = 12.sp)
    }
}

@Composable
private fun FlipCard(
    card: CardEntity, front: Front, clip: ClipHost?,
    heisig: List<HeisigEntry>, flipped: Boolean, onFlip: () -> Unit,
    speak: (String, String) -> Unit, onEdit: (String) -> Unit, modifier: Modifier
) {
    val c = Fluvour.colors
    val density = LocalDensity.current.density
    val rot by animateFloatAsState(if (flipped) 180f else 0f, tween(550, easing = CubicBezierEasing(0.3f, 1.1f, 0.4f, 1f)), label = "flip")
    var drag by remember(card.id) { mutableStateOf(Offset.Zero) }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize()
                // layers only while something moves: a video inside a resting card is then drawn straight, not through two extra layers
                .then(if (drag != Offset.Zero) Modifier.graphicsLayer { translationX = drag.x; translationY = drag.y; rotationZ = drag.x / 40f } else Modifier)
                .pointerInput(flipped, card.id) {
                    // a sideways swipe flips the card, like a tap; it never rates
                    if (!flipped) detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount -> change.consume(); drag = Offset(drag.x + amount, 0f) },
                        onDragEnd = {
                            val d = drag; drag = Offset.Zero
                            if (kotlin.math.abs(d.x) > 120f) onFlip()
                        },
                        onDragCancel = { drag = Offset.Zero }
                    )
                }
                .clickable(enabled = !flipped, onClick = onFlip)
        ) {
            val turning = rot != 0f && rot != 180f
            Box(Modifier.fillMaxSize().then(if (turning) Modifier.graphicsLayer { rotationY = rot; cameraDistance = 12f * density } else Modifier)) {
                if (rot <= 90f) Face(Alignment.Center) {
                    when {
                        front == Front.CLIP && clip != null -> ClipFront(card, clip)
                        front == Front.MEANING -> MeaningFront(card)
                        front == Front.SARAWAK && !card.variantWord.isNullOrBlank() -> SarawakFront(card, speak)
                        else -> FrontFace(card, speak)
                    }
                }
                else Box(Modifier.fillMaxSize().then(if (turning) Modifier.graphicsLayer { rotationY = 180f } else Modifier)) { Face(Alignment.TopStart) { BackFace(card, front, heisig, clip, speak, onEdit) } }
            }
        }
    }
}

@Composable
private fun Face(align: Alignment, content: @Composable BoxScope.() -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxSize().border(1.dp, c.line, shape).background(
            Brush.verticalGradient(listOf(lerp(c.card, c.tint, 0.42f), c.card), endY = 900f), shape).padding(24.dp),
        contentAlignment = align, content = content
    )
}

@Composable
private fun FrontFace(card: CardEntity, speak: (String, String) -> Unit) {
    val c = Fluvour.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        TargetText(card.word, size = 44.sp, align = TextAlign.Center)
        IconBtn(Icons.Outlined.VolumeUp, "Play word", { speak(card.word, card.languageCode) }, tint = c.accent)
        card.example?.let {
            Text(highlighted(it, card.word), Modifier.padding(top = 12.dp), fontFamily = targetFont(), fontSize = 16.sp, lineHeight = 26.sp, color = c.mute, textAlign = TextAlign.Center)
            IconBtn(Icons.Outlined.VolumeUp, "Play sentence", { speak(it, card.languageCode) }, tint = c.accent)
        }
    }
}

/** The clip of the card on screen and what the faces need to draw it: the Review player, its state, and whether it holds this card's clip. */
private class ClipHost(
    val player: ExoPlayer, val ref: ClipRef, val aspect: Float, val status: ClipStatus,
    val ended: Boolean, val showing: Boolean, val frame: Boolean, val playing: Boolean, val paused: Boolean, val play: () -> Unit
) {
    /** Nothing of this card's video has been drawn yet (and it has not failed): the frame shows the cover and a spinner. */
    val waiting get() = status != ClipStatus.FAILED && !(showing && frame)
}

/** The Review player's picture. A TextureView, because unlike a SurfaceView it can flip and turn with the card. */
@Composable
private fun ClipSurface(player: ExoPlayer, modifier: Modifier) {
    val ctx = LocalContext.current
    val view = remember { TextureView(ctx) }
    DisposableEffect(view) { player.setVideoTextureView(view); onDispose { player.clearVideoTextureView(view) } }
    AndroidView({ view }, modifier)
}

/**
 * Clip first: the video itself in the card (it starts by itself), then the question. Tapping the video, or the replay icon when it has
 * finished, plays it again. No subtitle and no translation, they would give the meaning away.
 */
@Composable
private fun ClipFront(card: CardEntity, clip: ClipHost) {
    val c = Fluvour.colors
    val tf = targetFont()
    // The word lights up in the question while it is being said in the clip (an estimate, see wordWindow).
    var lit by remember(card.id) { mutableStateOf(false) }
    val window = remember(card.id, clip.ref) { wordWindow(card.example, card.word, clip.ref) }
    LaunchedEffect(card.id, clip.ref, clip.showing, window) {
        if (window == null || !clip.showing) { lit = false; return@LaunchedEffect }
        while (true) {
            lit = clip.player.isPlaying && clip.player.currentPosition in window
            delay(40)
        }
    }
    val wordColor by animateColorAsState(if (lit) c.accent else c.ink, tween(120), label = "clipWord")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.heightIn(max = 220.dp).aspectRatio(clip.aspect).clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable(onClick = clip.play),
            Alignment.Center
        ) {
            ClipSurface(clip.player, Modifier.fillMaxSize())
            // the frame is there from the start: the video's cover and a spinner until its first picture is drawn
            if (clip.waiting || clip.status == ClipStatus.FAILED) clip.ref.cover?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            when {
                clip.waiting -> {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 2.dp)
                }
                clip.status == ClipStatus.FAILED || (clip.ended && clip.showing) -> Icon(Icons.Outlined.Replay, "Play the clip again", Modifier.size(44.dp), tint = Color.White.copy(alpha = 0.9f))
                clip.showing && clip.paused && !clip.playing -> Icon(Icons.Outlined.PlayArrow, "Resume the clip", Modifier.size(44.dp), tint = Color.White.copy(alpha = 0.9f))
                else -> {}
            }
        }
        Text(
            buildAnnotatedString {
                append("What does \u201C"); withStyle(SpanStyle(fontFamily = tf, color = wordColor)) { append(card.word) }; append("\u201D mean?")
            },
            Modifier.padding(top = 24.dp), color = c.ink, fontSize = 22.sp, lineHeight = 32.sp, textAlign = TextAlign.Center
        )
        if (clip.status == ClipStatus.FAILED)
            Sm("Could not load the clip. Tap the video to try again, or flip the card.", Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp), size = 12.sp, align = TextAlign.Center)
    }
}

/**
 * When, in video milliseconds, the word is being said. A clip mined from automatic captions knows when each word starts, so that is
 * used. Any other clip (manual subtitles, or mined before word timing existed) only knows its subtitle line's start and end: the word's
 * place in the sentence (by characters) is spread over the line's time, which is an estimate. Either way a little padding on both sides,
 * and no window at all (nothing lights up) when the word is not found.
 */
private fun wordWindow(sentence: String?, word: String, ref: ClipRef): LongRange? {
    ref.wordSpan(word)?.let { (from, to) -> return (from - 60)..(to + 100) }
    if (sentence.isNullOrBlank() || word.isBlank()) return null
    val i = sentence.indexOf(word, ignoreCase = true)
    if (i < 0) return null
    val dur = (ref.endMs - ref.startMs).coerceAtLeast(1L)
    val from = ref.startMs + dur * i / sentence.length
    val to = ref.startMs + dur * (i + word.length) / sentence.length
    return (from - 150)..(to + 150)
}

/** The back of a clip card: the clip's last frame, small, top right. Before the clip has loaded it is the video's cover. Tap to play, pause or resume the clip. */
@Composable
private fun ClipThumb(clip: ClipHost) {
    val shown = clip.showing && clip.frame
    Box(
        Modifier.padding(start = 12.dp).width(100.dp).heightIn(max = 72.dp).aspectRatio(clip.aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable(onClick = clip.play),
        Alignment.Center
    ) {
        ClipSurface(clip.player, Modifier.fillMaxSize())
        if (!shown) clip.ref.cover?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (clip.status == ClipStatus.LOADING) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        // the icon is only there when the clip is not playing (not played yet, or finished)
        else if (!(clip.showing && clip.playing)) Icon(Icons.Outlined.PlayArrow, "Play the clip", Modifier.size(26.dp), tint = Color.White.copy(alpha = 0.85f))
    }
}

/** Sarawak first (Malay): the Sarawak word and its sentence, with a speaker button each. The standard form and the meaning are the answer. */
@Composable
private fun SarawakFront(card: CardEntity, speak: (String, String) -> Unit) {
    val c = Fluvour.colors
    val word = card.variantWord.orEmpty()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Sm("Sarawak", size = 12.sp)
        TargetText(word, size = 44.sp, align = TextAlign.Center)
        IconBtn(Icons.Outlined.VolumeUp, "Play word", { speak(word, card.languageCode) }, tint = c.accent)
        card.variantExample?.takeIf { it.isNotBlank() }?.let {
            Text(highlighted(it, word), Modifier.padding(top = 12.dp), fontFamily = targetFont(), fontSize = 16.sp, lineHeight = 26.sp, color = c.mute, textAlign = TextAlign.Center)
            IconBtn(Icons.Outlined.VolumeUp, "Play sentence", { speak(it, card.languageCode) }, tint = c.accent)
        }
    }
}

/** Meaning first: nothing else on this side (no word, no sentence, no speech), they would give the answer away. */
@Composable
private fun MeaningFront(card: CardEntity) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        H2(card.meaning, size = 28.sp, align = TextAlign.Center)
    }
}

@Composable
private fun BackFace(card: CardEntity, front: Front, heisig: List<HeisigEntry>, clip: ClipHost?, speak: (String, String) -> Unit, onEdit: (String) -> Unit) {
    val c = Fluvour.colors
    // Always shown: word, short meaning, example. Under "More": the rest (Heisig, notes, the whole example). A back that is still
    // taller than the card scrolls up and down.
    val sarawakCard = front == Front.SARAWAK && !card.variantWord.isNullOrBlank()
    val headWord = if (sarawakCard) card.variantWord!! else card.word
    val otherForm = card.variantWord?.takeIf { !sarawakCard && it.isNotBlank() }
    var more by remember(card.id) { mutableStateOf(false) }
    var exampleCut by remember(card.id) { mutableStateOf(false) }
    val notes = card.notes?.takeIf { it.isNotBlank() }?.let { CardText.notesForCard(it) }?.takeIf { it.isNotBlank() }
    // Karaoke: while the clip plays, each word of the example turns accent-coloured when it starts and stays so until the line ends.
    // A clip without word times gets an estimate instead (see below).
    // Real word times when the clip has them; otherwise an estimate from the line's time (step 5). The word splitting runs off the main thread
    // because the first Japanese or Chinese call loads a dictionary.
    val spans = produceState<List<app.fluvour.reading.LitSpan>?>(null, card.id, card.example, clip?.ref, card.languageCode) {
        val ref = clip?.ref
        val example = card.example
        value = if (ref == null || example.isNullOrBlank()) null else withContext(Dispatchers.Default) {
            ref.litSpans(example) ?: runCatching { ref.estimatedSpans(example, WordSplitter.split(example, card.languageCode)) }.getOrNull()
        }
    }.value
    var litCount by remember(card.id) { mutableStateOf(0) }
    LaunchedEffect(card.id, spans, clip?.showing, clip?.ended) {
        if (clip == null || spans == null || !clip.showing || clip.ended) { litCount = 0; return@LaunchedEffect }
        while (true) {
            val pos = clip.player.currentPosition
            litCount = if (pos > clip.ref.endMs) 0 else spans.count { it.ms - 40 <= pos }
            delay(40)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    TargetText(headWord, size = 28.sp)
                    IconBtn(Icons.Outlined.VolumeUp, "Play word", { speak(headWord, card.languageCode) }, tint = c.accent)
                }
                // TODO(reading): the design shows a reading line (romanization, furigana) under the word. CardEntity has no field for it yet.
                // a clip card shows its clip here (tap to play); other cards show their own image
                if (clip != null) ClipThumb(clip)
                else if (card.imageUrl != null) CardImage(card.imageUrl, card.updatedAt, Modifier.padding(start = 12.dp).size(88.dp))
            }
            H2(card.meaning, Modifier.padding(top = 14.dp), size = 21.sp)
            // Malay: the answer shows both forms. On a Sarawak card the headline is the Sarawak word and this line gives the standard one.
            if (sarawakCard) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Standard", color = c.mute, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                TargetText(card.word, size = 18.sp)
                IconBtn(Icons.Outlined.VolumeUp, "Play standard word", { speak(card.word, card.languageCode) }, tint = c.accent)
            } else if (otherForm != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sarawak", color = c.mute, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                TargetText(otherForm, size = 18.sp)
                IconBtn(Icons.Outlined.VolumeUp, "Play Sarawak word", { speak(otherForm, card.languageCode) }, tint = c.accent)
            }
            (if (sarawakCard) card.variantExample?.takeIf { it.isNotBlank() } ?: card.example else card.example)?.let {
                // a long sentence gets a smaller type size, and three lines until "More" is opened
                val size = when { it.length <= 40 -> 17.sp; it.length <= 90 -> 15.sp; else -> 14.sp }
                Text(if (spans != null && litCount > 0) karaoke(it, card.word, spans, litCount) else highlighted(it, headWord), Modifier.padding(top = 14.dp), fontFamily = targetFont(), fontSize = size, lineHeight = size * 1.5f, color = c.ink,
                    maxLines = if (more) Int.MAX_VALUE else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    onTextLayout = { r -> if (!more) exampleCut = r.hasVisualOverflow })
                // what the Sarawak sentence means (only on the Sarawak card, under its sentence)
                if (sarawakCard || card.variantWord.isNullOrBlank()) card.variantExampleMeaning?.takeIf { it.isNotBlank() }?.let { m ->
                    Text(m, Modifier.padding(top = 4.dp), fontSize = 13.sp, lineHeight = 19.sp, color = c.mute)
                }
            }
            if (heisig.isNotEmpty() || notes != null || exampleCut || more)
                Text(if (more) "Less" else "More", Modifier.clickable { more = !more }.padding(top = 6.dp, bottom = 6.dp, end = 16.dp), color = c.accent, fontSize = 13.sp)
            if (more) {
                // Heisig: keyword and story for each kanji of the word (Japanese, when the Heisig deck is imported)
                if (heisig.isNotEmpty()) {
                    Eyebrow("Heisig", Modifier.padding(top = 10.dp))
                    heisig.forEach { h ->
                        val story = h.story1.ifBlank { h.story2 }.replace("\n", " ")
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                            Text(h.ch, fontFamily = targetFont(), fontSize = 22.sp, color = c.accent)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(h.keyword, color = c.ink, fontSize = 14.sp)
                                if (story.isNotBlank()) Text(story, color = c.mute, fontSize = 12.5.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
                notes?.let { Text(it, Modifier.padding(top = 12.dp), color = c.mute, fontSize = 12.5.sp, lineHeight = 18.sp) }
            }
        }
        var leech by remember { mutableStateOf(false) }
        if (card.lapses >= 6) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Sm("Missed ${card.lapses} times", size = 12.5.sp)
            Text("Try a new angle", Modifier.clickable { leech = true }.padding(vertical = 12.dp), color = c.accent, fontSize = 13.sp)
        }
        if (leech) LeechSheet(card, onClose = { leech = false })
        androidx.compose.material3.HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconBtn(Icons.Outlined.Edit, "Edit card", { onEdit(card.id) }, tint = c.mute)
        }
    }
}

/** The example with the card's word marked ([highlighted]) and the first [lit] timed pieces filled with the accent colour. */
@Composable
private fun karaoke(text: String, word: String, spans: List<app.fluvour.reading.LitSpan>, lit: Int): AnnotatedString {
    val c = Fluvour.colors
    val base = highlighted(text, word)
    return buildAnnotatedString {
        append(base)
        for (sp in spans.take(lit)) addStyle(SpanStyle(color = c.accent), sp.from.coerceIn(0, text.length), sp.to.coerceIn(0, text.length))
    }
}

@Composable
private fun highlighted(text: String, word: String): AnnotatedString {
    val c = Fluvour.colors
    return buildAnnotatedString {
        append(text)
        // Arabic: the card word and the sentence may carry different vowel marks, so match on the plain forms (findForms).
        for (r in app.fluvour.srs.findForms(Fluvour.lang, text, word))
            addStyle(SpanStyle(background = c.accent.copy(alpha = 0.12f), color = c.ink), r.first, r.last + 1)
    }
}

@Composable
private fun RatingButton(label: String, interval: String, emphasized: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(12.dp)
    val fg = if (emphasized) c.onPrimary else c.ink
    Column(
        modifier.clip12().then(if (emphasized) Modifier.background(c.accent, shape) else Modifier.background(c.card, shape).border(1.dp, c.line, shape))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
        Text(interval, fontSize = 11.5.sp, color = if (emphasized) fg.copy(alpha = 0.9f) else c.mute)
    }
}

private fun Modifier.clip12() = this.then(Modifier.clip(RoundedCornerShape(12.dp)))

/** Quiz tab: the list of quiz sets. Questions are built from your own cards when you open one (Phase 3). */
@Composable
private fun QuizList(onQuiz: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Sm("Check how well the words have stuck.", Modifier.padding(top = 18.dp, bottom = 6.dp))
        listOf(
            Triple("Mixed quiz", "8 questions, all kinds", "mix"), Triple("In context", "5 words inside sentences", "ctx"),
            Triple("Fill in the blank", "5 sentences", "fil"), Triple("Listening", "5 words, by sound", "lis"), Triple("Which word?", "5 meanings to match", "trn")
        ).forEach { (title, sub, id) -> FRow(title, subtitle = sub, onClick = { onQuiz(id) }) }
        Sm("Built from your own cards. Needs at least 4.", Modifier.padding(top = 12.dp))
    }
}
