package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ai.TextDiff
import app.fluvour.reading.DictionaryClient
import app.fluvour.reading.Token
import app.fluvour.reading.WordSplitter
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Where the sound is: [active] means a sound file is loaded for this text, [playing] that it is running. Times in ms. */
class PlayState(val active: Boolean = false, val playing: Boolean = false, val pos: Int = 0, val dur: Int = 0) {
    /** 0..1 through the sound, or null when this text is not the one playing. */
    val fraction: Float? get() = if (active && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
}

/** Reads [read] about 8 times a second while the screen is shown. */
@Composable
fun rememberPlayState(read: () -> PlayState): State<PlayState> {
    val st = remember { mutableStateOf(PlayState()) }
    LaunchedEffect(Unit) { while (true) { st.value = read(); delay(120) } }
    return st
}

/**
 * Text you can tap. Every word opens [onWord] (word, base form, reading). [lit] (0..1) fills the text with the accent colour as the sound
 * plays: the time is spread over the letters, snapped to whole words, so it is an estimate, not exact. [compareTo] marks the words that
 * are not in that other text (what the AI changed). [marked] are words drawn bold in the accent colour (words you said in another language).
 */
@Composable
fun TapText(
    text: String, lang: String, modifier: Modifier = Modifier, size: TextUnit = 22.sp, color: Color = Fluvour.colors.ink,
    lit: Float? = null, compareTo: String? = null, marked: List<String> = emptyList(), onWord: ((String, String?, String?) -> Unit)? = null
) {
    val c = Fluvour.colors
    val tokens by produceState<List<Token>>(emptyList(), text, lang) { value = withContext(Dispatchers.Default) { runCatching { WordSplitter.split(text, lang) }.getOrDefault(emptyList()) } }
    val changed by produceState<BooleanArray?>(null, text, compareTo, lang) {
        value = if (compareTo == null) null else withContext(Dispatchers.Default) {
            runCatching {
                val a = WordSplitter.split(compareTo, lang).filter { it.isWord }.map { compareTo.substring(it.start, it.end) }
                val b = WordSplitter.split(text, lang).filter { it.isWord }
                TextDiff.changed(a, b.map { text.substring(it.start, it.end) })
            }.getOrNull()
        }
    }
    val words = remember(tokens) { tokens.filter { it.isWord && it.start >= 0 && it.end <= text.length && it.start < it.end } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var picked by remember(text) { mutableStateOf<Token?>(null) }
    val litEnd = remember(lit, text, words) {
        if (lit == null) 0 else {
            val total = text.count { !it.isWhitespace() }
            val target = (lit * total).toInt()
            var n = 0; var idx = text.length
            for ((i, ch) in text.withIndex()) { if (!ch.isWhitespace()) n++; if (n >= target) { idx = i + 1; break } }
            if (target <= 0) 0 else words.firstOrNull { it.end >= idx }?.end ?: text.length
        }
    }
    val styled: AnnotatedString = remember(text, words, changed, litEnd, marked, picked, color, c.accent) {
        buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(color = color), 0, text.length)
            if (litEnd > 0) addStyle(SpanStyle(color = c.accent), 0, litEnd.coerceAtMost(text.length))
            changed?.let { ch -> words.forEachIndexed { i, t -> if (ch.getOrNull(i) == true) addStyle(SpanStyle(background = c.accent.copy(alpha = 0.18f)), t.start, t.end) } }
            for (m in marked) { if (m.isEmpty()) continue; var from = 0
                while (true) { val k = text.indexOf(m, from); if (k < 0) break; addStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Bold), k, k + m.length); from = k + m.length } }
            picked?.let { addStyle(SpanStyle(textDecoration = TextDecoration.Underline), it.start, it.end) }
        }
    }
    Text(styled, modifier.then(if (onWord == null) Modifier else Modifier.pointerInput(text, words) {
        detectTapGestures { pos ->
            val off = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
            words.firstOrNull { off >= it.start && off < it.end }?.let { t -> picked = t; onWord(text.substring(t.start, t.end), t.lemma, t.reading) }
        }
    }), fontFamily = targetFont(), fontSize = size, lineHeight = size * 1.65f, onTextLayout = { layout = it })
}

/**
 * Who wrote this: your own words sit on a plain card with a quiet bar, the AI's on a tinted card with an accent bar and an "AI" mark.
 * [actions] go at the right of the title row.
 */
@Composable
fun AuthorPanel(ai: Boolean, title: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(if (ai) c.accent.copy(alpha = 0.09f) else c.card).height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (ai) c.accent else c.mute.copy(alpha = 0.5f)))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(title, color = if (ai) c.accent else c.mute)
                if (ai) Text("AI", Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent).padding(horizontal = 6.dp, vertical = 1.dp), color = c.paper, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                actions?.invoke(this)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

private fun clock(ms: Int): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/**
 * Play, pause, seek and speed for one sound. [onPlay] starts it from the beginning (when nothing is loaded yet), [onPause] and [onResume]
 * keep the place. Shows "Loading" while the voice is being made.
 */
@Composable
fun AudioBar(state: PlayState, speed: Float, onPlay: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onSeek: (Int) -> Unit, onSpeed: (Float) -> Unit, loading: Boolean = false, modifier: Modifier = Modifier) {
    val c = Fluvour.colors
    var drag by remember { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent).clickable { if (!state.active) onPlay() else if (state.playing) onPause() else onResume() }, contentAlignment = Alignment.Center) {
                Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.playing) "Pause" else "Play", tint = c.paper)
            }
            Slider(
                value = drag ?: (state.fraction ?: 0f), onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { f -> if (state.dur > 0) onSeek((f * state.dur).toInt()) }; drag = null },
                enabled = state.active && state.dur > 0, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line, disabledThumbColor = c.line, disabledActiveTrackColor = c.line, disabledInactiveTrackColor = c.line)
            )
            Sm(if (loading) "Loading" else if (state.active) clock(state.pos) + " / " + clock(state.dur) else "0:00", size = 12.sp)
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f to "0.75x", 1f to "1x").forEach { (f, label) ->
                Text(label, Modifier.clip(RoundedCornerShape(50)).clickable { onSpeed(f) }.padding(horizontal = 12.dp, vertical = 6.dp), color = if (speed == f) c.accent else c.mute, fontSize = 13.sp, fontWeight = if (speed == f) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** The word sheet for a word tapped in Output text: meanings (cached) and a Save button. Nothing is saved on its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputWordSheet(vm: WordLookupViewModel) {
    val c = Fluvour.colors
    val look by vm.lookup.collectAsStateWithLifecycle()
    val dict by vm.dictionary.collectAsStateWithLifecycle()
    val expl by vm.explanation.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val l = look ?: return
    ModalBottomSheet(onDismissRequest = { vm.clear() }, containerColor = c.paper, scrimColor = c.scrim) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TargetText(l.word, Modifier.weight(1f), size = 34.sp)
                IconButton({ vm.speak(l.word) }) { Icon(Icons.Outlined.VolumeUp, "Speak the word", tint = c.ink) }
            }
            val form = listOfNotNull(l.reading?.takeIf { it.isNotBlank() && it != l.word }, l.lemma?.takeIf { it != l.word }?.let { "→ $it" }).joinToString("   ")
            if (form.isNotBlank()) Text(form, color = c.mute, fontSize = 14.sp)
            TargetText(l.sentence, size = 16.sp, color = c.mute)
            MeaningBlock("Dictionary", dict, DictionaryClient.SOURCE)
            MeaningBlock("In this sentence (AI)", expl, null)
            val ready = dict?.text != null || expl?.text != null
            FButton(if (saved) "Saved as a card" else "Save as a card", { vm.save() }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = ready && !saved, soft = saved)
        }
    }
}
