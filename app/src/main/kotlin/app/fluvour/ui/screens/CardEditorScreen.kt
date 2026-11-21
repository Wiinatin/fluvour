package app.fluvour.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/** Full-screen card editor. cardId == null creates a new card. */
@Composable
fun CardEditorScreen(vm: AppViewModel, cardId: String?, onDone: () -> Unit) {
    val c = Fluvour.colors
    val cards by vm.cards.collectAsStateWithLifecycle()
    val existing = cards.firstOrNull { it.id == cardId }
    val draft = remember { if (cardId == null) vm.takeDraft() else null }
    var word by remember(existing?.id) { mutableStateOf(existing?.word ?: draft?.word ?: "") }
    var meaning by remember(existing?.id) { mutableStateOf(existing?.meaning ?: draft?.meaning ?: "") }
    var example by remember(existing?.id) { mutableStateOf(existing?.example ?: draft?.example ?: "") }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes ?: draft?.notes ?: "") }
    // The mined sentence this card came from (reader). Used for the first card saved from the draft only.
    var sentenceId by remember { mutableStateOf(draft?.sentenceId) }
    // Malay (Sarawak form + sentence) and Arabic (root + lemma) fields; empty for every other language.
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lang = existing?.languageCode ?: settings?.targetLang ?: ""
    var variantWord by remember(existing?.id) { mutableStateOf(existing?.variantWord ?: draft?.variantWord ?: "") }
    var variantExample by remember(existing?.id) { mutableStateOf(existing?.variantExample ?: draft?.variantExample ?: "") }
    var variantExampleMeaning by remember(existing?.id) { mutableStateOf(existing?.variantExampleMeaning ?: "") }
    var root by remember(existing?.id) { mutableStateOf(existing?.root ?: draft?.root ?: "") }
    var lemma by remember(existing?.id) { mutableStateOf(existing?.lemma ?: draft?.lemma ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var savedCount by remember { mutableIntStateOf(0) }

    val duplicate = word.isNotBlank() && cards.any { it.id != existing?.id && app.fluvour.srs.sameWord(lang, it.word, word) }
    val canSave = word.isNotBlank() && meaning.isNotBlank()

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onDone, closeIcon = true)
        Eyebrow(if (cardId == null) "New card" else "Edit card", Modifier.padding(top = 8.dp))
        if (savedCount > 0) Sm("$savedCount added", Modifier.padding(top = 4.dp))
        UnderlineField("Word", word, { word = it }, singleLine = true, size = 26.sp, font = targetFont())
        if (duplicate) Sm("You already have a card with this word. You can still save it.", Modifier.padding(top = 6.dp), size = 12.sp)
        // TODO(reading): the design has a Reading field (romanization, furigana). CardEntity needs a column first.
        UnderlineField("Meaning", meaning, { meaning = it })
        UnderlineField("Example", example, { example = it }, placeholder = "A sentence you want to remember", font = targetFont(), minLines = 2)
        Sm("The word is highlighted on the card if it appears in the sentence.", Modifier.padding(top = 4.dp), size = 12.sp)
        if (lang == "ms") {
            UnderlineField("Sarawak word", variantWord, { variantWord = it }, placeholder = "Optional", singleLine = true, font = targetFont())
            if (variantWord.isNotBlank() && !app.fluvour.srs.needsSarawakFacet(word, variantWord)) Sm("Same in Sarawak: no separate Sarawak card to review.", Modifier.padding(top = 4.dp), size = 12.sp)
            else Sm("Standard and Sarawak are learned together as two review cards.", Modifier.padding(top = 4.dp), size = 12.sp)
            UnderlineField("Sarawak example", variantExample, { variantExample = it }, placeholder = "Optional", font = targetFont(), minLines = 2)
            UnderlineField("Sarawak example meaning", variantExampleMeaning, { variantExampleMeaning = it }, placeholder = "Optional: what the sentence means", minLines = 2)
            if (existing == null) Sm("Leave the Sarawak boxes empty and the AI suggests them when you save a new card (needs an AI key). They are marked in the notes so you can check them.", Modifier.padding(top = 4.dp), size = 12.sp)
        }
        if (lang == "ar") {
            UnderlineField("Root", root, { root = it }, placeholder = "Optional, e.g. ك-ت-ب", singleLine = true, font = targetFont())
            UnderlineField("Lemma", lemma, { lemma = it }, placeholder = "Optional", singleLine = true, font = targetFont())
            Sm("Vowel marks are kept on the card. Matching ignores them.", Modifier.padding(top = 4.dp), size = 12.sp)
        }
        UnderlineField("Notes", notes, { notes = it }, placeholder = "Optional", minLines = 2)
        if (existing != null) {
            val busy by vm.imageBusy.collectAsStateWithLifecycle()
            val imageMsg by vm.imageStatus.collectAsStateWithLifecycle()
            Eyebrow("Image", Modifier.padding(top = 22.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CardImage(existing.imageUrl, existing.updatedAt, Modifier.size(88.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sm(imageMsg ?: "Optional. Made from the word and meaning.", size = 12.5.sp)
                    SmallButton(if (busy) "Generating..." else if (existing.imageUrl == null) "Generate" else "New image", { if (!busy) vm.generateImage(existing.id) })
                }
            }
        }
        FButton("Save", { vm.saveCard(existing, word, meaning, example, notes, sentenceId, variantWord, variantExample, root, lemma, variantExampleMeaning); onDone() }, Modifier.fillMaxWidth().padding(top = 26.dp), enabled = canSave)
        if (cardId == null) FButton("Save and add another", {
            vm.saveCard(null, word, meaning, example, notes, sentenceId, variantWord, variantExample, root, lemma, variantExampleMeaning)
            word = ""; meaning = ""; example = ""; notes = ""; sentenceId = null; variantWord = ""; variantExample = ""; variantExampleMeaning = ""; root = ""; lemma = ""; savedCount++
        }, Modifier.fillMaxWidth().padding(top = 8.dp), outlined = true, enabled = canSave)
        if (existing != null) LinkText("Delete card", { confirmDelete = true }, Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = c.card,
        title = { Text("Delete this card?") }, text = { Text("${existing.word} and its review history will be removed from your reviews.") },
        confirmButton = { TextButton({ vm.deleteCard(existing.id); confirmDelete = false; onDone() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } }
    )
}
