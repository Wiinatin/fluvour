package app.fluvour.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/** Settings > Heisig deck. The deck is your own file; it stays on this phone and is never uploaded or synced. */
@Composable
internal fun HeisigGroup(vm: HeisigViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val count by vm.count.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val perDay by vm.newPerDay.collectAsStateWithLifecycle()
    val maturity by vm.maturityColors.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    // "*/*": phones label .apkg files inconsistently; the importer checks the content instead.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    SettingRow("Import Heisig deck", sub = "Your own Anki .apkg (All in one Kanji, Heisig order). Shows keyword, frame and story in the Kanji tab.", trailing = {
        SmallButton("Choose file", { if (!ui.busy) picker.launch(arrayOf("*/*")) })
    })
    if (ui.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = c.accent, trackColor = c.line)
        Sm("${ui.progress ?: "Importing"}. Keep the app open.", Modifier.padding(top = 8.dp))
    }
    ui.message?.let { Sm(it, Modifier.padding(top = 8.dp), color = c.ink) }
    count?.takeIf { it > 0 }?.let { n ->
        SettingRow("Heisig deck", sub = "$n kanji · stays on this phone", trailing = {
            IconBtn(Icons.Outlined.Delete, "Remove Heisig deck", { confirm = true }, tint = c.mute)
        })
    }
    SettingRow("New kanji per day", sub = if (perDay == 0) "Paused: only reviews" else "Unlocked in Heisig order when you study", trailing = {
        FStepper(perDay.toString(), { vm.setNewPerDay(perDay - 1) }, { vm.setNewPerDay(perDay + 1) })
    })
    SwitchRow("Kanji maturity colours", maturity, { vm.setMaturityColors(it) }, sub = "Tint kanji you have studied in reading and watch, from copper to radiant")
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = c.card,
        title = { Text("Remove the Heisig deck?") },
        text = { Text("It will be removed from this phone. You can import the file again later.") },
        confirmButton = { TextButton({ vm.clear(); confirm = false }) { Text("Remove") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } }
    )
}
