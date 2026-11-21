package app.fluvour.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.quiz.QSentence
import app.fluvour.quiz.TatoebaClient
import app.fluvour.reading.KanjiCard
import app.fluvour.reading.KanjiData
import app.fluvour.reading.KanjiStrokes
import app.fluvour.reading.Levels
import app.fluvour.reading.StrokeData
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/**
 * The scrolling body of the word popup, with tabs: Vocabulary, Kanji (Japanese words with kanji only) and Sentences (real Tatoeba sentences).
 */
@Composable
fun LookupTabs(lang: String, word: String, lemma: String?, vocabulary: @Composable ColumnScope.() -> Unit) {
    val text = word + (lemma?.takeIf { it != word }.orEmpty())
    val hasKanji = lang == "ja" && KanjiData.hanChars(text).isNotEmpty()
    val hasKana = lang == "ja" && !hasKanji && kanaChars(text).isNotEmpty()
    val tabs = when {
        hasKanji -> listOf("Vocabulary", "Kanji", "Sentences")
        hasKana -> listOf("Vocabulary", "Kana", "Sentences")
        else -> listOf("Vocabulary", "Sentences")
    }
    var tab by remember(word) { mutableIntStateOf(0) }
    UnderlineTabs(tabs, tab.coerceAtMost(tabs.lastIndex), { tab = it })
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (tabs[tab.coerceAtMost(tabs.lastIndex)]) {
            "Kanji" -> KanjiPane(lang, text)
            "Kana" -> KanaPane(text)
            "Sentences" -> SentencesPane(lang, lemma ?: word)
            else -> vocabulary()
        }
    }
}

@Composable
private fun SentencesPane(lang: String, word: String, vm: SentencesViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val found by produceState<List<QSentence>?>(null, word) { value = vm.sentences(lang, word) }
    val list = found
    when {
        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        list.isEmpty() -> Text("No example sentences found. Check your connection, or this word may not be in Tatoeba.", color = c.mute, fontSize = 13.sp)
        else -> {
            list.forEach { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TargetText(s.text, size = 17.sp, lineHeight = 26.sp)
                    s.translation?.let { Text(it, color = c.mute, fontSize = 14.sp) }
                }
            }
            Text("Sentences: ${TatoebaClient.CREDIT}", color = c.mute, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun KanjiPane(lang: String, text: String, vm: KanjiViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val cards by produceState<List<KanjiCard>?>(null, text) { value = vm.cards(lang, text) }
    val known by produceState<Set<String>>(emptySet(), text) { value = vm.known() }
    val list = cards
    when {
        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        list.isEmpty() -> Text("No kanji data for this word.", color = c.mute, fontSize = 13.sp)
        else -> {
            val inDeck = list.filter { it.heisig != null }
            if (inDeck.isNotEmpty()) {
                val n = inDeck.count { it.entry.ch in known }
                Text(
                    if (n == inDeck.size) "You know all ${inDeck.size} kanji here." else "You know $n of ${inDeck.size} kanji here.",
                    color = c.mute, fontSize = 12.5.sp
                )
            }
            list.forEachIndexed { i, card ->
                if (i > 0) Spacer(Modifier.height(6.dp))
                KanjiCardView(card, known = card.entry.ch in known)
            }
            if (list.any { it.heisig != null }) Text("Keyword, frame, components and stories: your imported Heisig deck.", color = c.mute, fontSize = 10.5.sp)
            Text(KanjiData.CREDIT, color = c.mute, fontSize = 10.5.sp)
            Text(StrokeData.CREDIT, color = c.mute, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun KanjiCardView(card: KanjiCard, known: Boolean, vm: KanjiViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val e = card.entry
    val strokes by produceState<KanjiStrokes?>(null, e.ch) { value = vm.strokes(e.ch) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
            // the stroke animation starts by itself; kanji without stroke data keep the big glyph
            val st = strokes
            if (st != null) StrokeOrderView(st, boxSize = 120.dp) else TargetText(e.ch, size = 46.sp, lineHeight = 52.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                card.heisig?.let { h -> Text("${h.keyword}  ·  Heisig ${h.frame}", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                Text(e.meanings.take(6).joinToString("; "), color = c.ink, fontSize = 15.sp)
                if (e.on.isNotEmpty()) ReadingLine("On", e.on.joinToString("  "))
                if (e.kun.isNotEmpty()) ReadingLine("Kun", e.kun.joinToString("  "))
            }
        }
        val tags = listOfNotNull(
            card.heisig?.let { if (known) "Known" else "Not learned yet" },
            e.strokes?.let { "$it strokes" }, KanjiData.gradeLabel(e.grade), e.jlptOld?.let { "Old JLPT $it" }, Levels.rankLabel(e.freq)?.replace("Freq", "Kanji freq")
        )
        if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Tag(it) } }
        card.heisig?.let { h ->
            if (h.components.isNotBlank()) { Eyebrow("Components"); Text(h.components, color = c.ink, fontSize = 14.sp) }
            if (h.story1.isNotBlank()) { Eyebrow("Story"); Text(h.story1, color = c.ink, fontSize = 14.sp) }
            if (h.story2.isNotBlank()) Text(h.story2, color = c.mute, fontSize = 14.sp)
        }
        if (card.words.isNotEmpty()) {
            Eyebrow("Common words")
            Text(
                card.words.joinToString("    ") { w -> w.word + (Levels.levelLabel("ja", w.level)?.removePrefix("JLPT ")?.let { " $it" } ?: "") },
                color = c.ink, fontSize = 16.sp, fontFamily = targetFont()
            )
        }
    }
}

@Composable
private fun ReadingLine(label: String, value: String) {
    val c = Fluvour.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = c.mute, fontSize = 12.sp, modifier = Modifier.width(26.dp))
        Text(value, color = c.ink, fontSize = 15.sp, fontFamily = targetFont())
    }
}

/** Hiragana and katakana in the word, once each, in order. */
private fun kanaChars(text: String): List<String> =
    text.filter { it in '\u3041'..'\u3096' || it in '\u30A1'..'\u30FA' }.map { it.toString() }.distinct().take(6)

/** Stroke order for each kana of a kana-only word. Tap an animation to play it again. */
@Composable
private fun KanaPane(text: String, vm: KanjiViewModel = hiltViewModel()) {
    val c = Fluvour.colors
    val chars = remember(text) { kanaChars(text) }
    val found by produceState<List<Pair<String, KanjiStrokes?>>?>(null, text) { value = chars.map { it to vm.strokes(it) } }
    val list = found
    when {
        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        list.none { it.second != null } -> Text("No stroke order for these kana.", color = c.mute, fontSize = 13.sp)
        else -> {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                list.forEach { (ch, st) ->
                    if (st != null) Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StrokeOrderView(st, boxSize = 140.dp)
                        TargetText(ch, size = 20.sp, lineHeight = 26.sp)
                    }
                }
            }
            Text("Tap an animation to play it again.", color = c.mute, fontSize = 12.sp)
            Text(StrokeData.CREDIT, color = c.mute, fontSize = 10.5.sp)
        }
    }
}
