package app.fluvour.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ai.JournalEntry
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun dayLabel(ms: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * Journal (route "journal"): a list of your entries, a quiet writing page, and the result with the AI's corrections.
 * Entries are saved as you leave the writing page, so nothing is lost. The corrected text can be played and every word tapped for a meaning.
 */
@Composable
fun JournalScreen(lang: String, onClose: () -> Unit, vm: JournalViewModel = hiltViewModel()) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }     // entry id, or "new"
    var draft by rememberSaveable { mutableStateOf("") }
    val entries by remember(lang) { vm.entries(lang) }.collectAsStateWithLifecycle(emptyList())
    val current = if (open == null || open == "new") null else entries.firstOrNull { it.id == open }
    DisposableEffect(Unit) { onDispose { vm.stopSound() } }
    val editorId = open?.removePrefix("edit:")?.takeIf { it != "new" }
    androidx.activity.compose.BackHandler(enabled = open != null) {
        if (current?.corrected == null) vm.saveDraft(editorId, lang, draft)
        vm.stopSound(); open = null
    }
    when {
        open == null -> JournalList(lang, entries, onClose, onNew = { draft = ""; open = "new" }, onOpen = { open = it.id; draft = it.text }, onDelete = { vm.delete(it.id) })
        current != null && current.corrected != null -> JournalResult(current, vm, onEdit = { vm.stopSound(); draft = current.text; open = "edit:" + current.id },
            onClose = { vm.stopSound(); open = null })
        else -> JournalEditor(lang, vm, id = editorId, draft = draft, onDraft = { draft = it },
            onBack = { vm.saveDraft(editorId, lang, draft); open = null },
            onChecked = { id -> open = id })
    }
}

@Composable
private fun JournalList(lang: String, entries: List<JournalEntry>, onClose: () -> Unit, onNew: () -> Unit, onOpen: (JournalEntry) -> Unit, onDelete: (JournalEntry) -> Unit) {
    var toDelete by remember { mutableStateOf<JournalEntry?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        PageBar(onClose, "Journal")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            H1("Your journal", Modifier.padding(top = 18.dp), size = 30.sp)
            Sm("Write a few lines in ${Languages.byCode(lang).name}. Mistakes are fine, the AI will correct them.", Modifier.padding(top = 6.dp, bottom = 10.dp))
            if (entries.isEmpty()) Sm("No entries yet.", Modifier.padding(top = 14.dp))
            entries.forEach { e ->
                key(e.id) {
                    SwipeToRemove(onRemove = { toDelete = e }) {
                        FRow(e.text.lineSequence().first().take(60), subtitle = dayLabel(e.updatedAt) + if (e.corrected != null) " · corrected" else " · not checked yet",
                            titleFont = targetFont(), onClick = { onOpen(e) })
                    }
                }
            }
        }
        FButton("New entry", onNew, Modifier.fillMaxWidth().padding(vertical = 18.dp))
    }
    toDelete?.let { e ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = Fluvour.colors.card,
            title = { Text("Delete this entry?") }, text = { Text("Cards you saved from it stay.") },
            confirmButton = { androidx.compose.material3.TextButton({ onDelete(e); toDelete = null }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton({ toDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun JournalEditor(lang: String, vm: JournalViewModel, id: String?, draft: String, onDraft: (String) -> Unit, onBack: () -> Unit, onChecked: (String) -> Unit) {
    val c = Fluvour.colors
    val checking by vm.checking.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var waiting by remember { mutableStateOf<String?>(null) }
    // when the AI answer arrives for the entry we sent, show its result
    val sent = waiting?.let { vm.entry(it) }
    LaunchedEffect(sent?.corrected) { if (sent?.corrected != null) onChecked(sent.id) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        PageBar(onBack, if (id == null) "New entry" else "Edit entry", closeIcon = true)
        Sm(Languages.byCode(lang).name + " · " + dayLabel(System.currentTimeMillis()), Modifier.padding(top = 16.dp))
        TextField(
            draft, onDraft, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp),
            textStyle = TextStyle(fontFamily = targetFont(), fontSize = 23.sp, lineHeight = 41.sp, color = c.ink),
            placeholder = { Text("Start here", color = c.mute, fontFamily = targetFont(), fontSize = 23.sp) },
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = c.accent)
        )
        error?.let { Sm(it, Modifier.padding(bottom = 6.dp)) }
        FButton(if (checking) "Checking..." else "Check with AI", {
            val eid = vm.saveDraft(id, lang, draft)
            if (eid != null) { waiting = eid; vm.check(eid) }
        }, Modifier.fillMaxWidth().padding(vertical = 18.dp), enabled = draft.isNotBlank() && !checking)
    }
}

@Composable
private fun JournalResult(e: JournalEntry, vm: JournalViewModel, onEdit: () -> Unit, onClose: () -> Unit) {
    val c = Fluvour.colors
    val lookup: WordLookupViewModel = hiltViewModel()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val state by rememberPlayState { vm.playState() }
    val corrected = e.corrected.orEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        PageBar(onClose, "Corrections", closeIcon = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            H1(if (e.fixes.isNotEmpty()) "${e.fixes.size} to fix" else "Nicely written", Modifier.padding(top = 14.dp, bottom = 14.dp), size = 30.sp)
            AuthorPanel(ai = false, title = "You wrote") {
                TapText(e.text, e.lang, size = 21.sp, onWord = { w, l, r -> lookup.select(w, l, r, sentenceOf(e.text, w), e.lang) })
            }
            AuthorPanel(ai = true, title = "Corrected", modifier = Modifier.padding(top = 14.dp)) {
                TapText(corrected, e.lang, size = 21.sp, lit = state.fraction, compareTo = e.text, onWord = { w, l, r -> lookup.select(w, l, r, sentenceOf(corrected, w), e.lang) })
                Sm("Highlighted words are what the AI changed.", Modifier.padding(top = 8.dp), size = 12.sp)
                AudioBar(state, speed, onPlay = { vm.play(e.lang, corrected) }, onPause = { vm.pauseSound() }, onResume = { vm.resumeSound() },
                    onSeek = { vm.seekSound(it) }, onSpeed = { vm.setSpeed(it) }, loading = loading && !state.active, modifier = Modifier.padding(top = 10.dp))
            }
            if (e.fixes.isNotEmpty()) AuthorPanel(ai = true, title = "What changed", modifier = Modifier.padding(top = 14.dp)) {
                e.fixes.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 10.dp))
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TargetText(f.wrong, Modifier.weight(1f), size = 16.sp, color = c.mute)
                        Text("→", color = c.mute, fontSize = 16.sp)
                        TargetText(f.right, Modifier.weight(1f), size = 16.sp)
                    }
                    if (f.why.isNotBlank()) Sm(f.why, Modifier.padding(top = 4.dp), size = 12.5.sp)
                }
            }
            AuthorPanel(ai = true, title = "Words worth learning", modifier = Modifier.padding(top = 14.dp)) {
                if (e.words.isEmpty()) Sm("Nothing new this time.", size = 13.sp)
                e.words.forEachIndexed { i, w ->
                    if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            TargetText(w.word, size = 19.sp)
                            Sm(w.meaning, size = 13.sp)
                            w.example?.let { TargetText(it, Modifier.padding(top = 2.dp), size = 14.sp, color = c.mute) }
                        }
                        if (w.cardId != null) SmallButton("Saved", {}, soft = true) else SmallButton("Save", { vm.saveWord(e.id, w.word) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton("Edit entry", onEdit, Modifier.weight(1f), outlined = true)
            FButton("Done", onClose, Modifier.weight(1f))
        }
    }
    OutputWordSheet(lookup)
}
