package app.fluvour.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import app.fluvour.ui.theme.ThemeMode
import app.fluvour.ui.theme.Themes

/**
 * Settings, laid out as in the design. Wired to real settings: learning language, interface language, theme, light/dark,
 * daily goal, auto-play, account and sync, Anki export.
 * In-memory only for now (not saved, no effect yet): illustrations, reading size and aid, playback speed, AI switches,
 * offline packs, accessibility switches, screen lookup, widget. Each marks what to connect.
 */
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onReplayIntro: () -> Unit) {
    val c = Fluvour.colors
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    var langSheet by remember { mutableStateOf(false) }
    var ocr by remember { mutableStateOf(false) }
    var widget by remember { mutableStateOf(false) }
    // TODO(settings): move these into SettingsRepository (DataStore) when the features behind them exist.
    var readSize by remember { mutableIntStateOf(2) }
    var aid by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    var aiImages by remember { mutableStateOf(true) }
    var aiExplain by remember { mutableStateOf(true) }
    var contrast by remember { mutableStateOf(false) }
    val speeds = listOf("0.8×", "1×", "1.2×")
    val uiLangs = Languages.uiLanguages
    val aidName = when (s.targetLang) { "th" -> "Romanization"; "ja" -> "Furigana"; else -> "Phonetic spelling" }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onBack)
        H1("Settings", Modifier.padding(top = 6.dp), size = 30.sp)

        Group("Language") {
            ValueRow("Learning", Languages.byCode(s.targetLang).native, { langSheet = true }, valueFont = targetFont())
            ValueRow("Interface language", uiLangs.firstOrNull { it.code == s.uiLang }?.native ?: "English", {
                val i = uiLangs.indexOfFirst { it.code == s.uiLang }
                vm.setUi(uiLangs[(i + 1).mod(uiLangs.size)].code)
            })
        }
        Group("Appearance") {
            Themes.all.forEach { t -> OptionRow(t.label, t.id == s.theme, { vm.setTheme(t.id) }, sub = t.blurb) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.values().forEach { m -> FButton(m.name, { vm.setMode(m) }, Modifier.weight(1f), outlined = m != s.mode, soft = false) }
            }
            SwitchRow("Illustrations", app.fluvour.ui.art.ArtPrefs.enabled, { app.fluvour.ui.art.ArtPrefs.enabled = it }, sub = "Textures and printed art")
        }
        Group("Daily goal") {
            SettingRow("Cards per day", sub = "New and review", trailing = {
                FStepper(s.dailyGoal.toString(), { vm.setDailyGoal((s.dailyGoal - 5).coerceAtLeast(5)) }, { vm.setDailyGoal((s.dailyGoal + 5).coerceAtMost(200)) })
            })
        }
        Group("Reading") {
            SettingRow("Text size", sub = "In the reader", trailing = {
                FStepper(listOf("XS", "S", "M", "L", "XL")[readSize], { readSize = (readSize - 1).coerceAtLeast(0) }, { readSize = (readSize + 1).coerceAtMost(4) })
            })
            SwitchRow(aidName, aid, { aid = it }, sub = "Shown above words in the reader")
        }
        Group("Audio") {
            SwitchRow("Play audio on reveal", s.autoPlay, { vm.setAutoPlay(it) })
            ValueRow("Playback speed", speeds[speed], { speed = (speed + 1) % speeds.size })
        }
        Group("AI and storage") {
            SwitchRow("AI images", aiImages, { aiImages = it }, sub = "Made for new cards")
            SwitchRow("AI explanations", aiExplain, { aiExplain = it }, sub = "Short notes in the word sheet")
        }
        Group("AI keys") { AiKeysGroup() }
        Group("Offline packs") {
            listOf("th" to "42 MB", "ja" to "38 MB", "en" to "31 MB").forEach { (code, size) ->
                val l = Languages.byCode(code)
                SettingRow(l.native, sub = "$size · ${l.name}", trailing = { SmallButton("Download", { /* TODO(offline): download dictionary and audio pack */ }) })
            }
        }
        Group("Account and sync") {
            val session by vm.session.collectAsStateWithLifecycle()
            val status by vm.accountStatus.collectAsStateWithLifecycle()
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            val signedIn = session
            if (signedIn == null) {
                UnderlineField("Email", email, { email = it }, singleLine = true, size = 17.sp)
                UnderlineField("Password", password, { password = it }, singleLine = true, size = 17.sp)
                FButton("Sign in", { vm.signIn(email, password) }, Modifier.fillMaxWidth().padding(top = 18.dp), enabled = email.isNotBlank() && password.isNotBlank())
            } else {
                Text("Signed in as ${signedIn.email}", color = c.ink, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 10.dp))
                FButton("Sync now", { vm.syncNow() }, Modifier.fillMaxWidth())
                FButton("Sign out", { vm.signOut() }, Modifier.fillMaxWidth().padding(top = 8.dp), outlined = true)
            }
            Sm(status ?: "Optional. Sync keeps your cards safe and enables AI pictures. Everything else works offline.", Modifier.padding(top = 10.dp), size = 12.sp)
        }
        Group("Dictionaries") { DictionariesGroup(s.targetLang) }
        Group("Export to Anki") {
            val exportStatus by vm.exportStatus.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.exportToAnki(uri) }
            SettingRow("Export cards", sub = exportStatus ?: "All languages. In Anki: File, Import, then choose the file.", trailing = {
                SmallButton("Export", { picker.launch("fluvour-cards.txt") })
            })
        }
        Group("Extras") {
            ValueRow("Screen lookup", "Set up", { ocr = true }, sub = "Look up words in other apps")
            ValueRow("Home-screen widget", "Preview", { widget = true })
        }
        Group("Accessibility") {
            SwitchRow("Stronger text contrast", contrast, { contrast = it })
            SwitchRow("Reduce motion", app.fluvour.ui.art.ArtPrefs.reduceMotion, { app.fluvour.ui.art.ArtPrefs.reduceMotion = it })
        }
        Group("About") {
            ValueRow("Version", "0.0.1")
            SettingRow("Replay intro", onClick = onReplayIntro, trailing = { ChevronGo() })
        }
        NotBuiltNote("Settings without a stored value reset when you leave this screen. They are placeholders for features still to come.")
        Spacer(Modifier.height(36.dp))
    }
    if (langSheet) LanguageSheet(s.targetLang, { vm.setTarget(it); langSheet = false }, { langSheet = false })
    if (ocr) OcrSetupSheet { ocr = false }
    if (widget) WidgetSheet("ข้าวมันไก่", "khâao man gài", "Hainanese chicken rice") { widget = false }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        Eyebrow(title, Modifier.padding(bottom = 2.dp))
        content()
    }
}

/**
 * Phase 2A step 9: Yomitan-format dictionaries. Imported for the language you are learning now; shown in the word popup
 * (meanings, frequency, pitch accent). Dictionaries of other languages stay listed and are used when you switch to them.
 */
@Composable
private fun DictionariesGroup(targetLang: String, vm: DictionaryViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val c = Fluvour.colors
    val dicts by vm.dictionaries.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<app.fluvour.data.DictionaryEntity?>(null) }
    // "*/*": phones label .zip files inconsistently; the reader checks the content instead.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    val name = Languages.byCode(targetLang).name
    SettingRow("Import a dictionary", sub = "A Yomitan .zip for $name (JMdict, CC-CEDICT, frequency or pitch lists...)", trailing = {
        SmallButton("Choose file", { if (!ui.busy) picker.launch(arrayOf("*/*")) })
    })
    if (ui.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = c.accent, trackColor = c.line)
        Sm("${ui.progress ?: "Importing"}. A large dictionary can take a minute; keep the app open.", Modifier.padding(top = 8.dp))
    }
    ui.message?.let { Sm(it, Modifier.padding(top = 8.dp), color = c.ink) }
    dicts.forEach { d ->
        val sub = listOfNotNull(Languages.byCode(d.languageCode).name, "${d.terms} entries", d.metaCount.takeIf { it > 0 }?.let { "$it extra" }, d.revision.takeIf { it.isNotBlank() }).joinToString(" \u00B7 ")
        SettingRow(d.title, sub = sub, onClick = { vm.setEnabled(d.id, !d.enabled) }, trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FSwitch(d.enabled)
                IconBtn(Icons.Outlined.Delete, "Remove dictionary", { toDelete = d }, tint = c.mute)
            }
        })
    }
    if (dicts.isEmpty() && !ui.busy) Sm("None yet. Dictionaries are separate files you choose; check each one's licence.", Modifier.padding(top = 8.dp), size = 12.sp)
    toDelete?.let { d ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = c.card,
            title = { Text("Remove this dictionary?") },
            text = { Text("\"${d.title}\" will be removed from this phone. You can import the file again later.") },
            confirmButton = { androidx.compose.material3.TextButton({ vm.delete(d.id); toDelete = null }) { Text("Remove") } },
            dismissButton = { androidx.compose.material3.TextButton({ toDelete = null }) { Text("Cancel") } }
        )
    }
}

/**
 * Your own AI keys, one list per provider (Gemini, Groq, Cerebras, Mistral, OpenRouter, Hugging Face). Add as many keys as you
 * like: when one hits its limit the next is used, newest key first (the rule of Momori). With at least one key the app talks to
 * the provider directly, so AI needs no sign-in and has no limit set by Fluvour. Text goes to Gemini first, then the others in
 * that order (providers without a key are skipped); pictures go to Hugging Face first, then Gemini. Keys stay on this phone.
 */
@Composable
private fun AiKeysGroup(vm: AiKeysViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val c = Fluvour.colors
    val all by vm.keys.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<app.fluvour.ai.AiProvider?>(null) }
    var text by remember { mutableStateOf("") }
    var acct by remember { mutableStateOf("") }   // Cloudflare only: the Account ID (the token goes in [text])
    var modelText by remember { mutableStateOf("") }
    var toRemove by remember { mutableStateOf<Pair<app.fluvour.ai.AiProvider, app.fluvour.ai.MaskedKey>?>(null) }
    val total = all.values.sumOf { it.size }

    Sm(
        if (total == 0) "No key saved. AI explanations and pictures use the shared service (needs sign-in, limited)."
        else "$total key${if (total == 1) "" else "s"} saved. AI goes straight to the providers below, in this order; a provider without a key is skipped.",
        Modifier.padding(top = 8.dp)
    )
    app.fluvour.ai.AiProvider.values().forEach { p ->
        val list = all[p].orEmpty()
        val sub = if (list.isEmpty()) "No key" else "${list.size} key${if (list.size == 1) "" else "s"} · ${list.count { it.limitedAt != null }} hit a limit"
        val what = listOfNotNull("text".takeIf { p.text }, "pictures".takeIf { p.image }).joinToString(" and ")
        SettingRow(p.label, sub = "$sub · $what", onClick = {
            open = if (open == p) null else p; text = ""; acct = ""; modelText = models[p].orEmpty(); vm.clearMessage()
        }, trailing = { Text(if (open == p) "Close" else if (list.isEmpty()) "Add" else "Open", color = c.accent, fontSize = 13.sp) })
        if (open == p) {
            val cloudflare = p == app.fluvour.ai.AiProvider.CLOUDFLARE
            if (cloudflare) {
                UnderlineField("Account ID", acct, { acct = it }, placeholder = "32 letters and numbers, from the Cloudflare dashboard", singleLine = true, size = 15.sp)
                UnderlineField("API token", text, { text = it }, placeholder = "Token with the Workers AI permission", singleLine = true, size = 15.sp)
            } else {
                UnderlineField("Add ${p.label} keys", text, { text = it }, placeholder = "Paste one key, or several", size = 15.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Add", {
                    if (!cloudflare) vm.add(p, text)
                    else if (acct.isBlank() && ":" in text) vm.add(p, text)                // pasted as ACCOUNT_ID:TOKEN
                    else if (acct.isBlank() || text.isBlank()) vm.add(p, "")               // shows "Paste a key first."
                    else vm.add(p, acct.trim() + ":" + text.trim())
                    text = ""; acct = ""
                })
                message?.let { Sm(it, color = c.ink) }
            }
            list.forEach { k ->
                val ks = app.fluvour.ai.LimitText.status(k.limitedAt, k.active, System.currentTimeMillis())
                SettingRow(k.masked, sub = ks, trailing = { IconBtn(Icons.Outlined.Delete, "Remove key", { toRemove = p to k }, tint = c.mute) })
            }
            if (p.text) {
                UnderlineField("Model (optional)", modelText, { modelText = it }, placeholder = p.defaultModel, singleLine = true, size = 15.sp)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("Save model", { vm.setModel(p, modelText) }, soft = true)
                    Sm(if (p == app.fluvour.ai.AiProvider.HUGGINGFACE) "For text. Pictures always use FLUX.1-schnell." else "Leave empty for the default.", size = 12.sp)
                }
            }
            Sm("Get a key at ${p.keysAt}." + p.keyHint.takeIf { it.isNotBlank() }?.let { " $it." }.orEmpty() +
                " A key that hits its limit is skipped and the next one is used; it is tried again after the working ones.", Modifier.padding(top = 10.dp), size = 12.sp)
        }
    }
    toRemove?.let { (p, k) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toRemove = null }, containerColor = c.card,
            title = { Text("Remove this key?") },
            text = { Text("${k.masked} (${p.label}) will be deleted from this phone. Paste it again to use it later.") },
            confirmButton = { androidx.compose.material3.TextButton({ vm.remove(p, k.id); toRemove = null }) { Text("Remove") } },
            dismissButton = { androidx.compose.material3.TextButton({ toRemove = null }) { Text("Cancel") } }
        )
    }
}
