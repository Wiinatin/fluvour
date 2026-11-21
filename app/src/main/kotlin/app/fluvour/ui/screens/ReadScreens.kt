package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import app.fluvour.reading.CatalogBook
import app.fluvour.reading.LibraryMath
import app.fluvour.data.LibraryBookEntity
import androidx.compose.foundation.border
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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

private enum class ReadTab(val label: String) { Books("Books"), Articles("Articles"), Mined("Mined") }
private enum class BookSource(val label: String) { All("All"), Gutenberg("Gutenberg"), Files("My files") }

/** A shelf row turned back into a catalog book, so it can be downloaded. Null for rows without an EPUB link. */
internal fun LibraryBookEntity.toCatalog(): CatalogBook? {
    val key = id.substringAfter(':', "").toLongOrNull() ?: return null
    val url = epubUrl ?: return null
    return CatalogBook(key, title, author, lang, coverUrl, url, tags?.split('|')?.filter { it.isNotBlank() } ?: emptyList(), about)
}

/**
 * Read tab. Books: Continue reading, Shelf, own files and the Gutenberg catalog (search, hearts, offline download).
 * Articles: Wikipedia search and saved articles. Mined: sentences mined so far.
 * `onOpenDetail` opens the book detail page for a catalog book (Root passes it).
 * Removing: long-press a cover (Continue reading, Shelf) for "Remove from shelf" / "Remove download"; swipe an own file or a
 * downloaded Gutenberg row left to remove the download (always with a confirm).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReadScreen(onAddCard: (word: String, sentence: String, meaning: String, sentenceId: String?) -> Unit = { _, _, _, _ -> }, onOpen: (String) -> Unit, onOpenBook: (String) -> Unit, onOpenDetail: ((String) -> Unit)? = null, vm: ReadViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val sentences by vm.sentences.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val shelfIds by vm.shelfIds.collectAsStateWithLifecycle()
    val continueItems by vm.continueItems.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val bookMessage by vm.bookMessage.collectAsStateWithLifecycle()
    val aList by vm.list.collectAsStateWithLifecycle()
    var nearOnly by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(ReadTab.Books) }
    var source by rememberSaveable { mutableStateOf(BookSource.All) }
    var openMined by rememberSaveable { mutableStateOf<String?>(null) }   // source id whose mined sentences are showing ("" = no source)
    androidx.activity.compose.BackHandler(enabled = tab == ReadTab.Mined && openMined != null) { openMined = null }
    var query by remember { mutableStateOf("") }
    val importUi by vm.importUi.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<app.fluvour.data.ReadItem?>(null) }
    var bookMenu by remember { mutableStateOf<LibraryBookEntity?>(null) }
    var removeBook by remember { mutableStateOf<LibraryBookEntity?>(null) }
    var importing by remember { mutableStateOf(false) }
    val lang = Fluvour.lang
    LaunchedEffect(lang) { vm.loadCatalog("") }
    LaunchedEffect(lang, tab) { if (tab == ReadTab.Articles) vm.loadArticles() }
    // Online articles near the declared level (Watch tab). Rows without a measured level stay in: they cannot be judged yet.
    val levelScale = remember(lang) { app.fluvour.reading.ClipLevels.scale(lang) }
    val declaredIdx = aList.declared?.let { levelScale.codes.indexOf(it) }?.takeIf { it >= 0 }
    val shownRows = remember(aList.rows, nearOnly, declaredIdx) {
        fun idx(r: ArticleRow) = r.levelCode?.let { levelScale.codes.indexOf(it) }?.takeIf { it >= 0 }
        if (declaredIdx == null) aList.rows
        else {
            val base = if (nearOnly) aList.rows.filter { r -> idx(r)?.let { kotlin.math.abs(it - declaredIdx) <= 1 } ?: true } else aList.rows
            base.sortedBy { r -> idx(r)?.let { kotlin.math.abs(it - declaredIdx) } ?: 99 }
        }
    }

    val sourceOf = remember(downloaded) { downloaded.associate { it.id to it.sourceId } }
    val byId = remember(downloaded, shelf) { (downloaded + shelf).associateBy { it.id } }
    // Books the user added from a file: library rows with origin "file" (cover read from the EPUB, else generated).
    val ownFiles = remember(downloaded, query) {
        downloaded.filter { it.origin == "file" && (query.isBlank() || it.title.contains(query.trim(), true) || it.author.contains(query.trim(), true)) }
    }
    val articles = remember(saved) { saved.filter { it.chapters <= 1 } }

    fun openCatalog(b: CatalogBook) {
        val id = LibraryMath.catalogId("gutenberg", b.id)
        val sid = sourceOf[id]
        when {
            onOpenDetail != null -> vm.openDetail(b, onOpenDetail)   // the detail page has Read, Continue and Remove
            sid != null -> onOpenBook(sid)
            else -> vm.download(b) { onOpenBook(it.sourceId) }
        }
    }
    fun openEntity(e: LibraryBookEntity) {
        if (e.origin == "file" && onOpenDetail != null) onOpenDetail(e.id)
        else e.sourceId?.let { onOpenBook(it) } ?: e.toCatalog()?.let { openCatalog(it) }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
        item {
            Column(Modifier.artPattern(2, bleed = 22.dp).ambient("r", "eo", bleed = 22.dp).languageMark("lmr", "lmo", bleed = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) { Eyebrow(tab.label); H1("Read", Modifier.padding(top = 8.dp)) }
                IconBtn(Icons.Outlined.Add, "Add to Read", { importing = true })
            }
            UnderlineTabs(ReadTab.values().map { it.label }, tab.ordinal, { tab = ReadTab.values()[it] }, Modifier.padding(top = 14.dp))
            when (tab) {
                ReadTab.Books -> {
                    SearchField(query, { query = it }, "Search books", Modifier.padding(top = 10.dp), onSearch = { if (source != BookSource.Files) vm.loadCatalog(query, force = true) })
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BookSource.values().forEach { s -> SourceChip(s.label, source == s) { source = s } }
                    }
                }
                ReadTab.Articles -> {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchField(query, { query = it }, "Search a topic", Modifier.weight(1f), onSearch = { if (query.isNotBlank()) vm.find(query) })
                        LinkText("Random", { query = ""; vm.find("") })
                    }
                    if (declaredIdx != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceChip("Near my level", nearOnly) { nearOnly = true }
                        SourceChip("All levels", !nearOnly) { nearOnly = false }
                    }
                }
                ReadTab.Mined -> {}
            }
            if (ui.loading || catalog.loading || (tab == ReadTab.Articles && (aList.loading || aList.checking))) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = c.accent, trackColor = c.line)
            if (tab == ReadTab.Articles) ui.message?.let { Sm(it, Modifier.padding(top = 10.dp)) }
            if (tab == ReadTab.Articles) aList.message?.let { Sm(it, Modifier.padding(top = 10.dp)) }
            if (tab == ReadTab.Books) bookMessage?.let { Sm(it, Modifier.padding(top = 10.dp)) }
            }
        }
        when (tab) {
            ReadTab.Books -> {
                val showGutenberg = source != BookSource.Files
                val showMine = source != BookSource.Gutenberg
                if (showMine && query.isBlank() && continueItems.isNotEmpty()) {
                    item { SectionEyebrow("Continue reading", top = 24.dp) }
                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            continueItems.forEach { ci ->
                                val e = byId[ci.bookId] ?: return@forEach
                                Column(Modifier.width(104.dp).combinedClickable(onClick = { onOpen(ci.articleId) }, onLongClick = { bookMenu = e })) {
                                    BookCover(e.title, e.author, e.coverUrl, e.coverPath, Modifier.size(104.dp, 148.dp))
                                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
                                        Box(Modifier.fillMaxWidth(ci.overall.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                                    }
                                    Text(e.title, Modifier.padding(top = 6.dp), color = c.ink, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (showMine && query.isBlank() && shelf.isNotEmpty()) {
                    item { SectionEyebrow("Shelf", top = 28.dp) }
                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            shelf.forEach { e ->
                                Column(Modifier.width(104.dp).combinedClickable(onClick = { openEntity(e) }, onLongClick = { bookMenu = e })) {
                                    BookCover(e.title, e.author, e.coverUrl, e.coverPath, Modifier.size(104.dp, 148.dp))
                                    Text(e.title, Modifier.padding(top = 6.dp), color = c.ink, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (showMine && ownFiles.isNotEmpty()) {
                    item { SectionEyebrow("My files", top = 28.dp) }
                    items(ownFiles, key = { "own-${it.id}" }) { e ->
                        SwipeToRemove(onRemove = { removeBook = e }) {
                            FRow(e.title, subtitle = e.author.takeIf { it.isNotBlank() }, titleFont = targetFont(), onClick = { openEntity(e) },
                                leading = { BookCover(e.title, e.author, null, e.coverPath, Modifier.size(44.dp, 62.dp)) },
                                trailing = { HeartButton(e.id in shelfIds, "your shelf") { vm.toggleOwnHeart(e.id) } })
                        }
                    }
                }
                if (showGutenberg) {
                    item { SectionEyebrow(if (catalog.query.isBlank()) "Popular on Gutenberg" else "Results for \"${catalog.query}\"", top = 28.dp) }
                    if (catalog.books.isEmpty() && !catalog.loading) item {
                        Column(Modifier.padding(top = 8.dp)) {
                            Sm(catalog.message ?: "No books to show yet.")
                            if (!catalog.loaded) LinkText("Try again", { vm.loadCatalog(query, force = true) }, Modifier.padding(top = 8.dp))
                        }
                    }
                    items(catalog.books, key = { "cat-${it.id}" }) { b ->
                        val id = LibraryMath.catalogId("gutenberg", b.id)
                        val isDown = sourceOf[id] != null
                        SwipeToRemove(enabled = isDown && id !in downloading, onRemove = { byId[id]?.let { removeBook = it } }) {
                            FRow(b.title, subtitle = listOfNotNull(b.author.takeIf { it.isNotBlank() }, if (isDown) "Offline" else null).joinToString(" \u00B7 ").takeIf { it.isNotEmpty() },
                                titleFont = targetFont(), onClick = { openCatalog(b) },
                                leading = { BookCover(b.title, b.author, b.coverUrl, byId[id]?.coverPath, Modifier.size(44.dp, 62.dp)) },
                                trailing = {
                                    if (id in downloading) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                                    else HeartButton(id in shelfIds, "your shelf") { vm.toggleHeart(b) }
                                })
                        }
                    }
                    if (catalog.books.isNotEmpty() && (catalog.hasNext || catalog.loadingMore)) item {
                        Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                            if (catalog.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                            else LinkText("More books", { vm.loadMore() })
                        }
                    }
                    if (catalog.books.isNotEmpty()) catalog.message?.let { m -> item { Sm(m, Modifier.padding(top = 10.dp)) } }
                }
                if (source == BookSource.Files && ownFiles.isEmpty()) item { Sm("No files yet. Tap + to add an EPUB.", Modifier.padding(top = 20.dp)) }
            }
            ReadTab.Articles -> {
                if (ui.hits.isNotEmpty()) {
                    item { SectionEyebrow("Results", top = 24.dp) }
                    items(ui.hits, key = { "hit-${it.pageId}" }) { hit ->
                        FRow(hit.title, subtitle = hit.preview.takeIf { it.isNotBlank() }, titleFont = targetFont(), onClick = { if (ui.opening == null) vm.open(hit, onOpen) },
                            trailing = { if (ui.opening == hit.pageId) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp) else ChevronGo() })
                    }
                }
                item { SectionEyebrow("Articles online", top = 24.dp) }
                if (declaredIdx == null) item { Sm("Set your level on the Watch tab to see articles near it.", Modifier.padding(top = 8.dp)) }
                if (aList.failed.isNotEmpty() && !aList.loading) item { Sm("Skipped: ${aList.failed.joinToString(", ")}.", Modifier.padding(top = 8.dp)) }
                if (shownRows.isEmpty() && !aList.loading) item {
                    Column(Modifier.padding(top = 8.dp)) {
                        Sm(if (aList.rows.isNotEmpty()) "Nothing near your level yet. Try All levels, or check levels." else "No articles to show.")
                        LinkText("Try again", { vm.loadArticles(force = true) }, Modifier.padding(top = 8.dp))
                    }
                }
                items(shownRows, key = { "art-${it.item.url}" }) { r ->
                    FRow(r.item.title, subtitle = listOfNotNull(r.item.sourceName, if (r.savedId != null) "Saved" else null).joinToString(" · "),
                        titleFont = targetFont(), onClick = { if (aList.opening == null) vm.openArticle(r, onOpen) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                r.label?.let { Text(it, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                                if (aList.opening == r.item.url) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp) else ChevronGo()
                            }
                        })
                }
                if (aList.rows.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        LinkText("Check levels", { vm.checkLevels() })
                        LinkText("Refresh", { vm.loadArticles(force = true) })
                    }
                }
                item { SectionEyebrow("Saved", top = 28.dp) }
                if (articles.isEmpty()) item { Sm("Nothing saved yet. Open an article above, or tap + to add a file or pasted text. It is kept here for offline reading.", Modifier.padding(top = 8.dp)) }
                items(articles, key = { it.id }) { a ->
                    FRow(a.bookTitle ?: a.title, subtitle = a.preview.replace('\n', ' '), titleFont = targetFont(), onClick = { onOpen(a.id) },
                        leading = { CoverArt(readArt[Math.floorMod(a.id.hashCode(), readArt.size)], Modifier.size(44.dp, 56.dp), 6.dp, small = true) },
                        trailing = { IconBtn(Icons.Outlined.Delete, "Remove", { toDelete = a }, tint = c.mute) })
                }
            }
            ReadTab.Mined -> {
                // One row per source (a video, a book, an article...), newest mined first. Tap a row to see its sentences.
                val groups = sentences.groupBy { it.sentence.sourceId ?: "" }.values.toList()
                val open = groups.firstOrNull { openMined != null && (it.first().sentence.sourceId ?: "") == openMined }   // gone once its last sentence is removed
                if (open == null) {
                    item { SectionEyebrow("Mined sentences (${sentences.size})", top = 24.dp) }
                    if (sentences.isEmpty()) item { Sm("No sentences yet. Tap a word while reading, then save its sentence.", Modifier.padding(top = 8.dp)) }
                    items(groups, key = { "src-${it.first().sentence.sourceId ?: ""}" }) { g ->
                        val first = g.first()
                        val title = first.sourceTitle ?: "Other sentences"
                        val book = first.sentence.sourceId?.let { sid -> downloaded.firstOrNull { it.sourceId == sid } }
                        val thumb = if (first.sourceKind == "video") first.sourceUrl?.let { app.fluvour.reading.ClipRef.videoId(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" } else null
                        FRow(title, subtitle = if (g.size == 1) "1 sentence" else "${g.size} sentences", titleSize = 15.sp,
                            onClick = { openMined = first.sentence.sourceId ?: "" },
                            leading = {
                                // Same slot size as the Watch list thumbnail, so video, book and article rows line up.
                                Box(Modifier.width(112.dp).height(63.dp), contentAlignment = Alignment.Center) {
                                    when {
                                        thumb != null -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(c.card)) {
                                            coil.compose.AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                        }
                                        book != null -> BookCover(book.title, book.author, book.coverUrl, book.coverPath, Modifier.size(45.dp, 63.dp), 6.dp)
                                        else -> GeneratedCover(title, "", Modifier.size(45.dp, 63.dp).clip(RoundedCornerShape(6.dp)))
                                    }
                                }
                            })
                    }
                } else {
                    val head = open.first()
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBtn(Icons.Outlined.ArrowBack, "Back to sources", { openMined = null })
                            Text(head.sourceTitle ?: "Other sentences", color = c.ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                    item { SectionEyebrow("${open.size} ${if (open.size == 1) "sentence" else "sentences"}", top = 8.dp) }
                    items(open, key = { "sent-${it.sentence.id}" }) { row ->
                        SwipeToRemove(onRemove = { vm.deleteSentence(row.sentence.id) }) {   // swipe left, as everywhere
                            MinedSentenceRow(row, onWord = { w, lemma, reading -> vm.selectMined(w, row, lemma, reading) })
                        }
                    }
                }
            }
        }
    }
    val minedLook by vm.lookup.collectAsStateWithLifecycle()
    minedLook?.let { MinedLookupSheet(vm, it, onClose = vm::clearLookup, onAddCard = onAddCard) }
    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = c.card,
            title = { Text(if (a.chapters > 1) "Remove this book?" else "Remove this?") },
            text = { Text("${a.bookTitle ?: a.title} will be removed from this phone. Cards and mined sentences you made from it stay.") },
            confirmButton = { TextButton({ vm.delete(a); toDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Cancel") } }
        )
    }
    bookMenu?.let { e ->
        AlertDialog(
            onDismissRequest = { bookMenu = null }, containerColor = c.card,
            title = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (e.id in shelfIds) TextButton({ vm.unshelve(e.id); bookMenu = null }) { Text("Remove from shelf") }
                    if (e.sourceId != null) TextButton({ removeBook = e; bookMenu = null }) { Text(if (e.origin == "file") "Delete file" else "Remove download") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ bookMenu = null }) { Text("Cancel") } }
        )
    }
    removeBook?.let { e ->
        val own = e.origin == "file"
        AlertDialog(
            onDismissRequest = { removeBook = null }, containerColor = c.card,
            title = { Text(if (own) "Delete this file?" else "Remove this download?") },
            text = { Text(if (own) "${e.title} will be removed from this phone. Cards and mined sentences you made from it stay. Add the file again with + if you want it back."
                else "${e.title} will be removed from this phone. Cards and mined sentences you made from it stay. You can download it again.") },
            confirmButton = { TextButton({ vm.removeDownload(e); removeBook = null }) { Text(if (own) "Delete" else "Remove") } },
            dismissButton = { TextButton({ removeBook = null }) { Text("Cancel") } }
        )
    }
    if (importing) ImportSheet(
        busy = importUi.busy, message = importUi.message,
        onPaste = { text -> vm.importText(text) { r -> if (importing) { importing = false; if (r.chapters > 1) onOpenBook(r.sourceId) else onOpen(r.firstArticleId) } } },
        onFile = { uri -> vm.importFile(uri) { r -> if (importing) { importing = false; if (r.chapters > 1) onOpenBook(r.sourceId) else onOpen(r.firstArticleId) } } },
        onClose = { importing = false; vm.clearImportMessage() }
    )
}

/** A source filter pill on the Books tab: filled when selected, hairline outline otherwise. */
@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Fluvour.colors
    Text(label, Modifier.clip(RoundedCornerShape(50)).then(if (selected) Modifier.background(c.primary) else Modifier.border(1.dp, c.line, RoundedCornerShape(50)))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (selected) c.onPrimary else c.mute, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

    val isArabic = Fluvour.lang == "ar"
    val fontSize = 17 + p.size * 2 + (if (isArabic) 4 else 0)   // Arabic with full vowel marks needs a bigger size and taller lines
    val font = targetFont()
    val style = remember(font, p.size, p.spacing, c.ink, isArabic) {
        val lines = if (isArabic) listOf(2.0f, 2.3f, 2.6f) else listOf(1.6f, 1.9f, 2.2f)
        TextStyle(color = c.ink, fontFamily = font, fontSize = fontSize.sp, lineHeight = (fontSize * lines[p.spacing]).sp,
            textDirection = if (isArabic) androidx.compose.ui.text.style.TextDirection.Rtl else androidx.compose.ui.text.style.TextDirection.Content)
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
    val title = chapterNav?.let { n -> "Chapter ${n.index + 1} of ${n.total}" } ?: if (imported) "Your file" else if (a.license.contains("Wikipedia")) "Wikipedia" else "Article"

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
                                else if (a.license.contains("Wikipedia")) "Wikipedia · ${a.license}" else a.license, Modifier.padding(top = 6.dp))
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
                LookupTabs(a.languageCode, l.word, l.lemma) {
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
