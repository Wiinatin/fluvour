package app.fluvour.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluvour.audio.TtsManager
import app.fluvour.data.ArticleEntity
import app.fluvour.data.ChapterRef
import app.fluvour.data.ReadItem
import app.fluvour.data.SourceEntity
import app.fluvour.reading.ImportRepository
import app.fluvour.reading.BookDownloader
import app.fluvour.reading.CatalogBook
import app.fluvour.reading.ContinueItem
import app.fluvour.reading.GutendexClient
import app.fluvour.reading.LibraryMath
import app.fluvour.reading.LibraryRepository
import app.fluvour.reading.OwnBook
import app.fluvour.data.LibraryBookEntity
import app.fluvour.data.CardRepository
import app.fluvour.reading.Coverage
import app.fluvour.reading.DictionaryRepository
import app.fluvour.reading.ImportedLookup
import app.fluvour.reading.LevelInfo
import app.fluvour.reading.LevelRepository
import app.fluvour.reading.WordSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.fluvour.data.SentenceWithSource
import app.fluvour.data.SettingsRepository
import app.fluvour.data.BookmarkEntity
import app.fluvour.data.HighlightEntity
import app.fluvour.data.ReadPrefs
import app.fluvour.reading.ReaderRepository
import app.fluvour.reading.ArticleHit
import app.fluvour.reading.ArticleRepository
import app.fluvour.reading.LookupRepository
import app.fluvour.reading.Part
import app.fluvour.reading.SentenceRepository
import app.fluvour.reading.WikipediaClient
import app.fluvour.reading.ClipLevels
import app.fluvour.reading.articles.ArticleCatalog
import app.fluvour.reading.articles.ArticleItem
import app.fluvour.reading.articles.ArticleLevel
import app.fluvour.reading.articles.ArticleLevelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReadUi(val loading: Boolean = false, val hits: List<ArticleHit> = emptyList(), val message: String? = null, val opening: Long? = null)

/** One row of the Articles list: the article, its measured level if known (`label`, `levelCode`), and the saved copy's id if there is one. */
data class ArticleRow(val item: ArticleItem, val levelCode: String?, val label: String?, val savedId: String?)

/** The Articles list. `declared` is the level set on the Watch tab (null if not set). `checking` is true while levels are being measured. */
data class ArticleListUi(
    val loading: Boolean = false, val rows: List<ArticleRow> = emptyList(), val failed: List<String> = emptyList(), val message: String? = null,
    val opening: String? = null, val checking: Boolean = false, val loadedLang: String? = null, val declared: String? = null
)

/** The Gutenberg catalog on the Read page: popular books, or the results of a search. `loaded` is false until a request has worked. */
data class CatalogUi(
    val loading: Boolean = false, val loadingMore: Boolean = false, val books: List<CatalogBook> = emptyList(), val hasNext: Boolean = false,
    val page: Int = 1, val query: String = "", val message: String? = null, val loaded: Boolean = false
)

/** State of the "Add to Read" sheet while a file or pasted text is being imported. */
data class ImportUi(val busy: Boolean = false, val message: String? = null)

/** A word tapped inside a mined sentence on the Mined page. `sentenceId` is the saved sentence, so "Add card" links to it. */
data class MinedLookup(val word: String, val sentence: String, val sentenceId: String, val lang: String, val lemma: String? = null, val reading: String? = null)

/** The Read tab: saved articles and books, search / random from Wikipedia in the target language, and import. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReadViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository, private val repo: ArticleRepository, private val sentenceRepo: SentenceRepository,
    private val importer: ImportRepository, private val library: LibraryRepository, private val downloader: BookDownloader,
    private val lookups: LookupRepository, private val tts: TtsManager, private val levelRepo: LevelRepository, private val dictRepo: DictionaryRepository,
    private val articleLevels: ArticleLevelStore
) : ViewModel() {
    private val lang = settingsRepo.settings.map { it.targetLang }.distinctUntilChanged()
    /** One entry per saved article or book (a book is its first chapter plus a chapter count). */
    val saved: StateFlow<List<ReadItem>> =
        lang.flatMapLatest { repo.observeItems(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Sentences mined so far in the target language, newest first. */
    val sentences: StateFlow<List<SentenceWithSource>> =
        // grouped by source on screen, so no 50-row cap
        lang.flatMapLatest { sentenceRepo.observeRecent(it, 5000) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun deleteSentence(id: String) = viewModelScope.launch { sentenceRepo.delete(id) }

    // ---- Word lookup inside Mined (same content as the reader's and Watch's popup) ----
    private val _lookup = MutableStateFlow<MinedLookup?>(null)
    val lookup: StateFlow<MinedLookup?> = _lookup.asStateFlow()
    private val _meanings = MutableStateFlow(Meanings())
    val meanings: StateFlow<Meanings> = _meanings.asStateFlow()
    private val _level = MutableStateFlow<LevelInfo?>(null)
    val level: StateFlow<LevelInfo?> = _level.asStateFlow()
    private val _imported = MutableStateFlow<ImportedLookup?>(null)
    val imported: StateFlow<ImportedLookup?> = _imported.asStateFlow()
    private val _voiceHint = MutableStateFlow(false)
    val voiceHint: StateFlow<Boolean> = _voiceHint.asStateFlow()
    private var lookupJob: Job? = null

    fun selectMined(word: String, row: SentenceWithSource, lemma: String?, reading: String?) {
        lookupJob?.cancel()
        val lang = row.sentence.languageCode
        val sentence = row.sentence.text
        _lookup.value = MinedLookup(word, sentence, row.sentence.id, lang, lemma, reading)
        _meanings.value = Meanings(); _level.value = null; _imported.value = null
        lookupJob = viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val userId = settingsRepo.ensureUserId()
            launch { _level.value = levelRepo.lookup(lang, lemma, word) }
            launch { _imported.value = dictRepo.lookup(lang, lemma, word) }
            launch { val p = lookups.dictionary(userId, lang, lemma ?: word); _meanings.update { it.copy(dictionary = p) } }
            launch { val p = lookups.explain(userId, lang, s.uiLang, word, sentence); _meanings.update { it.copy(explanation = p) } }
        }
    }
    fun clearLookup() { lookupJob?.cancel(); _lookup.value = null }
    fun speak(text: String, lang: String) { _voiceHint.value = tts.speak(text, lang) == TtsManager.Result.NO_VOICE }
    override fun onCleared() { tts.stop() }

    private val _ui = MutableStateFlow(ReadUi())
    val ui: StateFlow<ReadUi> = _ui.asStateFlow()

    /** Blank query = random articles. */
    fun find(query: String) = viewModelScope.launch {
        _ui.value = ReadUi(loading = true)
        val l = settingsRepo.settings.first().targetLang
        WikipediaClient.list(l, query).fold(
            onSuccess = { _ui.value = ReadUi(hits = it, message = if (it.isEmpty()) "Nothing found. Try another word." else null) },
            onFailure = { _ui.value = ReadUi(message = "Could not reach Wikipedia. Check your connection.") }
        )
    }

    fun open(hit: ArticleHit, onReady: (String) -> Unit) = viewModelScope.launch {
        _ui.value = _ui.value.copy(opening = hit.pageId, message = null)
        val userId = settingsRepo.ensureUserId()
        repo.import(userId, hit).fold(
            onSuccess = { _ui.value = _ui.value.copy(opening = null); onReady(it) },
            onFailure = { _ui.value = _ui.value.copy(opening = null, message = it.message ?: "Could not open the article.") }
        )
    }

    // ---- Articles list: online articles from the sources in ArticleCatalog, with a measured level on each row ----
    private val _list = MutableStateFlow(ArticleListUi())
    val list: StateFlow<ArticleListUi> = _list.asStateFlow()
    private var listJob: Job? = null

    private suspend fun rowsFor(items: List<ArticleItem>, lang: String): List<ArticleRow> {
        val urls = items.map { it.url }
        val lv = articleLevels.levelsFor(urls)
        val savedIds = repo.savedIds(urls)
        return items.distinctBy { it.url }.map { i ->
            val code = lv[i.url]?.levelCode
            ArticleRow(i, code, ArticleLevel.label(lang, code), savedIds[i.url])
        }
    }

    /** Lists articles for the target language. Results stay in memory, so coming back to the tab does not ask the sites again (unless `force` or the language changed). */
    fun loadArticles(force: Boolean = false) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val s = settingsRepo.settings.first(); val l = s.targetLang; val declared = s.levels[l]
            val cur = _list.value
            if (!force && cur.loadedLang == l && cur.rows.isNotEmpty()) { _list.value = cur.copy(declared = declared); return@launch }
            _list.value = ArticleListUi(loading = true, loadedLang = l, declared = declared)
            val r = ArticleCatalog.list(l, declared)
            val rows = rowsFor(r.items, l)
            _list.value = ArticleListUi(rows = rows, failed = r.failed, loadedLang = l, declared = declared,
                message = if (rows.isEmpty()) "No articles came back. Check your connection." else null)
        }
    }

    /** Opens a row. A saved copy opens at once. Otherwise the text is fetched, its level is measured and stored, then it is saved and opened. */
    fun openArticle(row: ArticleRow, onReady: (String) -> Unit) = viewModelScope.launch {
        row.savedId?.let { onReady(it); return@launch }
        val url = row.item.url
        _list.update { it.copy(opening = url, message = null) }
        val userId = settingsRepo.ensureUserId()
        val l = row.item.lang
        val fetched = ArticleCatalog.fetch(row.item)
        val a = fetched.getOrElse {
            _list.update { u -> u.copy(opening = null, message = "Could not open this article. Try again, or pick another.") }; return@launch
        }
        val m = articleLevels.measureAndStore(a.url, l, a.site, a.title, a.body)
        repo.saveFetched(userId, l, a).fold(
            onSuccess = { id ->
                _list.update { u -> u.copy(opening = null, rows = u.rows.map { r ->
                    if (r.item.url == url) r.copy(levelCode = m.levelCode, label = ArticleLevel.label(l, m.levelCode), savedId = id) else r }) }
                onReady(id)
            },
            onFailure = { _list.update { u -> u.copy(opening = null, message = "Could not save this article.") } }
        )
    }

    /** Measures the level of up to [limit] rows that have none yet, one after another (the sites are asked politely, one at a time). Nothing is saved except the level. */
    fun checkLevels(limit: Int = 8) {
        if (_list.value.checking) return
        viewModelScope.launch {
            val l = _list.value.loadedLang ?: return@launch
            val todo = _list.value.rows.filter { it.levelCode == null && it.label == null && it.savedId == null }.take(limit)
            if (todo.isEmpty()) { _list.update { it.copy(message = "Every article here already has a level.") }; return@launch }
            _list.update { it.copy(checking = true, message = null) }
            var done = 0
            for (row in todo) {
                val a = ArticleCatalog.fetch(row.item).getOrNull() ?: continue
                val m = articleLevels.measureAndStore(a.url, l, a.site, a.title, a.body)
                done++
                _list.update { u -> u.copy(rows = u.rows.map { r ->
                    if (r.item.url == row.item.url) r.copy(levelCode = m.levelCode, label = ArticleLevel.label(l, m.levelCode)) else r }) }
            }
            _list.update { it.copy(checking = false, message = if (done == 0) "Could not check levels. Try again later." else "Checked $done of ${todo.size}.") }
        }
    }

    // ---- Books: shelf, downloads, progress and the Gutenberg catalog ----
    val shelf: StateFlow<List<LibraryBookEntity>> = lang.flatMapLatest { library.shelf(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloaded: StateFlow<List<LibraryBookEntity>> = lang.flatMapLatest { library.downloaded(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val shelfIds: StateFlow<Set<String>> = lang.flatMapLatest { library.shelfIds(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val continueItems: StateFlow<List<ContinueItem>> = lang.flatMapLatest { library.continueReading(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _catalog = MutableStateFlow(CatalogUi())
    val catalog: StateFlow<CatalogUi> = _catalog.asStateFlow()
    private var catalogJob: Job? = null
    private var catalogLang: String? = null

    /** Blank `query` = most downloaded books. Results stay in memory, so coming back to the page does not ask again (unless `force`, or the language or query changed). */
    fun loadCatalog(query: String = "", force: Boolean = false) {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            val l = settingsRepo.settings.first().targetLang
            val q = query.trim()
            val cur = _catalog.value
            if (!force && cur.loaded && catalogLang == l && cur.query == q) return@launch
            catalogLang = l
            _catalog.value = CatalogUi(loading = true, query = q)
            GutendexClient.search(l, q.ifEmpty { null }).fold(
                onSuccess = { _catalog.value = CatalogUi(books = it.books, hasNext = it.hasNext, query = q, loaded = true, message = if (it.books.isEmpty()) "No books found. Try another word." else null) },
                onFailure = { _catalog.value = CatalogUi(query = q, message = "Could not reach the book catalog. Your downloaded books still work.") }
            )
        }
    }

    fun loadMore() {
        val cur = _catalog.value
        val l = catalogLang ?: return
        if (cur.loading || cur.loadingMore || !cur.hasNext) return
        _catalog.value = cur.copy(loadingMore = true, message = null)
        viewModelScope.launch {
            GutendexClient.search(l, cur.query.ifEmpty { null }, cur.page + 1).fold(
                onSuccess = { p -> _catalog.update { if (it.query != cur.query) it else it.copy(books = (it.books + p.books).distinctBy { b -> b.id }, hasNext = p.hasNext, page = cur.page + 1, loadingMore = false) } },
                onFailure = { _catalog.update { if (it.query != cur.query) it else it.copy(loadingMore = false, message = "Could not load more books.") } }
            )
        }
    }

    /** The heart on a catalog book. */
    fun toggleHeart(book: CatalogBook) { viewModelScope.launch { library.toggleShelf(book, settingsRepo.settings.first().targetLang) } }

    /** Gives the book a library row (without shelving or downloading it) and hands back its id, for the detail page. */
    fun openDetail(book: CatalogBook, go: (String) -> Unit) { viewModelScope.launch { go(library.ensure(book, settingsRepo.settings.first().targetLang)) } }

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    /** Library ids being downloaded now. */
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()
    private val _bookMessage = MutableStateFlow<String?>(null)
    val bookMessage: StateFlow<String?> = _bookMessage.asStateFlow()
    fun clearBookMessage() { _bookMessage.value = null }

    /** Downloads a catalog book for offline reading, then calls `onReady`. One download per book at a time. */
    fun download(book: CatalogBook, onReady: (BookDownloader.Downloaded) -> Unit) {
        val id = LibraryMath.catalogId("gutenberg", book.id)
        if (id in _downloading.value) return
        _downloading.update { it + id }
        _bookMessage.value = null
        viewModelScope.launch {
            val l = settingsRepo.settings.first().targetLang
            downloader.download(settingsRepo.ensureUserId(), l, book).fold(
                onSuccess = { _downloading.update { s -> s - id }; onReady(it) },
                onFailure = { _downloading.update { s -> s - id }; _bookMessage.value = it.message ?: "Could not download the book." }
            )
        }
    }

    /** The heart on an own file (a row that already exists). */
    /** Removes a downloaded book's text and cover. A catalog book keeps its heart; an own file is forgotten entirely. */
    fun removeDownload(b: LibraryBookEntity) {
        viewModelScope.launch {
            b.sourceId?.let { repo.deleteSource(it) }
            if (b.origin == "file") b.sourceId?.let { library.forgetOwnFile(it) } else library.clearDownload(b.id)
        }
    }

    fun unshelve(id: String) { viewModelScope.launch { library.setShelved(id, false) } }

    fun toggleOwnHeart(id: String) { viewModelScope.launch { library.setShelved(id, id !in shelfIds.value) } }

    init {
        // Books imported before the library existed get a row (generated cover). Waits while a catalog download is running.
        viewModelScope.launch {
            lang.collectLatest { l ->
                if (_downloading.value.isNotEmpty()) return@collectLatest
                val linked = library.downloaded(l).first().mapNotNull { it.sourceId }.toSet()
                val missing = repo.observeItems(l).first().filter { it.chapters > 1 && it.sourceId !in linked }
                    .map { OwnBook(it.sourceId, it.bookTitle ?: it.title, ImportRepository.author(it.meta)) }
                library.adoptOwnFiles(l, missing)
            }
        }
    }

    fun delete(item: ReadItem) = viewModelScope.launch { repo.deleteSource(item.sourceId); library.forgetOwnFile(item.sourceId) }

    private val _import = MutableStateFlow(ImportUi())
    val importUi: StateFlow<ImportUi> = _import.asStateFlow()
    fun clearImportMessage() { _import.update { it.copy(message = null) } }

    /** Saves pasted text (split into parts when long). `onDone` gets the result so the screen can open it. */
    fun importText(text: String, onDone: (ImportRepository.Imported) -> Unit) = runImport(onDone) { userId, l -> importer.importText(userId, l, text) }

    /** Saves a picked .epub, .txt or .html file. */
    fun importFile(uri: android.net.Uri, onDone: (ImportRepository.Imported) -> Unit) = runImport(onDone) { userId, l -> importer.importFile(userId, l, uri) }

    private fun runImport(onDone: (ImportRepository.Imported) -> Unit, work: suspend (String, String) -> Result<ImportRepository.Imported>) {
        if (_import.value.busy) return
        viewModelScope.launch {
            _import.value = ImportUi(busy = true)
            val l = settingsRepo.settings.first().targetLang
            work(settingsRepo.ensureUserId(), l).fold(
                onSuccess = {
                    // A book (more than one chapter) gets a library row and its cover before the screen opens it.
                    if (it.chapters > 1) library.addOwnFile(it.sourceId, l, it.title, it.author, it.cover)
                    _import.value = ImportUi(); onDone(it)
                },
                onFailure = { _import.value = ImportUi(message = it.message ?: "Could not import this.") }
            )
        }
    }
}

/** A book's contents page: the book and its chapters in order. */
@HiltViewModel
class BookViewModel @Inject constructor(state: SavedStateHandle, repo: ArticleRepository) : ViewModel() {
    private val sourceId = state.get<String>("sourceId") ?: ""
    val source: StateFlow<SourceEntity?> = repo.observeSource(sourceId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val chapters: StateFlow<List<ChapterRef>> = repo.observeChapters(sourceId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

/** Where an open chapter sits in its book (only for sources with two or more chapters). */
data class ChapterNav(val index: Int, val total: Int, val prevId: String?, val nextId: String?)

/** Where the tapped sentence sits: paragraph number and character range inside that paragraph. */
data class SentenceSpot(val paragraph: Int, val start: Int, val end: Int)

data class Lookup(val word: String, val sentence: String, val lemma: String? = null, val reading: String? = null, val spot: SentenceSpot? = null)

/** What the popup shows for the tapped word. `null` text and message means still loading. */
data class Meanings(val dictionary: Part? = null, val explanation: Part? = null)

/** One open article: the text, the tapped word and its sentence, meanings, and speech. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    state: SavedStateHandle, repo: ArticleRepository, private val settingsRepo: SettingsRepository,
    private val lookups: LookupRepository, private val tts: TtsManager, private val sentenceRepo: SentenceRepository,
    cardRepo: CardRepository, private val levelRepo: LevelRepository, private val dictRepo: DictionaryRepository,
    private val readerRepo: ReaderRepository
) : ViewModel() {
    val article: StateFlow<ArticleEntity?> =
        repo.observeOne(state.get<String>("id") ?: "").stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val sourceId: Flow<String?> = article.map { it?.sourceId }.distinctUntilChanged()
    /** The source of the open text: Wikipedia article or imported file (kind "manual"). */
    val source: StateFlow<SourceEntity?> =
        sourceId.flatMapLatest { if (it == null) flowOf(null) else repo.observeSource(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Previous and next chapter when the text is part of a book; null for a single article. */
    val chapterNav: StateFlow<ChapterNav?> =
        sourceId.flatMapLatest { if (it == null) flowOf(emptyList()) else repo.observeChapters(it) }
            .combine(article) { list, a ->
                val i = if (a == null) -1 else list.indexOfFirst { it.id == a.id }
                if (list.size < 2 || i < 0) null else ChapterNav(i, list.size, list.getOrNull(i - 1)?.id, list.getOrNull(i + 1)?.id)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Paragraphs as the reader shows them, each with its tokens. Split once per article, off the main thread. */
    val splitText: StateFlow<List<Pair<String, List<app.fluvour.reading.Token>>>> =
        article.filterNotNull().distinctUntilChangedBy { it.id }.mapLatest { a ->
            withContext(Dispatchers.Default) {
                a.body.split(Regex("\\n+")).filter { it.isNotBlank() }.map { it to WordSplitter.split(it, a.languageCode) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Known-word coverage of this article (Phase 2A step 6); null until the text is split. Updates when cards graduate. */
    val coverage: StateFlow<Coverage?> =
        article.filterNotNull().map { it.languageCode }.distinctUntilChanged()
            .flatMapLatest { l -> cardRepo.observeKnownWords(l).map { l to it } }
            .combine(splitText) { (l, words), texts -> if (texts.isEmpty()) null else Coverage.compute(texts, words.toSet(), l) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Sentences already mined from this article, so the sheet can show "Saved" for the tapped one. */
    private val minedTexts: StateFlow<Set<String>> =
        article.map { it?.sourceId }.distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(emptyList<String>()) else sentenceRepo.observeTextsForSource(id) }
            .map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _lookup = MutableStateFlow<Lookup?>(null)
    val lookup: StateFlow<Lookup?> = _lookup.asStateFlow()
    /** True when the sentence in the open popup is already saved. Declared after `_lookup` so it is initialised. */
    val sentenceSaved: StateFlow<Boolean> =
        combine(_lookup, minedTexts) { l, mined -> l != null && l.sentence.trim() in mined }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _meanings = MutableStateFlow(Meanings())
    val meanings: StateFlow<Meanings> = _meanings.asStateFlow()
    /** Exam level and frequency rank of the tapped word (Phase 2A step 7); null when the word lists do not know it. */
    private val _level = MutableStateFlow<LevelInfo?>(null)
    val level: StateFlow<LevelInfo?> = _level.asStateFlow()
    /** What the user's imported Yomitan dictionaries say about the tapped word (Phase 2A step 9); null until loaded, empty when there are none. */
    private val _imported = MutableStateFlow<ImportedLookup?>(null)
    val imported: StateFlow<ImportedLookup?> = _imported.asStateFlow()
    private val _voiceHint = MutableStateFlow(false)
    val voiceHint: StateFlow<Boolean> = _voiceHint.asStateFlow()
    private var job: Job? = null

    /** Opens the popup and fetches the dictionary entry and the AI explanation side by side (both cached after the first time). */
    fun select(word: String, sentence: String, lemma: String? = null, reading: String? = null, spot: SentenceSpot? = null) {
        job?.cancel()
        _lookup.value = Lookup(word, sentence, lemma, reading, spot)
        _meanings.value = Meanings()
        _level.value = null
        _imported.value = null
        job = viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val userId = settingsRepo.ensureUserId()
            val lang = article.value?.languageCode ?: s.targetLang
            launch { _level.value = levelRepo.lookup(lang, lemma, word) }
            launch { _imported.value = dictRepo.lookup(lang, lemma, word) }
            launch { val p = lookups.dictionary(userId, lang, lemma ?: word); _meanings.update { it.copy(dictionary = p) } }
            launch { val p = lookups.explain(userId, lang, s.uiLang, word, sentence); _meanings.update { it.copy(explanation = p) } }
        }
    }
    fun clear() { job?.cancel(); _lookup.value = null }

    /** Saves the popup's sentence with this article as its source and its place in the text. Returns the sentence id (existing one if already saved). */
    private suspend fun mineCurrent(): String? {
        val l = _lookup.value ?: return null
        val a = article.value ?: return null
        if (l.sentence.isBlank()) return null
        val position = l.spot?.let { SentenceRepository.position(it.paragraph, it.start, it.end, chapterNav.value?.index) }
        return sentenceRepo.mine(settingsRepo.ensureUserId(), a.languageCode, l.sentence, a.sourceId, position)
    }

    /** One tap: save the sentence only (no card). The sheet then shows "Sentence saved". */
    fun saveSentence() { viewModelScope.launch { mineCurrent() } }

    /** Save the sentence, then hand its id to the card editor so the card links to it. */
    fun mineForCard(onReady: (sentenceId: String?) -> Unit) { viewModelScope.launch { onReady(mineCurrent()) } }


    // ---- Reader: saved position, options, bookmarks, highlights ----

    private var cachedUser: String? = null
    private suspend fun userId() = cachedUser ?: settingsRepo.ensureUserId().also { cachedUser = it }

    /** Reader options (mode, theme, size, spacing); null until loaded so the reader never flashes the wrong layout. */
    val readPrefs: StateFlow<ReadPrefs?> = settingsRepo.readPrefs.map<ReadPrefs, ReadPrefs?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun setReadPrefs(change: (ReadPrefs) -> ReadPrefs) {
        val now = readPrefs.value ?: return
        viewModelScope.launch { settingsRepo.setReadPrefs(change(now)) }
    }

    /** Where the reader was last time. `loaded` is false until the saved row has been read, so the screen waits instead of starting at the top. */
    data class Resume(val loaded: Boolean = false, val para: Int = 0, val ch: Int = 0)
    val resume: StateFlow<Resume> =
        article.filterNotNull().map { it.id }.distinctUntilChanged().mapLatest { id ->
            val p = readerRepo.progress(id)
            Resume(true, p?.para ?: 0, p?.ch ?: 0)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, Resume())

    fun saveProgress(para: Int, ch: Int, percent: Float) {
        val id = article.value?.id ?: return
        viewModelScope.launch { readerRepo.saveProgress(userId(), id, para, ch, percent) }
    }

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        article.map { it?.id }.distinctUntilChanged()
            .flatMapLatest { if (it == null) flowOf(emptyList()) else readerRepo.observeBookmarks(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val highlights: StateFlow<List<HighlightEntity>> =
        article.map { it?.id }.distinctUntilChanged()
            .flatMapLatest { if (it == null) flowOf(emptyList()) else readerRepo.observeHighlights(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addBookmark(para: Int, ch: Int, snippet: String, percent: Float) {
        val id = article.value?.id ?: return
        viewModelScope.launch { readerRepo.addBookmark(userId(), id, para, ch, snippet, percent) }
    }
    fun deleteBookmarks(ids: List<String>) { viewModelScope.launch { readerRepo.deleteBookmarks(ids) } }
    fun highlight(para: Int, start: Int, end: Int, text: String, color: Int) {
        val id = article.value?.id ?: return
        viewModelScope.launch { readerRepo.highlight(userId(), id, para, start, end, text, color) }
    }
    fun deleteHighlight(id: String) { viewModelScope.launch { readerRepo.deleteHighlight(id) } }
    fun setNote(id: String, note: String?) { viewModelScope.launch { readerRepo.setNote(id, note) } }

    fun speak(text: String, lang: String) {
        _voiceHint.value = tts.speak(text, lang) == TtsManager.Result.NO_VOICE
    }
    override fun onCleared() { tts.stop() }
}
