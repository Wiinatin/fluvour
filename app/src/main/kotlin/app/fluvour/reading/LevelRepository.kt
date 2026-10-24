package app.fluvour.reading

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Loads `assets/levels/<lang>.tsv` once per language (a few hundred KB, off the main thread) and answers level / rank lookups. */
@Singleton
class LevelRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val tables = ConcurrentHashMap<String, LevelTable>()

    suspend fun lookup(lang: String, vararg words: String?): LevelInfo? = withContext(Dispatchers.IO) { table(lang)?.find(*words) }

    private fun table(lang: String): LevelTable? = tables[lang] ?: runCatching {
        ctx.assets.open("levels/$lang.tsv").bufferedReader().useLines { Levels.parse(it) }
    }.getOrNull()?.also { tables[lang] = it }
}
