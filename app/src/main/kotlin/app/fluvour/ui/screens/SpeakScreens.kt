package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.language.Languages
import app.fluvour.ui.*
import app.fluvour.ui.sample.Sample
import app.fluvour.ui.sample.forLang
import app.fluvour.ui.theme.Fluvour
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ai.Measures
import app.fluvour.ai.SpeakingMeasures
import app.fluvour.ai.SpeakingParse
import app.fluvour.data.SpeakingTopicRow
import app.fluvour.data.SpeakingVersionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Output tab: speak about a topic and write a journal entry. Topics come from the database and are recorded, transcribed and improved;
 * journal entries are kept in a small file and checked by the AI.
 */
@Composable
fun SpeakScreen(lang: String, onTopic: (String) -> Unit, onJournal: () -> Unit,
                vm: SpeakingViewModel = hiltViewModel(), jvm: JournalViewModel = hiltViewModel()) {
    val entries by remember(lang) { jvm.entries(lang) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).ambient().languageMark().padding(horizontal = 22.dp)) {
        Eyebrow("Speak and write", Modifier.padding(top = 24.dp))
        H1("Output", Modifier.padding(top = 8.dp))
        SectionEyebrow("Speak", top = 22.dp)
        Sm("Pick a topic, talk for up to 3 minutes, and see how a natural speaker would say it.", Modifier.padding(top = 4.dp, bottom = 6.dp))
        TopicsTab(lang, vm, onTopic)
        SectionEyebrow("Write", top = 34.dp)
        FRow("Journal", subtitle = if (entries.isEmpty()) "Write a few lines in ${Languages.byCode(lang).name} and get them corrected"
            else "${entries.size} ${if (entries.size == 1) "entry" else "entries"} · last ${shortDate(entries.first().updatedAt)}", onClick = onJournal)
        Spacer(Modifier.height(30.dp))
    }
}

private fun shortDate(ms: Long): String = DateTimeFormatter.ofPattern("d MMM").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun topicSubtitle(r: SpeakingTopicRow): String = when {
    r.versionCount == 0 -> "No recordings yet"
    else -> "${r.versionCount} ${if (r.versionCount == 1) "recording" else "recordings"} · last ${shortDate(r.lastAt ?: r.topic.createdAt)}"
}

/** The Topic tab: a new-topic field with suggested titles, then your topics with their number of recordings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicsTab(lang: String, vm: SpeakingViewModel, onTopic: (String) -> Unit) {
    val rows by remember(lang) { vm.topics(lang) }.collectAsStateWithLifecycle(emptyList())
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<SpeakingTopicRow?>(null) }
    LaunchedEffect(lang) { vm.loadSuggestions(lang) }
    UnderlineField("New topic", text, { text = it }, placeholder = "Type a topic", singleLine = true, font = targetFont())
    FButton("Start", { vm.createTopic(lang, text, onTopic); text = "" }, Modifier.fillMaxWidth().padding(top = 14.dp), enabled = text.isNotBlank())
    if (suggestions.isNotEmpty()) {
        Eyebrow("Or pick one", Modifier.padding(top = 20.dp, bottom = 10.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { t -> Tag(t, Modifier.clip(RoundedCornerShape(50)).clickable { vm.createTopic(lang, t, onTopic) }, font = targetFont()) }
        }
        LinkText("New ideas", { vm.loadSuggestions(lang, force = true) }, Modifier.padding(top = 10.dp))
    }
    SectionEyebrow("Your topics", top = 26.dp)
    if (rows.isEmpty()) Sm("No topics yet. Type one above or pick a suggestion.", Modifier.padding(top = 6.dp))
    rows.forEachIndexed { i, r ->
        key(r.topic.id) {
            SwipeToRemove(onRemove = { toDelete = r }) {
                FRow(r.topic.title, subtitle = topicSubtitle(r), titleFont = targetFont(), onClick = { onTopic(r.topic.id) },
                    leading = { CoverArt(app.fluvour.ui.art.topicArtKey(lang, i, r.topic.title), Modifier.size(44.dp, 56.dp), 6.dp, small = true) })
            }
        }
    }
    toDelete?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = Fluvour.colors.card,
            title = { Text("Delete this topic?") },
            text = { Text(if (r.versionCount > 0) "${r.topic.title} and its ${r.versionCount} ${if (r.versionCount == 1) "recording" else "recordings"} will be deleted. Cards you made from it stay." else "${r.topic.title} will be deleted.") },
            confirmButton = { androidx.compose.material3.TextButton({ vm.deleteTopic(r.topic.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton({ toDelete = null }) { Text("Cancel") } }
        )
    }
}

private fun mmss(s: Int) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

/**
 * Topic page (route "topic/{id}"): the cue card, the record button with a timer, a retry line when a saved recording could not be
 * processed, and then the result. Replaces the sample topic session. Recording and the AI steps live in [SpeakingViewModel].
 */
@Composable
fun TopicScreen(topicId: String, onClose: () -> Unit, vm: SpeakingViewModel = hiltViewModel()) {
    LaunchedEffect(topicId) { vm.open(topicId) }
    DisposableEffect(Unit) { onDispose { vm.stopSound() } }
    val phase by vm.phase.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    val v = shown
    val fromList by vm.fromList.collectAsStateWithLifecycle()
    if (phase == SpeakPhase.RESULT && v != null) {
        TopicResult(v, vm, fromList, onAgain = { vm.again() }, onClose = if (fromList) ({ vm.again() }) else onClose)
        return
    }
    RecordPage(vm, phase, onClose)
}

@Composable
private fun RecordPage(vm: SpeakingViewModel, phase: SpeakPhase, onClose: () -> Unit) {
    val c = Fluvour.colors
    val ctx = LocalContext.current
    val topic by vm.topic.collectAsStateWithLifecycle()
    val cues by vm.cues.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedSec.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val detail by vm.failDetail.collectAsStateWithLifecycle()
    val denied by vm.micDenied.collectAsStateWithLifecycle()
    val askMic = rememberMicRequest(onGranted = { vm.startRecording() }, onDenied = { vm.onMicDenied() })
    val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val unfinished = versions.lastOrNull()?.takeIf { it.improved == null }
    val rec = phase == SpeakPhase.RECORDING
    val busy = phase == SpeakPhase.PROCESSING
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Topic · up to 3 min", Modifier.weight(1f))
            IconBtn(Icons.Outlined.Close, "Close", { vm.discardRecording(); onClose() })
        }
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Sm(if (rec) "Speaking now" else if (busy) "Processing" else "Speak about this topic")
            H1(topic?.title ?: "", Modifier.padding(top = 6.dp), size = 30.sp, font = targetFont())
            if (rec) TimerRing(1f - elapsed / 180f, mmss(elapsed), Modifier.padding(vertical = 18.dp))
        }
        if (cues.isNotEmpty() && !rec) AuthorPanel(ai = true, title = "Cue words", modifier = Modifier.padding(top = 18.dp)) {
            cues.forEachIndexed { i, cue ->
                if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
                TargetText(cue.first, size = 18.sp)
                if (cue.second.isNotBlank()) Sm(cue.second, size = 12.5.sp)
            }
        }
        if (busy) Sm("This can take a few seconds. Your recording is saved.", Modifier.padding(top = 18.dp))
        if (!busy && !rec && unfinished != null) {
            Sm("Could not process this. Your recording is saved. Try again.", Modifier.padding(top = 18.dp))
            detail?.let { Sm(it, Modifier.padding(top = 4.dp)) }
            FButton("Try again", { vm.retry(unfinished.id) }, Modifier.fillMaxWidth().padding(top = 12.dp), outlined = true)
        }
        if (!rec && !busy) VersionHistory(vm, versions)
        if (rec) Wave(true, Modifier.padding(top = 20.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        if (!rec && !busy) {
            Sm("Say English or your own language for words you do not know.", Modifier.padding(bottom = 6.dp))
            if (denied) Sm("The microphone is off for Fluvour. Allow it in your phone settings to record.", Modifier.padding(bottom = 6.dp))
            else if (!micOk) Sm("Fluvour uses the microphone only while you record. The recording is sent to Gemini through your own key.", Modifier.padding(bottom = 6.dp))
            notice?.let { Sm(it, Modifier.padding(bottom = 6.dp)) }
            FButton("Record", askMic, Modifier.fillMaxWidth().padding(top = 6.dp), icon = Icons.Outlined.Mic)
        }
        if (rec) FButton("Stop", { vm.stopRecording() }, Modifier.fillMaxWidth(), outlined = true)
    }
}

/** The sentence of [text] that holds [word], so the word sheet explains it in context. */
internal fun sentenceOf(text: String, word: String): String =
    text.split(Regex("(?<=[。！？.!?\\n])")).map { it.trim() }.firstOrNull { it.contains(word) }?.takeIf { it.isNotBlank() } ?: text

/**
 * The result of one recording. Your own words and the AI's version look different (plain card against tinted card with an "AI" mark).
 * Words in both texts can be tapped for a meaning; the AI's sound has play, pause, seek and speed and fills the text as it plays
 * (the fill is an estimate spread over the sound). Words worth learning are suggested and only saved when you tap Save.
 */
@Composable
private fun TopicResult(v: SpeakingVersionEntity, vm: SpeakingViewModel, fromList: Boolean, onAgain: () -> Unit, onClose: () -> Unit) {
    val c = Fluvour.colors
    val lang = vm.topic.collectAsStateWithLifecycle().value?.languageCode ?: "en"
    val lookup: WordLookupViewModel = hiltViewModel()
    var confirmDelete by rememberSaveable(v.id) { mutableStateOf(false) }
    val marked = remember(v.substituted) { SpeakingParse.substitutedFrom(v.substituted) }
    val words = remember(v.newWords) { SpeakingParse.newWordsFrom(v.newWords) }
    val speed by vm.speed.collectAsStateWithLifecycle()
    val loadingKey by vm.loadingKey.collectAsStateWithLifecycle()
    val meState by rememberPlayState { vm.playState("me") }
    val aiState by rememberPlayState { vm.playState("improved") }
    val improved = v.improved.orEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageBar(onClose, "Your result", closeIcon = true)
        Sm("Version ${v.versionNo} · you spoke for ${mmss(v.durationSec)}", Modifier.padding(top = 14.dp, bottom = 14.dp))

        AuthorPanel(ai = false, title = "You said") {
            TapText(v.transcript, lang, size = 21.sp, lit = meState.fraction, marked = marked, onWord = { w, l, r -> lookup.select(w, l, r, sentenceOf(v.transcript, w), lang) })
            if (marked.isNotEmpty()) Sm("Words in accent colour were not in the language you are learning.", Modifier.padding(top = 8.dp), size = 12.sp)
            if (v.audioPath != null) AudioBar(meState, speed, onPlay = { vm.playMine(v) }, onPause = { vm.pauseSound() }, onResume = { vm.resumeSound() },
                onSeek = { vm.seekSound(it) }, onSpeed = { vm.setSpeed(it) }, modifier = Modifier.padding(top = 10.dp))
        }

        AuthorPanel(ai = true, title = "Improved version", modifier = Modifier.padding(top = 14.dp)) {
            TapText(improved, lang, size = 21.sp, lit = aiState.fraction, compareTo = v.transcript, onWord = { w, l, r -> lookup.select(w, l, r, sentenceOf(improved, w), lang) })
            Sm("Highlighted words are what the AI changed or added.", Modifier.padding(top = 8.dp), size = 12.sp)
            AudioBar(aiState, speed, onPlay = { vm.playAi("improved", improved) }, onPause = { vm.pauseSound() }, onResume = { vm.resumeSound() },
                onSeek = { vm.seekSound(it) }, onSpeed = { vm.setSpeed(it) }, loading = loadingKey == "improved" && !aiState.active, modifier = Modifier.padding(top = 10.dp))
        }

        AuthorPanel(ai = true, title = "Words worth learning", modifier = Modifier.padding(top = 14.dp)) {
            if (words.isEmpty()) Sm("Nothing new this time.", size = 13.sp)
            words.forEachIndexed { i, w ->
                if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        TargetText(w.word, size = 19.sp)
                        Sm(w.meaning, size = 13.sp)
                        w.example?.let { TargetText(it, Modifier.padding(top = 2.dp), size = 14.sp, color = c.mute) }
                    }
                    if (w.cardId != null) SmallButton("Saved", {}, soft = true) else SmallButton("Save", { vm.saveWord(v.id, w.word) })
                }
            }
        }

        if (v.audioPath != null) {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                if (!confirmDelete) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Sm("The recording is saved on this phone.", Modifier.weight(1f), size = 12.5.sp)
                        SmallButton("Delete recording", { confirmDelete = true }, soft = true)
                    }
                } else {
                    Sm("Delete the recording file? The text and saved cards stay.", size = 12.5.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton("Delete", { vm.stopSound(); vm.deleteAudio(v.id); confirmDelete = false })
                        SmallButton("Keep it", { confirmDelete = false }, soft = true)
                    }
                }
            }
        } else Sm("The recording file was deleted. The text is kept.", Modifier.padding(top = 18.dp), size = 12.5.sp)
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton("Record again", { vm.stopSound(); onAgain() }, Modifier.weight(1f), outlined = true)
            FButton(if (fromList) "Back" else "Done", { vm.stopSound(); onClose() }, Modifier.weight(1f))
        }
    }
    OutputWordSheet(lookup)
}

/**
 * Versions of this topic: a small table of countable measures once two versions are finished (no score, only the numbers and
 * the change from the first to the latest), then the list, newest first. Tap a finished version to open its result. A version
 * that is not finished says so, and a tap tries it again.
 */
@Composable
private fun VersionHistory(vm: SpeakingViewModel, versions: List<SpeakingVersionEntity>) {
    if (versions.isEmpty()) return
    val c = Fluvour.colors
    val measures by vm.measures.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        HorizontalDivider(color = c.line)
        Eyebrow("Your versions", Modifier.padding(top = 14.dp))
        var compare by rememberSaveable { mutableStateOf(false) }
        if (measures.size >= 2) {
            LinkText(if (compare) "Hide comparison" else "Compare versions", { compare = !compare }, Modifier.padding(top = 8.dp))
            if (compare) MeasuresTable(measures.takeLast(5).map { it.first.versionNo to it.second }, SpeakingMeasures.change(measures.first().second, measures.last().second), measures.first().first.versionNo, measures.last().first.versionNo)
        }
        versions.asReversed().forEach { v ->
            val done = v.improved != null
            Row(Modifier.fillMaxWidth().clickable { if (done) vm.show(v.id) else vm.retry(v.id) }.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("v${v.versionNo}", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Sm("${shortDate(v.createdAt)} · ${mmss(v.durationSec)}" + if (done) "" else " · not processed, tap to try again", size = 12.5.sp)
                }
                if (done) Sm("Open", size = 12.5.sp)
            }
        }
    }
}

@Composable
private fun MeasuresTable(rows: List<Pair<Int, Measures>>, change: Measures, firstNo: Int, lastNo: Int) {
    val c = Fluvour.colors
    @Composable fun RowScope.cell(t: String, w: Float, head: Boolean = false, strong: Boolean = false) =
        Text(t, Modifier.weight(w), color = if (head) c.mute else c.ink, fontSize = if (head) 12.sp else 14.sp,
            fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal, textAlign = if (w == 1.2f) TextAlign.Start else TextAlign.End)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth()) { cell("", 1.2f, true); cell("Time", 1f, true); cell("Words", 1f, true); cell("Swaps", 1f, true); cell("New", 1f, true) }
        rows.forEach { (no, m) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                cell("v$no", 1.2f); cell(mmss(m.seconds), 1f); cell(m.distinctWords.toString(), 1f); cell(m.substituted.toString(), 1f); cell(m.newWords.toString(), 1f)
            }
        }
        HorizontalDivider(color = c.line, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            cell("v$firstNo to v$lastNo", 1.2f, true, true)
            cell((if (change.seconds < 0) "-" else "+") + mmss(kotlin.math.abs(change.seconds)), 1f, strong = true)
            cell(SpeakingMeasures.signed(change.distinctWords), 1f, strong = true)
            cell(SpeakingMeasures.signed(change.substituted), 1f, strong = true)
            cell(SpeakingMeasures.signed(change.newWords), 1f, strong = true)
        }
        Sm("Words: different words you used. Swaps: words in another language. New: new words to learn. Rough counts, so look at the trend over a few versions.", Modifier.padding(top = 8.dp), size = 12.sp)
    }
}
