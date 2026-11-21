package app.fluvour.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/** Settings > Word Power deck. The deck and the book are your own files; they stay on this phone and are never uploaded or synced. */
@Composable
internal fun WpmeGroup(vm: WpmeSettingsViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val count by vm.count.collectAsStateWithLifecycle()
    val book by vm.bookInfo.collectAsStateWithLifecycle()
    val linkCount by vm.linkCount.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val perDay by vm.newPerDay.collectAsStateWithLifecycle()
    // 0 none, 1 deck, 2 book, 3 links
    var confirm by remember { mutableIntStateOf(0) }
    // "*/*": phones label .apkg and .epub files inconsistently; the importers check the content instead.
    val deckPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importDeck(uri) }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importBook(uri) }
    val hasDeck = (count ?: 0) > 0

    SettingRow("Choose deck", sub = "Your own Anki .apkg (Word Power Made Easy). Gives roots, prefixes and words to study in the Word Power tab.", trailing = {
        SmallButton("Choose file", { if (!ui.busy) deckPicker.launch(arrayOf("*/*")) })
    })
    SettingRow("Choose book", sub = "Your own copy of the book as an .epub. It opens in the Read tab and gives the See more passages.", trailing = {
        SmallButton("Choose file", { if (!ui.busy) bookPicker.launch(arrayOf("*/*")) })
    })
    if (ui.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = c.accent, trackColor = c.line)
        Sm("${ui.progress ?: "Working"}. Keep the app open.", Modifier.padding(top = 8.dp))
    }
    ui.message?.let { Sm(it, Modifier.padding(top = 8.dp), color = c.ink) }
    if (hasDeck) SettingRow("Word Power deck", sub = "$count items · stays on this phone", trailing = {
        IconBtn(Icons.Outlined.Delete, "Remove Word Power deck", { confirm = 1 }, tint = c.mute)
    })
    book?.let { b ->
        SettingRow("Book", sub = "${b.title} · ${b.located} items found in it", trailing = {
            IconBtn(Icons.Outlined.Delete, "Remove the book", { confirm = 2 }, tint = c.mute)
        })
    }
    if (hasDeck) {
        val n = linkCount ?: 0
        SettingRow("Word parts", sub = "$n words have their parts. Built into the app, nothing to do.")
    }
    SettingRow("New items per day", sub = if (perDay == 0) "Paused: only reviews" else "Roots and prefixes first, then words whose parts you know", trailing = {
        FStepper(perDay.toString(), { vm.setNewPerDay(perDay - 1) }, { vm.setNewPerDay(perDay + 1) })
    })
    if (confirm != 0) {
        val (title, text) = when (confirm) {
            1 -> "Remove the Word Power deck?" to "The deck and the word parts will be removed from this phone. Your study progress is kept, so importing the file again carries on."
            2 -> "Remove the book?" to "It will be removed from the Read tab, and the See more and Read in the book buttons stop working. You can import it again later."
            else -> "Remove the word parts?" to "Word cards will show no parts and unlock freely until you build them again."
        }
        AlertDialog(
            onDismissRequest = { confirm = 0 }, containerColor = c.card,
            title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton({ when (confirm) { 1 -> vm.clearDeck(); 2 -> vm.clearBook(); else -> vm.clearLinks() }; confirm = 0 }) { Text("Remove") } },
            dismissButton = { TextButton({ confirm = 0 }) { Text("Cancel") } }
        )
    }
}

/** The Word Power tab on the Review screen (English). */
@Composable
fun WordPowerTab(onStudy: () -> Unit, vm: WpmeHomeViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    LaunchedEffect(Unit) { vm.refresh() }
    val st by vm.stats.collectAsStateWithLifecycle()
    val perDay by vm.newPerDay.collectAsStateWithLifecycle()
    val s = st
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            s == null -> CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
            s.total == 0 -> Sm("Import your Word Power deck in Settings (Word Power deck) to study it here.")
            else -> {
                H2("${s.learned} of ${s.total} learned")
                Sm("${s.due} due  ·  ${s.newLeftToday} new today  ·  ${s.learning} in learning")
                if (s.waiting > 0) Sm("${s.waiting} words are waiting for their parts.")
                FButton("Study", onStudy, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = s.due + s.newLeftToday > 0)
                Sm("Roots and prefixes come first, then words whose parts you know, $perDay new a day (change it in Settings). Progress stays on this phone.", Modifier.padding(top = 6.dp), size = 12.sp)
            }
        }
    }
}
