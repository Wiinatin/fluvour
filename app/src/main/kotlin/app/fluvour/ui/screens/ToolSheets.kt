package app.fluvour.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.data.CardEntity
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/**
 * Small sheets from the design. Each one is complete as UI. The buttons that need a feature that does not exist yet
 * carry a TODO(...) comment saying what to connect.
 */

/**
 * "Try a new angle" for a card missed 6 or more times (Phase 3 step 4). A new picture, a mnemonic, or a new example sentence,
 * made by the AI with your keys. A mnemonic is added to the card's notes; a sentence becomes the card's example.
 */
@Composable
fun LeechSheet(card: CardEntity, onClose: () -> Unit, vm: app.fluvour.quiz.LeechViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }
    var option by remember { mutableIntStateOf(0) }
    val options = listOf("A new image" to "Something to picture", "A mnemonic" to "A short hook to remember it by", "A new example" to "Another sentence with the word")
    fun make() { when (option) { 0 -> vm.image(card); 1 -> vm.mnemonic(card); else -> vm.example(card) } }
    FSheet(onClose) {
        SheetHeader("A new angle", onClose)
        TargetText(card.word, Modifier.padding(top = 10.dp), size = 26.sp)
        if (step == 0) {
            Sm("Missed ${card.lapses} times. A different way in often helps.", Modifier.padding(top = 4.dp, bottom = 10.dp))
            options.forEachIndexed { i, (t, s) -> OptionRow(t, option == i, { option = i }, sub = s) }
            FButton("Continue", { vm.reset(); step = 1; make() }, Modifier.fillMaxWidth().padding(top = 18.dp))
        } else {
            Sm(options[option].first, Modifier.padding(top = 4.dp))
            if (ui.busy) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp), color = c.accent, trackColor = c.line)
                Sm("Working on it.", Modifier.padding(top = 8.dp))
            }
            ui.text?.let { if (option == 2) TargetText(it, Modifier.padding(top = 16.dp), size = 22.sp) else H2(it, Modifier.padding(top = 16.dp), size = 19.sp) }
            ui.message?.let { Sm(it, Modifier.padding(top = 12.dp), color = c.ink) }
            if (ui.saved && option != 0) Sm("Saved on the card.", Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FButton("Back", { vm.reset(); step = 0 }, Modifier.weight(1f), outlined = true)
                when {
                    option == 0 -> FButton("Done", onClose, Modifier.weight(1f), enabled = !ui.busy)
                    ui.text != null && !ui.saved -> FButton("Use this", { if (option == 1) vm.keepMnemonic(card) else vm.keepExample(card) }, Modifier.weight(1f))
                    ui.saved -> FButton("Done", onClose, Modifier.weight(1f))
                    else -> FButton("Try again", { make() }, Modifier.weight(1f), enabled = !ui.busy)
                }
            }
        }
    }
}

/**
 * "Add to Read" (Phase 2A step 8): paste text, or pick a book or file. The screen does the saving and opens the result.
 * Subtitles (.srt) are not here yet: they arrive with the Watch screen (Phase 4, step 6).
 */
@Composable
fun ImportSheet(busy: Boolean, message: String?, onPaste: (String) -> Unit, onFile: (android.net.Uri) -> Unit, onClose: () -> Unit) {
    val c = Fluvour.colors
    var paste by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    // "*/*" on purpose: phones label .epub and .txt files inconsistently, so the file's extension is checked after picking.
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFile(uri)
    }
    FSheet(onClose) {
        SheetHeader("Add to Read", onClose)
        if (busy) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp), color = c.accent, trackColor = c.line)
            Sm("Importing. A long book can take a few seconds.", Modifier.padding(top = 8.dp))
        }
        message?.let { Sm(it, Modifier.padding(top = 12.dp), color = c.ink) }
        if (paste) {
            UnderlineField("Paste text", text, { text = it }, placeholder = "Paste an article, a chapter, anything", minLines = 6)
            FButton("Import", { onPaste(text) }, Modifier.fillMaxWidth().padding(top = 22.dp), enabled = text.isNotBlank() && !busy)
        } else {
            FRow("Paste text", subtitle = "From the clipboard or anywhere", onClick = { paste = true })
            FRow("Book or file", subtitle = ".epub, .txt, .html", onClick = { if (!busy) picker.launch(arrayOf("*/*")) })
            FRow("Subtitles", subtitle = ".srt, with the Watch screen later", trailing = null, divider = false)
        }
    }
}

/** Reader "Aa" sheet. Text size and reading aid. Held in memory for now; persist with the other settings. */
@Composable
fun TextOptionsSheet(size: Int, onSize: (Int) -> Unit, aid: Boolean, onAid: (Boolean) -> Unit, aidLabel: String, onClose: () -> Unit) {
    FSheet(onClose) {
        SheetHeader("Reading", onClose)
        SettingRow("Text size", Modifier.padding(top = 8.dp), trailing = {
            FStepper(listOf("XS", "S", "M", "L", "XL")[size], { onSize((size - 1).coerceAtLeast(0)) }, { onSize((size + 1).coerceAtMost(4)) })
        })
        SwitchRow(aidLabel, aid, onAid, sub = "Shown above words")
    }
}

/**
 * Screen lookup (Phase 2B): turns the floating button on or off and shows what it needs. Real: overlay permission, optional
 * accessibility service (only names the app in front), AI fallback switch, and the capture permission Android asks for on Start.
 */
@Composable
fun OcrSetupSheet(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { app.fluvour.ocr.OcrPrefs(ctx) }
    var tick by remember { mutableStateOf(0) }
    // Check again when coming back from one of Android's own settings screens.
    val owner = remember(ctx) {
        var x: android.content.Context = ctx
        while (x is android.content.ContextWrapper && x !is androidx.lifecycle.LifecycleOwner) x = x.baseContext
        x as? androidx.lifecycle.LifecycleOwner
    }
    DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner?.lifecycle?.addObserver(ob)
        onDispose { owner?.lifecycle?.removeObserver(ob) }
    }
    val running by app.fluvour.ocr.OverlayService.isRunning.collectAsState()
    val overlayOk = remember(tick) { android.provider.Settings.canDrawOverlays(ctx) }
    val a11yOk = remember(tick) { app.fluvour.ocr.ForegroundApp.isEnabled(ctx) }
    var ai by remember { mutableStateOf(prefs.aiFallback) }
    val overlaySettings = {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
    }
    FSheet(onClose) {
        SheetHeader("Screen lookup", onClose)
        FRow("Allow display over other apps", subtitle = "Lets the lookup button sit on top of your manga or reader.",
            trailing = { if (overlayOk) Tag("On") else SmallButton("Allow", { overlaySettings() }) })
        FRow("Name the app in saved sentences", subtitle = "Optional. Turn on \"Fluvour: app name for screen lookup\" in Accessibility. It only learns which app is in front; it cannot read your screen.",
            trailing = { if (a11yOk) Tag("On") else SmallButton("Open", { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) })
        SwitchRow("Let AI read the screen", ai, { ai = it; prefs.aiFallback = it },
            sub = "Only when the on-device reader finds nothing or cannot read the language (Thai). Sends that screenshot to Gemini. Off: pictures never leave the phone.")
        FRow("Button position", subtitle = "Drag the button to move it.", trailing = { SmallButton("Reset", { prefs.resetBubble() }) }, divider = false)
        Sm("Tap the button to read the screen. Long-press it to read only an area. On Android 14 and newer, choose \"Entire screen\" when Android asks.", Modifier.padding(top = 10.dp))
        FButton(
            if (running) "Turn off" else "Start now",
            {
                when {
                    running -> app.fluvour.ocr.OverlayService.stop(ctx)
                    !overlayOk -> overlaySettings()
                    else -> { ctx.startActivity(android.content.Intent(ctx, app.fluvour.ocr.CaptureRequestActivity::class.java)); onClose() }
                }
            },
            Modifier.fillMaxWidth().padding(top = 18.dp)
        )
    }
}

/** What the home-screen widget will look like. UI only: the real widget needs Glance. */
@Composable
fun WidgetSheet(word: String, reading: String, meaning: String, onClose: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    FSheet(onClose) {
        SheetHeader("Home-screen widget", onClose)
        FCard(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Eyebrow("Word for today")
            TargetText(word, Modifier.padding(top = 8.dp), size = 34.sp)
            if (reading.isNotBlank()) Sm(reading)
            if (shown) H2(meaning, Modifier.padding(top = 12.dp), size = 20.sp)
            FButton(if (shown) "Hide" else "Show", { shown = !shown }, Modifier.padding(top = 14.dp), outlined = true)
        }
        Sm("Press and hold an empty spot on your home screen, then choose Widgets.", Modifier.padding(top = 14.dp))
        // TODO(widget): Glance AppWidget fed by the next due card
    }
}

/** A sheet for a looked-up word from the screen-lookup overlay. UI only; kept for the OCR phase. */
@Composable
fun OcrResultSheet(source: String, word: String, reading: String, meaning: String, onAdd: () -> Unit, onClose: () -> Unit) {
    FSheet(onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tag(source); Spacer(Modifier.weight(1f)); IconBtn(Icons.Outlined.Info, "Close", onClose)
        }
        TargetText(word, Modifier.padding(top = 16.dp), size = 34.sp)
        if (reading.isNotBlank()) Sm(reading)
        H2(meaning, Modifier.padding(top = 12.dp), size = 20.sp)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton("Add to cards", onAdd, Modifier.weight(1f)); FButton("Close", onClose, Modifier.weight(1f), outlined = true)
        }
    }
}
