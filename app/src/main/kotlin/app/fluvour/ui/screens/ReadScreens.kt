package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.reading.DictionaryClient
import app.fluvour.reading.Levels
import app.fluvour.reading.Part
import app.fluvour.ui.art.artPattern
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.reading.Token
import app.fluvour.reading.WordSplitter
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import app.fluvour.data.ArticleEntity
import app.fluvour.data.HighlightEntity
import app.fluvour.data.ReadMode
import app.fluvour.data.ReadPrefs
import app.fluvour.data.ReadTheme
import app.fluvour.ui.theme.LocalFluvour
import app.fluvour.ui.theme.ResolvedTheme
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

private val readArt = listOf("leaf", "sunhills", "moon", "terraces")   // saved articles pick one by id, as the design's list does

/** Read tab: search or random Wikipedia articles in the target language, and the articles already saved. */
@Composable
fun ReadScreen(onOpen: (String) -> Unit, onOpenBook: (String) -> Unit, vm: ReadViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val sentences by vm.sentences.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val importUi by vm.importUi.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<app.fluvour.data.ReadItem?>(null) }
    var importing by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
        item {
            Column(Modifier.artPattern(2, bleed = 22.dp).ambient("r", "eo", bleed = 22.dp).languageMark("lmr", "lmo", bleed = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) { Eyebrow("Articles"); H1("Read", Modifier.padding(top = 8.dp)) }
                IconBtn(Icons.Outlined.Add, "Add to Read", { importing = true })
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, "Search a topic", Modifier.weight(1f), onSearch = { if (query.isNotBlank()) vm.find(query) })
                LinkText("Random", { query = ""; vm.find("") })
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = c.accent, trackColor = c.line)
            ui.message?.let { Sm(it, Modifier.padding(top = 10.dp)) }
            }
        }
        if (ui.hits.isNotEmpty()) {
            item { SectionEyebrow("Results", top = 24.dp) }
            items(ui.hits, key = { "hit-${it.pageId}" }) { hit ->
                FRow(hit.title, subtitle = hit.preview.takeIf { it.isNotBlank() }, titleFont = targetFont(), onClick = { if (ui.opening == null) vm.open(hit, onOpen) },
                    trailing = { if (ui.opening == hit.pageId) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp) else ChevronGo() })
            }
        }
        item { SectionEyebrow("Saved", top = 28.dp) }
        if (saved.isEmpty()) item { Sm("Nothing saved yet. Open an article above, or tap + to add a book, a file or pasted text. It is kept here for offline reading.", Modifier.padding(top = 8.dp)) }
        items(saved, key = { it.id }) { a ->
            val isBook = a.chapters > 1
            val sub = if (isBook) listOfNotNull("${a.chapters} chapters", app.fluvour.reading.ImportRepository.author(a.meta)).joinToString(" \u00B7 ") else a.preview.replace('\n', ' ')
            FRow(a.bookTitle ?: a.title, subtitle = sub, titleFont = targetFont(), onClick = { if (isBook) onOpenBook(a.sourceId) else onOpen(a.id) },
                leading = { CoverArt(readArt[Math.floorMod(a.id.hashCode(), readArt.size)], Modifier.size(44.dp, 56.dp), 6.dp, small = true) },
                trailing = { IconBtn(Icons.Outlined.Delete, "Remove", { toDelete = a }, tint = c.mute) })
        }
        if (sentences.isNotEmpty()) {
            item { SectionEyebrow("Mined sentences (${sentences.size})", top = 28.dp) }
            items(sentences, key = { "sent-${it.sentence.id}" }) { row ->
                FRow(row.sentence.text, subtitle = row.sourceTitle, titleFont = targetFont(), titleSize = 16.sp,
                    trailing = { IconBtn(Icons.Outlined.Delete, "Remove sentence", { vm.deleteSentence(row.sentence.id) }, tint = c.mute) })
            }
        }
    }
    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = c.card,
            title = { Text(if (a.chapters > 1) "Remove this book?" else "Remove this?") },
            text = { Text("${a.bookTitle ?: a.title} will be removed from this phone. Cards and mined sentences you made from it stay.") },
            confirmButton = { TextButton({ vm.delete(a); toDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Cancel") } }
        )
    }
    if (importing) ImportSheet(
        busy = importUi.busy, message = importUi.message,
        onPaste = { text -> vm.importText(text) { r -> if (importing) { importing = false; if (r.chapters > 1) onOpenBook(r.sourceId) else onOpen(r.firstArticleId) } } },
        onFile = { uri -> vm.importFile(uri) { r -> if (importing) { importing = false; if (r.chapters > 1) onOpenBook(r.sourceId) else onOpen(r.firstArticleId) } } },
        onClose = { importing = false; vm.clearImportMessage() }
    )
}

/** Paper and ink for the reader's own colour choices. `App` keeps the app theme. */
private fun readerTheme(base: ResolvedTheme, t: ReadTheme): ResolvedTheme = when (t) {
    ReadTheme.App -> base
    ReadTheme.Sepia -> base.copy(dark = false, colors = base.colors.copy(
        paper = Color(0xFFF4ECD8), card = Color(0xFFEADFC5), ink = Color(0xFF4B3B2A), mute = Color(0xFF8C7A62), line = Color(0xFFD8C9A8),
        primary = Color(0xFF4B3B2A), onPrimary = Color(0xFFF4ECD8)))
    ReadTheme.Night -> base.copy(dark = true, colors = base.colors.copy(
        paper = Color(0xFF15171A), card = Color(0xFF212429), ink = Color(0xFFD8D5CE), mute = Color(0xFF8E9298), line = Color(0xFF353A40),
        primary = Color(0xFFE8E4DA), onPrimary = Color(0xFF15171A)))
}

/**
 * One text. Tap a word to open the lookup sheet; "Add card" opens the card editor with the word and its sentence filled in.
 * Reads in one long scroll or in swipeable pages, remembers where you stopped, and has bookmarks and coloured highlights.
 */
@Composable
fun ReaderScreen(onBack: () -> Unit, onOpenChapter: (String) -> Unit, onAddCard: (word: String, sentence: String, meaning: String, sentenceId: String?) -> Unit, vm: ReaderViewModel = hiltViewModel()) {
    val article by vm.article.collectAsStateWithLifecycle()
    val prefs by vm.readPrefs.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    val a = article
    val p = prefs
    if (a == null || p == null || !resume.loaded) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageBar(onBack, null)
            if (a == null) Sm("Article not found.", Modifier.padding(22.dp))
        }
        return
    }
    val base = LocalFluvour.current
    // The reader's own paper and ink apply to the whole screen, including its sheets.
    CompositionLocalProvider(LocalFluvour provides remember(base, p.theme) { readerTheme(base, p.theme) }) {
        ReaderBody(a, p, resume, onBack, onOpenChapter, onAddCard, vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ReaderBody(
    a: ArticleEntity, p: ReadPrefs, resume: ReaderViewModel.Resume, onBack: () -> Unit, onOpenChapter: (String) -> Unit,
    onAddCard: (word: String, sentence: String, meaning: String, sentenceId: String?) -> Unit, vm: ReaderViewModel
) {
    val c = Fluvour.colors
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val voiceHint by vm.voiceHint.collectAsStateWithLifecycle()
    val meanings by vm.meanings.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val myDicts by vm.imported.collectAsStateWithLifecycle()
    val sentenceSaved by vm.sentenceSaved.collectAsStateWithLifecycle()
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val chapterNav by vm.chapterNav.collectAsStateWithLifecycle()
    val splitText by vm.splitText.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val imported = source?.kind == app.fluvour.reading.ImportRepository.SOURCE_KIND
    val context = LocalContext.current

    val texts = remember(a.id) { a.body.split(Regex("\\n+")).filter { it.isNotBlank() } }
    val starts = remember(texts) { IntArray(texts.size).also { var s = 0; texts.forEachIndexed { i, t -> it[i] = s; s += t.length } } }
    val total = remember(texts) { texts.sumOf { it.length }.coerceAtLeast(1) }
    val tokens = remember(splitText, texts) { texts.indices.map { i -> splitText.getOrNull(i)?.second.orEmpty() } }
    val hlByPara = remember(highlights) { highlights.groupBy { it.para } }

    var showOptions by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var aid by remember { mutableStateOf(false) }   // TODO(reading-aid): show readings above words once it is wired to the lookup data
    var sel by remember { mutableStateOf<Sel?>(null) }
    var noteFor by remember { mutableStateOf<HighlightEntity?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var jump by remember { mutableStateOf<Anchor?>(null) }
    var range by remember { mutableStateOf(0L to 0L) }   // positions on screen now: [first, second)

    // Saved position: starts where the reader left off, then follows the scroll or the page, saved shortly after it settles.
    var anchor by remember(a.id) {
        val para = resume.para.coerceIn(0, (texts.size - 1).coerceAtLeast(0))
        mutableStateOf(Anchor(para, resume.ch, if (texts.isEmpty()) 0f else (starts[para] + resume.ch) / total.toFloat()))
    }
    LaunchedEffect(a.id) { snapshotFlow { anchor }.drop(1).debounce(500).collect { vm.saveProgress(it.para, it.ch, it.percent) } }
    val latest by rememberUpdatedState(anchor)
    DisposableEffect(a.id) { onDispose { if (texts.isNotEmpty()) vm.saveProgress(latest.para, latest.ch, latest.percent) } }

    val fontSize = 17 + p.size * 2
    val font = targetFont()
    val style = remember(font, p.size, p.spacing, c.ink) {
        TextStyle(color = c.ink, fontFamily = font, fontSize = fontSize.sp, lineHeight = (fontSize * listOf(1.6f, 1.9f, 2.2f)[p.spacing]).sp)
    }
    val x = ReaderCtx(
        texts, tokens, style, hlByPara, sel, lookup?.word, lookup?.spot?.paragraph,
        onTap = { para, word, sentence, lemma, reading, s, e -> if (sel != null) sel = null else vm.select(word, sentence, lemma, reading, SentenceSpot(para, s, e)) },
        onBlankTap = { sel = null },
        onSelect = { sel = it }
    )
    val selected = sel
    val selectedText = selected?.let { texts[it.para].substring(it.start, it.end) }
    val existing = selected?.let { s -> hlByPara[s.para]?.firstOrNull { it.startCh == s.start && it.endCh == s.end } }
    val here = bookmarks.filter { val k = posKey(it.para, it.ch); k >= range.first && k < range.second }
    val title = chapterNav?.let { n -> "Chapter ${n.index + 1} of ${n.total}" } ?: if (imported) "Your file" else "Wikipedia"

    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageBar(onBack, title, trailing = {
                IconBtn(if (here.isNotEmpty()) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark this place", {
                    if (here.isNotEmpty()) vm.deleteBookmarks(here.map { it.id })
                    else if (texts.isNotEmpty()) {
                        val para = (range.first / 1_000_000L).toInt().coerceIn(0, texts.lastIndex); val ch = (range.first % 1_000_000L).toInt()
                        vm.addBookmark(para, ch, texts[para].drop(ch).trim().take(70), anchor.percent)
                    }
                }, tint = if (here.isNotEmpty()) c.accent else c.ink)
                IconBtn(Icons.Outlined.Bookmarks, "Bookmarks and highlights", { showNotes = true })
                IconBtn(Icons.Outlined.TextFields, "Reading options", { showOptions = true })
            })
            if (texts.isEmpty()) Sm("This text is empty.", Modifier.padding(22.dp))
            else if (p.mode == ReadMode.Pages) {
                PagesBody(x, anchor, starts, total, a.title, chapterNav, onOpenChapter, jump, { jump = null }, { anchor = it }, { lo, hi -> range = lo to hi })
            } else {
                ScrollBody(x, anchor, starts, total, jump, { jump = null }, { anchor = it }, { lo, hi -> range = lo to hi },
                    header = {
                        Column {
                            H2(a.title, Modifier.padding(top = 16.dp), size = 26.sp, font = targetFont())
                            Sm(if (imported) listOfNotNull(source?.title.takeIf { chapterNav != null }, app.fluvour.reading.ImportRepository.author(source?.meta)).joinToString(" · ").ifEmpty { "Your file" }
                                else "Wikipedia · ${a.license}", Modifier.padding(top = 6.dp))
                            coverage?.takeIf { it.total > 0 }?.let { cv ->
                                Text(
                                    if (cv.known == 0) "No known words yet. Cards you have learned count here." else "You know ${cv.percent}% of the words here (${cv.known} of ${cv.total})",
                                    color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    },
                    footer = {
                        Column {
                            chapterNav?.let { n ->
                                Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FButton("Previous", { n.prevId?.let(onOpenChapter) }, Modifier.weight(1f), outlined = true, enabled = n.prevId != null)
                                    FButton("Next chapter", { n.nextId?.let(onOpenChapter) }, Modifier.weight(1f), enabled = n.nextId != null)
                                }
                            }
                            if (a.url.startsWith("http")) Text("Source: ${a.url}", color = c.mute, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
                        }
                    })
            }
        }
        if (selected != null && selectedText != null) {
            SelectionBar(
                selectedText, existing,
                onColor = { col -> vm.highlight(selected.para, selected.start, selected.end, selectedText, col.toArgb()) },
                onLookup = {
                    val (ss, se) = WordSplitter.sentenceSpan(texts[selected.para], selected.start)
                    vm.select(selectedText, texts[selected.para].substring(ss, se), null, null, SentenceSpot(selected.para, ss, se)); sel = null
                },
                onCopy = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Fluvour", selectedText)); sel = null
                },
                onNote = { existing?.let { noteDraft = it.note.orEmpty(); noteFor = it } },
                onRemove = { existing?.let { vm.deleteHighlight(it.id); sel = null } },
                onClose = { sel = null },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            )
        }
    }

    if (showOptions) ReaderOptionsSheet(p, { n -> vm.setReadPrefs { n } }, aid, { aid = it }, "Reading aid") { showOptions = false }
    if (showNotes) NotesSheet(bookmarks, highlights, texts,
        onJump = { para, ch -> jump = Anchor(para, ch, 0f); showNotes = false },
        onDeleteBookmark = { vm.deleteBookmarks(listOf(it)) }, onDeleteHighlight = vm::deleteHighlight, onClose = { showNotes = false })
    noteFor?.let { h ->
        AlertDialog(
            onDismissRequest = { noteFor = null }, containerColor = c.paper,
            title = { Text("Note", color = c.ink) },
            text = {
                OutlinedTextField(noteDraft, { noteDraft = it }, Modifier.fillMaxWidth(), placeholder = { Text("Write a note for this highlight") }, minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent))
            },
            confirmButton = { TextButton({ vm.setNote(h.id, noteDraft); noteFor = null }) { Text("Save", color = c.accent) } },
            dismissButton = { TextButton({ noteFor = null }) { Text("Cancel", color = c.mute) } }
        )
    }
    lookup?.let { l ->
        ModalBottomSheet(onDismissRequest = vm::clear, containerColor = c.paper, scrimColor = c.scrim) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TargetText(l.word, Modifier.weight(1f), size = 34.sp)
                    IconButton({ vm.speak(l.word, a.languageCode) }) { Icon(Icons.Outlined.VolumeUp, "Speak the word", tint = c.ink) }
                }
                Row(verticalAlignment = Alignment.Top) {
                    TargetText(l.sentence, Modifier.weight(1f), size = 18.sp, lineHeight = 28.sp)
                    IconButton({ vm.speak(l.sentence, a.languageCode) }) { Icon(Icons.Outlined.VolumeUp, "Speak the sentence", tint = c.mute) }
                }
                val form = listOfNotNull(l.reading?.takeIf { l.word.any { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN } },
                    l.lemma?.let { "→ $it" }).joinToString("   ")
                if (form.isNotBlank()) Text(form, color = c.mute, fontSize = 14.sp)
                (Levels.chips(a.languageCode, level) + myDicts?.chips.orEmpty()).takeIf { it.isNotEmpty() }?.let { chips ->
                    // Level, frequency rank, then frequency / pitch / IPA from imported dictionaries. Scrolls sideways when long.
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips.forEach { Tag(it, font = if (it.startsWith("/")) targetFont() else null) } }
                }
                if (voiceHint) Text("No voice for this language yet. Install one under Android's Text-to-speech settings.", color = c.mute, fontSize = 12.sp)
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    myDicts?.entries?.takeIf { it.isNotEmpty() }?.let { ImportedBlock(it) }
                    MeaningBlock("Dictionary", meanings.dictionary, DictionaryClient.SOURCE)
                    MeaningBlock("In this sentence (AI)", meanings.explanation, null)
                }
                FButton("Add card", {
                    val w = l.lemma ?: l.word; val s = l.sentence
                    // Pre-fill the meaning with the AI's short answer when there is one; the dictionary list is too long for a card.
                    val m = meanings.explanation?.text ?: myDicts?.entries?.firstOrNull()?.glossary?.lineSequence()?.take(3)?.joinToString("; ").orEmpty()
                    // Mining first means the card is linked to the saved sentence, its source and its position.
                    vm.mineForCard { sentenceId -> vm.clear(); onAddCard(w, s, m, sentenceId) }
                }, Modifier.fillMaxWidth())
                if (sentenceSaved) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, null, tint = c.mute, modifier = Modifier.size(18.dp))
                        Text("  Sentence saved", color = c.mute, fontSize = 14.sp)
                    }
                } else {
                    FButton("Save sentence", vm::saveSentence, Modifier.fillMaxWidth(), outlined = true)
                }
            }
        }
    }
}

/** One labelled meaning section: a spinner while loading, then the text (with its source) or a short message. */
@Composable
internal fun MeaningBlock(title: String, part: Part?, source: String?) {
    val c = Fluvour.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(title)
        when {
            part == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            part.text != null -> {
                Text(part.text, color = c.ink, fontSize = 15.sp)
                if (source != null) Text(source, color = c.mute, fontSize = 11.sp)
            }
            else -> Text(part.message ?: "", color = c.mute, fontSize = 13.sp)
        }
    }
}

/** Entries from the user's imported dictionaries, grouped by dictionary. Long entries are cut; the full text stays in the dictionary. */
@Composable
internal fun ImportedBlock(entries: List<app.fluvour.reading.ImportedEntry>) {
    val c = Fluvour.colors
    entries.groupBy { it.dictionary }.forEach { (dict, rows) ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(dict)
            rows.take(6).forEach { e ->
                val head = listOfNotNull(e.term, e.reading.takeIf { it.isNotBlank() && it != e.term }?.let { "【$it】" }, e.tags.takeIf { it.isNotBlank() }).joinToString("  ")
                Text(head, color = c.mute, fontSize = 12.sp)
                Text(e.glossary.let { if (it.length > 600) it.take(600).trimEnd() + "…" else it }, color = c.ink, fontSize = 15.sp)
            }
        }
    }
}

/** A book's contents page: chapters in order; tap one to read it. */
@Composable
fun BookScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: BookViewModel = hiltViewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageBar(onBack, "Book")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
            item {
                H2(source?.title.orEmpty(), Modifier.padding(top = 16.dp), size = 26.sp, font = targetFont())
                Sm(listOfNotNull(app.fluvour.reading.ImportRepository.author(source?.meta), "${chapters.size} chapters").joinToString(" \u00B7 "), Modifier.padding(top = 6.dp, bottom = 8.dp))
            }
            itemsIndexed(chapters, key = { _, ch -> ch.id }) { i, ch ->
                FRow(ch.title, subtitle = "Chapter ${i + 1}", titleFont = targetFont(), titleSize = 16.sp, onClick = { onOpen(ch.id) })
            }
        }
    }
}
