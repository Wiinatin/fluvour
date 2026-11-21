package app.fluvour.path

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled curriculum files (assets/curriculum/<lang>-<path>.tsv). A language with no file has no curriculum,
 * which is how the Path sub-tab knows to hide itself. Paths for one language come back in file-name order
 * (genki1, genki2, tobira).
 */
@Singleton
class CurriculumRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val cache = HashMap<String, List<CurriculumPath>>()

    suspend fun paths(languageCode: String): List<CurriculumPath> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[languageCode]?.let { return@withContext it } }
        val loaded = runCatching {
            ctx.assets.list("curriculum").orEmpty()
                .filter { it.startsWith("$languageCode-") && it.endsWith(".tsv") }
                .sorted()
                .mapNotNull { name ->
                    ctx.assets.open("curriculum/$name").bufferedReader().use { CurriculumParser.parse(it.readText()) }
                }
        }.getOrDefault(emptyList())
        synchronized(cache) { cache[languageCode] = loaded }
        loaded
    }

    suspend fun hasCurriculum(languageCode: String): Boolean = paths(languageCode).isNotEmpty()

    suspend fun unit(languageCode: String, unitId: String): CurriculumUnit? =
        paths(languageCode).firstNotNullOfOrNull { path -> path.units.firstOrNull { it.id == unitId } }
}
