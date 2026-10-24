package app.fluvour.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ui.ExtractorViewModel
import app.fluvour.ui.SettingRow
import app.fluvour.ui.SmallButton
import app.fluvour.ui.Sm
import app.fluvour.ui.theme.Fluvour
import java.text.DateFormat
import java.util.Date

/** Settings > YouTube extractor. Shows the running version and checks for a newer one on request. */
@Composable
internal fun ExtractorGroup(vm: ExtractorViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val last = if (ui.lastCheck > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ui.lastCheck)) else "never"
    SettingRow(
        "YouTube extractor",
        sub = "${ui.version ?: "loading"} · ${if (ui.downloaded) "downloaded update" else "built into the app"}",
        trailing = { SmallButton(if (ui.busy) "Checking" else "Check now", { vm.check() }) }
    )
    ui.message?.let { Sm(it, Modifier.padding(top = 8.dp), color = Fluvour.colors.ink) }
    Sm(
        if (ui.configured) "Updates itself in the background. Last checked: $last." else "Auto-update is off in this build (no update address).",
        Modifier.padding(top = 8.dp), size = 12.sp
    )
}
