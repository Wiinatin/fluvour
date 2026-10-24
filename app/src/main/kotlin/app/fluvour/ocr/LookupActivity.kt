package app.fluvour.ocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.reading.DictionaryClient
import app.fluvour.reading.Levels
import app.fluvour.reading.Part
import app.fluvour.ui.Eyebrow
import app.fluvour.ui.FButton
import app.fluvour.ui.Tag
import app.fluvour.ui.TargetText
import app.fluvour.ui.theme.Fluvour
import app.fluvour.ui.theme.FluvourTheme
import app.fluvour.ui.theme.Themes
import dagger.hilt.android.AndroidEntryPoint

/**
 * The lookup sheet over the app you were reading (Phase 2B step 4). Opens when you tap a block in the screen-lookup layer.
 * Transparent window: the other app stays visible behind the sheet. Closing it brings the outlined text back for the next word.
 */
@AndroidEntryPoint
class LookupActivity : ComponentActivity() {
    private val vm: OcrLookupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.start(
            intent.getStringExtra(EXTRA_TEXT).orEmpty(), intent.getIntExtra(EXTRA_OFFSET, 0), intent.getStringExtra(EXTRA_LANG) ?: "en",
            intent.getStringExtra(EXTRA_PACKAGE), intent.getStringExtra(EXTRA_PAGE).orEmpty(), intent.getIntExtra(EXTRA_BLOCK, 0)
        )
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            val settings = s ?: return@setContent
            FluvourTheme(Themes.byId(settings.theme), settings.mode, settings.targetLang) { LookupSheet(vm) { finish() } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) OverlayService.reshow(applicationContext)
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_OFFSET = "offset"
        const val EXTRA_LANG = "lang"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_PAGE = "page"
        const val EXTRA_BLOCK = "block"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LookupSheet(vm: OcrLookupViewModel, onClose: () -> Unit) {
    val c = Fluvour.colors
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val pick by vm.pick.collectAsStateWithLifecycle()
    val meanings by vm.meanings.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val imported by vm.imported.collectAsStateWithLifecycle()
    val voiceHint by vm.voiceHint.collectAsStateWithLifecycle()
    val sentenceSaved by vm.sentenceSaved.collectAsStateWithLifecycle()
    val cardAdded by vm.cardAdded.collectAsStateWithLifecycle()
    val sourceLabel by vm.sourceLabel.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.paper, scrimColor = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tag(sourceLabel)
            // The text that was read. Tap another word if the wrong one was picked.
            FlowRow(Modifier.fillMaxWidth()) {
                tokens.forEach { t ->
                    val chunk = vm.text.substring(t.start, t.end)
                    val selected = pick?.let { t.start >= it.start && t.end <= it.end } == true
                    TargetText(
                        chunk, Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(if (selected) c.highlight else androidx.compose.ui.graphics.Color.Transparent)
                            .then(if (t.isWord) Modifier.clickable { vm.choose(t) } else Modifier),
                        size = 20.sp, lineHeight = 32.sp
                    )
                }
            }
            val p = pick
            if (p == null) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TargetText(p.word, Modifier.weight(1f), size = 32.sp)
                IconButton({ vm.speak(p.word) }) { Icon(Icons.Outlined.VolumeUp, "Speak the word", tint = c.ink) }
                IconButton({ vm.speak(p.sentence) }) { Icon(Icons.Outlined.VolumeUp, "Speak the sentence", tint = c.mute) }
            }
            val form = listOfNotNull(p.reading?.takeIf { p.word.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN } }, p.lemma?.let { "→ $it" }).joinToString("   ")
            if (form.isNotBlank()) Text(form, color = c.mute, fontSize = 14.sp)
            (Levels.chips(vm.lang, level) + imported?.chips.orEmpty()).takeIf { it.isNotEmpty() }?.let { chips ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { Tag(it) } }
            }
            if (voiceHint) Text("No voice for this language yet. Install one under Android's Text-to-speech settings.", color = c.mute, fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                imported?.entries?.firstOrNull()?.let { Meaning("Your dictionaries", Part(it.glossary.take(500)), it.dictionary) }
                Meaning("Dictionary", meanings.dictionary, DictionaryClient.SOURCE)
                Meaning("In this sentence (AI)", meanings.explanation, null)
            }
            OutlinedTextField(
                note, vm::setNote, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Series / chapter (optional)") }, placeholder = { Text("e.g. One Piece ch. 12") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent)
            )
            if (cardAdded) Saved("Card added") else FButton("Add card", vm::addCard, Modifier.fillMaxWidth())
            if (sentenceSaved) { if (!cardAdded) Saved("Sentence saved") } else FButton("Save sentence", vm::saveSentence, Modifier.fillMaxWidth(), outlined = true)
        }
    }
}

@Composable
private fun Saved(text: String) {
    val c = Fluvour.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Check, null, tint = c.mute, modifier = Modifier.size(18.dp))
        Text("  $text", color = c.mute, fontSize = 14.sp)
    }
}

/** One labelled meaning: a spinner while loading, then the text (with its source) or a short message. */
@Composable
private fun Meaning(title: String, part: Part?, source: String?) {
    val c = Fluvour.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(title)
        when {
            part == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            part.text != null -> { Text(part.text, color = c.ink, fontSize = 15.sp); if (source != null) Text(source, color = c.mute, fontSize = 11.sp) }
            else -> Text(part.message ?: "", color = c.mute, fontSize = 13.sp)
        }
    }
}
