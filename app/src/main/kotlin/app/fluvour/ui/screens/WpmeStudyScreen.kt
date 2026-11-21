package app.fluvour.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.reading.WpmeData
import app.fluvour.reading.WpmeItem
import app.fluvour.srs.Rating
import app.fluvour.ui.*
import app.fluvour.ui.art.ambient
import app.fluvour.ui.art.languageMark
import app.fluvour.ui.theme.Fluvour

/**
 * Word Power study: a root, prefix or suffix, or a word, comes first and you recall its meaning, then check and rate yourself.
 * A word card's back is layered: its parts and what they mean, the deck's meaning, "See more" (the passage of your own book)
 * and "Read in the book". [onReader] opens the reader at the passage, [onSettings] goes to where the book is imported.
 */
@Composable
fun WpmeStudyScreen(onClose: () -> Unit, onReader: (String) -> Unit, onSettings: () -> Unit, vm: WpmeStudyViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().ambient().languageMark().statusBarsPadding().navigationBarsPadding().padding(bottom = 16.dp)) {
        PageBar(onClose, "Word Power", closeIcon = true, trailing = {
            if (!ui.loading && !ui.finished && !ui.noDeck) Sm("${ui.answered + 1} / ${ui.answered + ui.left}", Modifier.padding(end = 14.dp))
        })
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            when {
                ui.loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                ui.noDeck -> Column(Modifier.weight(1f).fillMaxWidth().padding(top = 60.dp)) {
                    H1("No deck yet", accentSuffix = ".")
                    Sm("Import your Word Power deck in Settings, then come back.", Modifier.padding(top = 10.dp))
                    LinkText("Close", onClose, Modifier.padding(top = 18.dp))
                }
                ui.finished -> Column(Modifier.weight(1f).fillMaxWidth().padding(top = 60.dp)) {
                    H1(if (ui.answered == 0) "Nothing due" else "All done", accentSuffix = ".")
                    Sm(
                        if (ui.answered == 0) "Nothing is waiting. Come back later." else "${ui.answered} reviewed, ${ui.remembered} remembered.",
                        Modifier.padding(top = 10.dp)
                    )
                    if (ui.canLearnMore) FButton("Learn 5 more new items", vm::learnMore, Modifier.fillMaxWidth().padding(top = 22.dp), outlined = true)
                    LinkText("Close", onClose, Modifier.padding(top = 18.dp))
                }
                else -> {
                    val card = ui.current!!
                    HairlineProgress(ui.progress, Modifier.padding(top = 10.dp, bottom = 16.dp))
                    StudyFace(Modifier.fillMaxWidth().weight(1f), onClick = if (ui.showAnswer) null else vm::reveal) {
                        if (!ui.showAnswer) Front(card) else Back(ui, vm, onReader, onSettings)
                    }
                    if (!ui.showAnswer) {
                        FButton("Show answer", vm::reveal, Modifier.fillMaxWidth().padding(top = 18.dp))
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Rating.AGAIN to "Again", Rating.HARD to "Hard", Rating.GOOD to "Good", Rating.EASY to "Easy").forEach { (r, label) ->
                                WpmeRatingButton(label, ui.previews[r]?.let(::formatInterval) ?: "", r == Rating.GOOD, Modifier.weight(1f)) { vm.rate(r) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun kindLabel(item: WpmeItem): String = when {
    item.kind == WpmeData.WORD -> "Word"
    item.form.startsWith("-") -> "Suffix"
    item.form.endsWith("-") -> "Prefix"
    else -> "Root"
}

@Composable
private fun ColumnScope.Front(card: WpmeItem) {
    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Eyebrow(kindLabel(card))
        H2(card.form, Modifier.padding(top = 14.dp), size = 38.sp, align = TextAlign.Center)
        Sm(
            if (card.kind == WpmeData.WORD) "Guess the meaning from its parts, then tap to check." else "Recall the meaning, then tap to check.",
            Modifier.padding(top = 18.dp), align = TextAlign.Center
        )
    }
}

@Composable
private fun Back(ui: WpmeStudyUi, vm: WpmeStudyViewModel, onReader: (String) -> Unit, onSettings: () -> Unit) {
    val c = Fluvour.colors
    val card = ui.current!!
    // Same layout as the Heisig back: one big form, the meaning under it, then small labelled sections, everything visible.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(kindLabel(card))
        H2(card.form, Modifier.padding(top = 4.dp), size = 52.sp, align = TextAlign.Center)
        Text(card.meaning, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (card.kind == WpmeData.WORD && ui.gloss.isNotBlank()) Text(ui.gloss, color = c.accent, fontSize = 15.sp, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.parts.isNotEmpty()) {
                Eyebrow("Parts")
                ui.parts.forEach { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(p.part, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(p.element?.meaning ?: "common ending or beginning", color = c.mute, fontSize = 14.sp)
                    }
                }
            }
            if (card.kind == WpmeData.ELEMENT && card.examples.isNotEmpty()) {
                Eyebrow("Examples", Modifier.padding(top = if (ui.parts.isNotEmpty()) 6.dp else 0.dp))
                Text(card.examples.joinToString(", "), color = c.ink, fontSize = 14.sp)
            }
            ui.excerpt?.let { ex ->
                Eyebrow("In the book", Modifier.padding(top = 6.dp))
                if (ex.heading.isNotBlank()) Text(ex.heading, color = c.ink, fontSize = 14.sp)
                Text(ex.text, color = c.mute, fontSize = 14.sp, maxLines = 9, overflow = TextOverflow.Ellipsis)
            }
        }
        BookLinks(ui, vm, onReader, onSettings)
    }
}

/** The book, for any card with a passage: the passage is shown above; this is the way into the reader. Comes from the user's own book and is never stored. */
@Composable
private fun BookLinks(ui: WpmeStudyUi, vm: WpmeStudyViewModel, onReader: (String) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (ui.bookState) {
            WpmeBookState.NONE -> {}
            WpmeBookState.NO_BOOK -> LinkText("Import the book to read this", onSettings)
            WpmeBookState.PASSAGE -> LinkText("Read in the book", { vm.readInBook(onReader) })
        }
        ui.note?.let { Sm(it) }
    }
}

@Composable
private fun WpmeRatingButton(label: String, interval: String, emphasized: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(12.dp)
    val fg = if (emphasized) c.onPrimary else c.ink
    Column(
        modifier.clip(shape).then(if (emphasized) Modifier.background(c.accent, shape) else Modifier.background(c.card, shape).border(1.dp, c.line, shape))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
        Text(interval, fontSize = 11.5.sp, color = if (emphasized) fg.copy(alpha = 0.9f) else c.mute)
    }
}
