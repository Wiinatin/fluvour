package app.fluvour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.fluvour.reading.HeisigStudyRepository
import app.fluvour.reading.Tier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Maturity colours for kanji inside running text (reading and watch pages). Static tint only: no frames, no shine, no layout change. */
class KanjiTints(private val tiers: Map<String, Tier>) {
    fun colorOf(ch: Char): Color? = tiers[ch.toString()]?.let { TierPalette.tint(it) }

    /** [text] with every kanji that has a tier above PLAIN coloured. Returns plain text when none. */
    fun annotate(text: String): AnnotatedString {
        var any = false
        val out = buildAnnotatedString {
            append(text)
            text.forEachIndexed { i, ch -> colorOf(ch)?.let { any = true; addStyle(SpanStyle(color = it), i, i + 1) } }
        }
        return if (any) out else AnnotatedString(text)
    }
}

/** Tiers worked out once when the page opens, plus the Settings switch. */
@HiltViewModel
class KanjiTintViewModel @Inject constructor(private val study: HeisigStudyRepository) : ViewModel() {
    val enabled: StateFlow<Boolean> = study.maturityColors
    private val _tiers = MutableStateFlow<Map<String, Tier>>(emptyMap())
    val tiers: StateFlow<Map<String, Tier>> = _tiers.asStateFlow()
    init { viewModelScope.launch { _tiers.value = study.tiers() } }
}

/** The tint helper for a page, or null when the Settings switch is off or no kanji has a tier yet. */
@Composable
fun rememberKanjiTints(vm: KanjiTintViewModel = hiltViewModel()): KanjiTints? {
    val on by vm.enabled.collectAsStateWithLifecycle()
    val t by vm.tiers.collectAsStateWithLifecycle()
    return remember(on, t) { if (on && t.isNotEmpty()) KanjiTints(t) else null }
}
