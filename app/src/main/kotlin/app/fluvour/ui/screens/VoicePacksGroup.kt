package app.fluvour.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.audio.PackState
import app.fluvour.audio.VoicePack
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/** Offline voices, one row per pack (a pack such as Kokoro covers several languages). The pack for the learning language comes first. */
@Composable
fun ColumnScope.VoicePacksGroup(targetLang: String, vm: VoicePacksViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val c = Fluvour.colors
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val installed by vm.installedIds.collectAsStateWithLifecycle()
    val prefer by vm.preferOffline.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val sarawak by vm.sarawakAccent.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<VoicePack?>(null) }
    val metered = remember { vm.onMetered() }

    Sm("Offline voices for when Gemini cannot speak. Download once, then they work without internet. They sound less natural than Gemini, Thai and Korean most of all.", Modifier.padding(top = 4.dp, bottom = 4.dp))
    if (targetLang == "ms") {
        SwitchRow("Sarawak accent (test)", sarawak, { vm.setSarawakAccent(it) }, sub = "Asks Gemini to speak Malay with a Sarawak accent. It may not be accurate. Turning it on or off makes new sounds, which uses your Gemini limit.")
    }
    if (installed.isNotEmpty()) {
        SwitchRow("Prefer offline voice", prefer, { vm.setPreferOffline(it) }, sub = "Use a downloaded voice first and keep Gemini for languages without one. Saves your Gemini limit.")
    }
    val list = catalog?.packs.orEmpty().sortedByDescending { targetLang in it.languages }
    if (list.isEmpty()) {
        Sm(
            if (!vm.isConfigured()) "Voice packs are not set up in this build." else "No pack list yet. Connect to the internet and open Settings again.",
            Modifier.padding(top = 8.dp), size = 12.sp
        )
    }
    list.forEach { pack ->
        val name = pack.languages.keys.joinToString(", ") { Languages.byCode(it).name }
        val mb = "${(pack.size + 524_288) / 1_048_576} MB"
        val base = "${pack.name} · $mb"
        val state = states[pack.id] ?: if (pack.id in installed) PackState.Ready() else PackState.NotInstalled
        val testLang = if (targetLang in pack.languages) targetLang else pack.languages.keys.first()
        when (state) {
            is PackState.NotInstalled -> SettingRow(name, sub = if (metered) "$base · uses mobile data" else base, onClick = { vm.download(pack.id) },
                trailing = { Text("Download", color = c.accent, fontSize = 13.sp) })
            is PackState.Downloading -> SettingRow(name, sub = "Downloading ${state.percent}%", onClick = { vm.cancel(pack.id) },
                trailing = { Text("Cancel", color = c.accent, fontSize = 13.sp) })
            is PackState.Installing -> SettingRow(name, sub = "Installing...")
            is PackState.Failed -> SettingRow(name, sub = "Download stopped. Tap to try again.", onClick = { vm.download(pack.id) })
            is PackState.Ready -> SettingRow(name, sub = if (state.updateAvailable) "$base · update available" else "$base · ready", trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.updateAvailable) SmallButton("Update", { vm.download(pack.id) })
                    SmallButton("Test", { vm.test(pack.id, testLang) }, soft = true)
                    IconBtn(Icons.Outlined.Delete, "Delete voice pack", { toDelete = pack }, tint = c.mute)
                }
            })
        }
    }
    note?.let { Sm(it, Modifier.padding(top = 6.dp), size = 12.sp) }
    toDelete?.let { d ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = c.card,
            title = { Text("Delete this voice?") },
            text = { Text("${d.languages.keys.joinToString(", ") { Languages.byCode(it).name }} will be removed from this phone. You can download it again later.") },
            confirmButton = { androidx.compose.material3.TextButton({ vm.delete(d.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton({ toDelete = null }) { Text("Cancel") } }
        )
    }
}
