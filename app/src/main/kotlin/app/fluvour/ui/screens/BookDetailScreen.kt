package app.fluvour.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/**
 * A library book: cover, title, author, tags, then Download (or Read / Continue once it is offline), the heart, About and progress.
 * Read opens the first chapter, Continue the chapter Win stopped in, Contents the chapter list.
 */
@Composable
fun BookDetailScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onOpenBook: (String) -> Unit, vm: BookDetailViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val book by vm.book.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageBar(onBack, "Book", trailing = { book?.let { b -> HeartButton(b.shelved, "your shelf") { vm.toggleHeart() } } })
        val b = book
        if (b == null) return@Column
        val offline = b.sourceId != null
        val firstChapter = chapters.firstOrNull()?.id
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    BookCover(b.title, b.author, b.coverUrl, b.coverPath, Modifier.size(124.dp, 184.dp), corner = 8.dp)
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        H2(b.title, size = 22.sp, font = targetFont())
                        if (b.author.isNotBlank()) Sm(b.author, Modifier.padding(top = 6.dp))
                        val tags = b.tags?.split('|')?.filter { it.isNotBlank() }.orEmpty()
                        if (tags.isNotEmpty()) Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { t ->
                                Text(t, Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = c.mute, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!offline) {
                        FButton(if (busy) "Downloading..." else "Download", { vm.download() }, Modifier.fillMaxWidth(), enabled = !busy && b.epubUrl != null)
                    } else {
                        val cont = progress
                        FButton(if (cont != null) "Continue" else "Read", {
                            val target = cont?.articleId ?: firstChapter
                            if (target != null) onOpen(target) else b.sourceId?.let(onOpenBook)
                        }, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FButton("Contents", { b.sourceId?.let(onOpenBook) }, Modifier.weight(1f), outlined = true)
                            FButton("Remove", { confirmRemove = true }, Modifier.weight(1f), soft = true)
                        }
                    }
                    message?.let { Sm(it, color = c.accent) }
                    if (offline) Sm(if (b.origin == "file") "Your file. It works without internet." else "Downloaded. It works without internet.")
                }
            }
            progress?.let { p ->
                item {
                    Column(Modifier.padding(top = 20.dp)) {
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
                            Box(Modifier.fillMaxWidth(p.overall.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                        }
                        Sm("${(p.overall * 100).toInt()}% read", Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (!b.about.isNullOrBlank()) item {
                Column(Modifier.padding(top = 26.dp)) {
                    Eyebrow("About")
                    Text(b.about!!, Modifier.padding(top = 8.dp), color = c.ink, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false }, containerColor = c.card,
        title = { Text(if (book?.origin == "file") "Remove this book?" else "Remove the download?") },
        text = { Text(if (book?.origin == "file") "The book is removed from this phone. Cards and mined sentences you made from it stay. To read it again, add the file again."
            else "The book is removed from this phone. Cards and mined sentences you made from it stay. You can download it again.") },
        confirmButton = { TextButton({ confirmRemove = false; vm.removeDownload { if (book?.origin == "file") onBack() } }) { Text("Remove") } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } }
    )
}
