package app.fluvour.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.data.CardEntity
import app.fluvour.reading.ClozeText
import app.fluvour.srs.Rating
import app.fluvour.ui.*
import app.fluvour.ui.art.LanguageMotif
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.ui.sample.Sample
import app.fluvour.ui.theme.Fluvour

/**
 * The "Review" tab: flashcards (real, FSRS-backed) and the Quiz list (placeholder content).
 * Swipe after flipping: left = Again, right = Good, up = Easy, as in the design.
 * Not in the app yet: cloze cards, reading line on the card back, "first met in" source link, leech "new angle".
 */
@Composable
fun ReviewTab(onBrowse: () -> Unit, onNewCard: () -> Unit, onEdit: (String) -> Unit, onQuiz: (String) -> Unit, vm: ReviewViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val voiceMissing by vm.voiceMissing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp).ambient(bleed = 22.dp).languageMark(bleed = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Review", Modifier.weight(1f).padding(top = 8.dp))
            if (tab == 0 && !ui.loading && !ui.finished) {
                val total = ui.answered + ui.newLeft + ui.learningLeft + ui.reviewLeft
                Sm("${ui.answered + 1} / $total")
            }
            IconBtn(Icons.Outlined.Add, "New card", onNewCard)
        }
        UnderlineTabs(listOf("Cards", "Quiz"), tab, { tab = it }, Modifier.padding(top = 2.dp))
        if (tab == 1) QuizList(onQuiz) else Session(ui, voiceMissing, vm, onBrowse, onEdit)
    }
}

@Composable
private fun ColumnScope.Session(ui: ReviewUi, voiceMissing: Boolean, vm: ReviewViewModel, onBrowse: () -> Unit, onEdit: (String) -> Unit) {
    val c = Fluvour.colors
    when {
        ui.loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        ui.finished -> Box(Modifier.weight(1f).fillMaxWidth()) {
          LanguageMotif(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-16).dp))
          Column(Modifier.fillMaxWidth().padding(top = 90.dp)) {
            H1(if (ui.answered == 0) "Nothing due" else "All done", accentSuffix = ".")
            Sm(if (ui.answered == 0) "Come back later or add more cards." else "${ui.answered} reviewed, ${ui.remembered} remembered. Nice rhythm.", Modifier.padding(top = 10.dp))
            LinkText("Browse cards", onBrowse, Modifier.padding(top = 18.dp))
          }
        }
        else -> {
            val card = ui.current!!
            HairlineProgress(ui.progress, Modifier.padding(top = 14.dp, bottom = 22.dp))
            FlipCard(card, ui.cloze, ui.showAnswer, vm::reveal, vm::rate, vm::speak, onEdit, Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp).alpha(if (ui.showAnswer) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Rating.AGAIN to "Again", Rating.HARD to "Hard", Rating.GOOD to "Good", Rating.EASY to "Easy").forEach { (r, label) ->
                    RatingButton(label, ui.previews[r]?.let(::formatInterval) ?: "", r == Rating.GOOD, ui.showAnswer, Modifier.weight(1f)) { vm.rate(r) }
                }
            }
            if (voiceMissing) Sm("No voice installed for this language. Add one in Android settings under Text-to-speech.", Modifier.padding(top = 8.dp), size = 12.sp)
            Sm(if (ui.showAnswer) "Tap a button, or swipe the card." else "Tap to flip.", Modifier.fillMaxWidth().padding(top = 14.dp), align = TextAlign.Center)
            LinkText("Browse cards", onBrowse, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun FlipCard(
    card: CardEntity, cloze: ClozeText?, flipped: Boolean, onFlip: () -> Unit, onRate: (Rating) -> Unit,
    speak: (String, String) -> Unit, onEdit: (String) -> Unit, modifier: Modifier
) {
    val c = Fluvour.colors
    val density = LocalDensity.current.density
    val rot by animateFloatAsState(if (flipped) 180f else 0f, tween(550, easing = CubicBezierEasing(0.3f, 1.1f, 0.4f, 1f)), label = "flip")
    var drag by remember(card.id) { mutableStateOf(Offset.Zero) }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationX = drag.x; translationY = drag.y; rotationZ = drag.x / 40f }
                .pointerInput(flipped, card.id) {
                    if (flipped) detectDragGestures(
                        onDrag = { change, amount -> change.consume(); drag += amount },
                        onDragEnd = {
                            val d = drag; drag = Offset.Zero
                            when { d.x < -200f -> onRate(Rating.AGAIN); d.x > 200f -> onRate(Rating.GOOD); d.y < -200f -> onRate(Rating.EASY) }
                        },
                        onDragCancel = { drag = Offset.Zero }
                    )
                }
                .clickable(enabled = !flipped, onClick = onFlip)
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = rot; cameraDistance = 12f * density }) {
                if (rot <= 90f) Face(Alignment.Center) { if (cloze != null) ClozeFront(card, cloze) else FrontFace(card, speak) }
                else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { Face(Alignment.TopStart) { BackFace(card, speak, onEdit) } }
            }
        }
        // swipe hints
        SwipeHint("Again", Modifier.align(Alignment.CenterStart).padding(18.dp), (-drag.x / 200f).coerceIn(0f, 1f))
        SwipeHint("Good", Modifier.align(Alignment.CenterEnd).padding(18.dp), (drag.x / 200f).coerceIn(0f, 1f))
        SwipeHint("Easy", Modifier.align(Alignment.TopCenter).padding(18.dp), (-drag.y / 200f).coerceIn(0f, 1f))
    }
}

@Composable
private fun SwipeHint(text: String, modifier: Modifier, alpha: Float) =
    Eyebrow(text, modifier.alpha(alpha), color = Fluvour.colors.accent)

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

/** Fill in the blank: the sentence with the word hidden, and the meaning as a hint. No speech on this side, it would give the word away. */
@Composable
private fun ClozeFront(card: CardEntity, cloze: ClozeText) {
    val c = Fluvour.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Eyebrow("Fill in the blank")
        val text = buildAnnotatedString {
            append(cloze.before)
            withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Medium)) { append("  ______  ") }
            append(cloze.after)
        }
        Text(text, Modifier.padding(top = 14.dp), fontFamily = targetFont(), fontSize = 22.sp, lineHeight = 36.sp, color = c.ink, textAlign = TextAlign.Center)
        if (card.meaning.isNotBlank())
            Text(card.meaning, Modifier.padding(top = 18.dp), color = c.mute, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun BackFace(card: CardEntity, speak: (String, String) -> Unit, onEdit: (String) -> Unit) {
    val c = Fluvour.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TargetText(card.word, size = 28.sp)
                IconBtn(Icons.Outlined.VolumeUp, "Play word", { speak(card.word, card.languageCode) }, tint = c.accent)
            }
            // TODO(reading): the design shows a reading line (romanization, furigana) under the word. CardEntity has no field for it yet.
            if (card.imageUrl != null) CardImage(card.imageUrl, card.updatedAt, Modifier.padding(start = 12.dp).size(88.dp))
        }
        H2(card.meaning, Modifier.padding(top = 14.dp), size = 21.sp)
        card.example?.let {
            Text(highlighted(it, card.word), Modifier.padding(top = 14.dp), fontFamily = targetFont(), fontSize = 17.sp, lineHeight = 26.sp, color = c.ink)
        }
        card.notes?.let { Sm(it, Modifier.padding(top = 8.dp), size = 12.5.sp) }
        Spacer(Modifier.weight(1f))
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

@Composable
private fun highlighted(text: String, word: String): AnnotatedString {
    val c = Fluvour.colors
    return buildAnnotatedString {
        if (word.isBlank()) { append(text); return@buildAnnotatedString }
        var i = 0
        while (i < text.length) {
            val j = text.indexOf(word, i, ignoreCase = true)
            if (j < 0) { append(text.substring(i)); break }
            append(text.substring(i, j))
            withStyle(SpanStyle(background = c.accent.copy(alpha = 0.12f), color = c.ink)) { append(text.substring(j, j + word.length)) }
            i = j + word.length
        }
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
