package app.fluvour.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluvour.ui.*
import app.fluvour.ui.theme.Fluvour

/** All cards in this language (route "browse"), opened from "Browse cards" on the Review tab. */
@Composable
fun BrowseScreen(vm: AppViewModel, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<app.fluvour.data.CardEntity?>(null) }
    val now = System.currentTimeMillis()
    val shown = remember(cards, query) {
        val q = query.trim()
        if (q.isEmpty()) cards else cards.filter { it.word.contains(q, true) || it.meaning.contains(q, true) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        PageBar(onBack, trailing = { IconBtn(Icons.Outlined.Add, "New card", { onEdit(null) }) })
        Eyebrow("All cards · ${cards.size}", Modifier.padding(top = 14.dp))
        H1("Browse", size = 30.sp)
        SearchField(query, { query = it }, "Search cards", Modifier.padding(top = 10.dp))
        if (shown.isEmpty()) Sm(if (cards.isEmpty()) "No cards yet. Tap + to add your first word." else "No cards match.", Modifier.padding(top = 22.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.id }) { card ->
                val due = card.dueAt == null || card.dueAt <= now
                // swipe a card left to delete it, the same gesture as the topics in Output; a dialog asks first
                SwipeToRemove(onRemove = { toDelete = card }) {
                    FRow(card.word, subtitle = card.meaning, titleFont = targetFont(), titleSize = 20.sp, onClick = { onEdit(card.id) },
                        trailing = { Tag(if (due) "Due" else "Done") })
                }
            }
        }
    }
    toDelete?.let { d ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = Fluvour.colors.card,
            title = { androidx.compose.material3.Text("Delete this card?") },
            text = { androidx.compose.material3.Text("${d.word} and its review history will be removed from your reviews.") },
            confirmButton = { androidx.compose.material3.TextButton({ vm.deleteCard(d.id); toDelete = null }) { androidx.compose.material3.Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton({ toDelete = null }) { androidx.compose.material3.Text("Cancel") } }
        )
    }
}
