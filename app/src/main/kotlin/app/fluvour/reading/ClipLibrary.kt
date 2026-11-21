package app.fluvour.reading

import app.fluvour.data.AnalyzedVideoDao
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the local clip library under its cap (see [IndexPlan]). Cheap when there is nothing to do: two counts. */
@Singleton
class ClipLibrary @Inject constructor(private val analyzed: AnalyzedVideoDao) {

    /**
     * Over [IndexPlan.LIBRARY_CAP] usable clips: first drops clips of the current language whose level is too far from the declared level
     * (never recommended), never-shown and oldest first; if still too many, drops the never-shown and oldest of any language.
     * Always trims the marker rows too. [declaredCode] is the learner's level in [lang], null when none is set.
     */
    suspend fun prune(lang: String, declaredCode: String?) {
        val usable = analyzed.countUsable()
        if (usable > IndexPlan.LIBRARY_CAP) {
            val scale = ClipLevels.scale(lang)
            val declared = declaredCode?.let { scale.codes.indexOf(it) } ?: -1
            val levels = ClipPlan.libraryLevels(scale, declared)
            if (levels.isNotEmpty()) analyzed.deleteOutsideLevels(lang, levels, usable - IndexPlan.PRUNE_TO)
            val left = analyzed.countUsable() - IndexPlan.PRUNE_TO
            if (left > 0) analyzed.deleteOldest(left)
        }
        analyzed.trimMarkers(IndexPlan.MARKER_CAP)
    }
}
