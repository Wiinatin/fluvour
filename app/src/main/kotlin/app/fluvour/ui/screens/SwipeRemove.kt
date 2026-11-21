package app.fluvour.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fluvour.ui.theme.Fluvour

/**
 * Swipe a row left to remove it, the same gesture as the Watch history. The row always springs back: [onRemove] is
 * expected to open a confirm dialog (or remove at once), and the list updates when the data does.
 * With [enabled] false the row does not swipe at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToRemove(enabled: Boolean = true, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val c = Fluvour.colors
    val current by rememberUpdatedState(onRemove)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) current() ; false })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = enabled, backgroundContent = {
        if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
            Box(Modifier.fillMaxSize().padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Delete, "Remove", Modifier.size(20.dp), tint = c.mute)
            }
    }) { content() }
}
