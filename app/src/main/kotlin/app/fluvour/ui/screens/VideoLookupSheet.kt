package app.fluvour.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.reading.DictionaryClient
import app.fluvour.reading.Levels
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/** The word sheet for a tapped video word: same content and buttons as the reader's (meanings, level chips, Add card, Save sentence). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLookupSheet(
    vm: PlayerViewModel, onClose: () -> Unit,
    onAddCard: (word: String, sentence: String, meaning: String, sentenceId: String?) -> Unit
) {
    val c = Fluvour.colors
    val l by vm.lookup.collectAsStateWithLifecycle()
    val meanings by vm.meanings.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val myDicts by vm.imported.collectAsStateWithLifecycle()
    val voiceHint by vm.voiceHint.collectAsStateWithLifecycle()
    val mined by vm.mined.collectAsStateWithLifecycle()
    val look = l ?: return
    val sentenceSaved = look.sentence.trim() in mined

    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.paper, scrimColor = c.scrim) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TargetText(look.word, Modifier.weight(1f), size = 34.sp)
                IconButton({ vm.speak(look.word) }) { Icon(Icons.Outlined.VolumeUp, "Speak the word", tint = c.ink) }
            }
            Row(verticalAlignment = Alignment.Top) {
                TargetText(look.sentence, Modifier.weight(1f), size = 18.sp, lineHeight = 28.sp)
                IconButton({ vm.speak(look.sentence) }) { Icon(Icons.Outlined.VolumeUp, "Speak the sentence", tint = c.mute) }
            }
            val form = listOfNotNull(look.reading?.takeIf { look.word.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN } },
                look.lemma?.let { "→ $it" }).joinToString("   ")
            if (form.isNotBlank()) Text(form, color = c.mute, fontSize = 14.sp)
            (Levels.chips(vm.lang, level) + myDicts?.chips.orEmpty()).takeIf { it.isNotEmpty() }?.let { chips ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { Tag(it, font = if (it.startsWith("/")) targetFont() else null) }
                }
            }
            if (voiceHint) Text("No voice for this language yet. Install one under Android's Text-to-speech settings.", color = c.mute, fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                myDicts?.entries?.takeIf { it.isNotEmpty() }?.let { ImportedBlock(it) }
                MeaningBlock("Dictionary", meanings.dictionary, DictionaryClient.SOURCE)
                MeaningBlock("In this sentence (AI)", meanings.explanation, null)
            }
            FButton("Add card", {
                val w = look.lemma ?: look.word; val s = look.sentence
                val m = meanings.explanation?.text ?: myDicts?.entries?.firstOrNull()?.glossary?.lineSequence()?.take(3)?.joinToString("; ").orEmpty()
                // Mining first links the card to the saved sentence, this video and the line's time.
                vm.mineForCard { sentenceId -> vm.clear(); onAddCard(w, s, m, sentenceId) }
            }, Modifier.fillMaxWidth())
            if (sentenceSaved) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, null, tint = c.mute, modifier = Modifier.size(18.dp))
                    Text("  Sentence saved", color = c.mute, fontSize = 14.sp)
                }
            } else {
                FButton("Save sentence", vm::saveSentence, Modifier.fillMaxWidth(), outlined = true)
            }
        }
    }
}
