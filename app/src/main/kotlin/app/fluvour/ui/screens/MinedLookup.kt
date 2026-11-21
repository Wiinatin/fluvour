package app.fluvour.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.data.SentenceWithSource
import app.fluvour.reading.DictionaryClient
import app.fluvour.reading.Levels
import app.fluvour.reading.Token
import app.fluvour.reading.WordSplitter
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One mined sentence on the Mined page. The full sentence always shows; every word is tappable and opens the same word sheet as Watch.
 * Words use the reader's tokens (lemma, reading) once ready, and the platform word breaker until then, as the Watch subtitles do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MinedSentenceRow(row: SentenceWithSource, onWord: (word: String, lemma: String?, reading: String?) -> Unit) {
    val c = Fluvour.colors
    val text = row.sentence.text
    val lang = row.sentence.languageCode
    val tokens by produceState<List<Token>?>(null, text, lang) { value = withContext(Dispatchers.Default) { WordSplitter.split(text, lang) } }
    val words = tokens?.takeIf { it.isNotEmpty() }
        ?.map { t -> Triple(text.substring(t.start, t.end).replace("\n", " "), t.isWord, t) }
        ?: splitWords(text, lang).map { (p, w) -> Triple(p, w, null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        FlowRow {
            words.forEach { (w, isWord, tok) ->
                val mod = if (isWord) Modifier.clickable { onWord(w, tok?.lemma, tok?.reading) } else Modifier
                Text(w, mod, color = c.ink, fontFamily = targetFont(), fontSize = 16.sp, lineHeight = 26.sp)
            }
        }
    }
}

/** The word sheet for a word tapped in a mined sentence: same content and buttons as Watch's (meanings, level chips, Add card). The sentence is already saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MinedLookupSheet(
    vm: ReadViewModel, look: MinedLookup, onClose: () -> Unit,
    onAddCard: (word: String, sentence: String, meaning: String, sentenceId: String?) -> Unit
) {
    val c = Fluvour.colors
    ModalBottomSheet(onDismissRequest = onClose, containerColor = c.paper, scrimColor = c.scrim) { MinedLookupBody(vm, look, onAddCard) }
}

@Composable
private fun MinedLookupBody(
    vm: ReadViewModel, look: MinedLookup,
    onAddCard: (word: String, sentence: String, meaning: String, sentenceId: String?) -> Unit
) {
    val c = Fluvour.colors
    val meanings by vm.meanings.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val myDicts by vm.imported.collectAsStateWithLifecycle()
    val voiceHint by vm.voiceHint.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TargetText(look.word, Modifier.weight(1f), size = 34.sp)
            IconButton({ vm.speak(look.word, look.lang) }) { Icon(Icons.Outlined.VolumeUp, "Speak the word", tint = c.ink) }
        }
        Row(verticalAlignment = Alignment.Top) {
            TargetText(look.sentence, Modifier.weight(1f), size = 18.sp, lineHeight = 28.sp)
            IconButton({ vm.speak(look.sentence, look.lang) }) { Icon(Icons.Outlined.VolumeUp, "Speak the sentence", tint = c.mute) }
        }
        val form = listOfNotNull(look.reading?.takeIf { look.word.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN } },
            look.lemma?.let { "→ $it" }).joinToString("   ")
        if (form.isNotBlank()) Text(form, color = c.mute, fontSize = 14.sp)
        (Levels.chips(look.lang, level) + myDicts?.chips.orEmpty()).takeIf { it.isNotEmpty() }?.let { chips ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { Tag(it, font = if (it.startsWith("/")) targetFont() else null) }
            }
        }
        if (voiceHint) Text("No voice for this language yet. Install one under Android's Text-to-speech settings.", color = c.mute, fontSize = 12.sp)
        LookupTabs(look.lang, look.word, look.lemma) {
            myDicts?.entries?.takeIf { it.isNotEmpty() }?.let { ImportedBlock(it) }
            MeaningBlock("Dictionary", meanings.dictionary, DictionaryClient.SOURCE)
            MeaningBlock("In this sentence (AI)", meanings.explanation, null)
        }
        FButton("Add card", {
            val w = look.lemma ?: look.word; val s = look.sentence
            val m = meanings.explanation?.text ?: myDicts?.entries?.firstOrNull()?.glossary?.lineSequence()?.take(3)?.joinToString("; ").orEmpty()
            vm.clearLookup(); onAddCard(w, s, m, look.sentenceId)
        }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Check, null, tint = c.mute, modifier = Modifier.size(18.dp))
            Text("  Sentence saved", color = c.mute, fontSize = 14.sp)
        }
    }
}
