# Phase 2 status

| Part | Step | Status |
|---|---|---|
| 2A | 1 Article reader (sourced content) | **written, not yet built or run** (see below) |
| 2A | 2 Tap a word, popup meaning (dictionary + AI) | **written, not yet built or run** (see below) |
| 2A | 3 Tokenizers for Japanese, Chinese, Thai | **written, not yet built or run** (see below) |
| 2A | 4 One-tap sentence mining | **written, not yet built or run** (see below) |
| 2A | 5 Cloze cards from mined sentences | **written, not yet built or run** (see below) |
| 2A | 6 Known-word coverage | **written, not yet built or run** (see below) |
| 2A | 7 Level tags (JLPT / HSK / CEFR / frequency) | **written, not yet built or run** (see below) |
| 2A | 8 Book / EPUB and text import | **written, not yet built or run** (see below) |
| 2A | 9 Yomitan dictionary import | **written, not yet built or run** (see below) |
| 2B | Screen-OCR overlay | not started |

## 2A-1: article reader
- **Read tab** (replaces the placeholder): a search box, "Search" and "Random" buttons, and the list of saved articles. Articles come from Wikipedia in the *target language* (language codes match Fluvour's). Random picks drop stubs under 150 characters of intro.
- **Opening an article** downloads the full plain text once and saves it on the phone, so it reads offline afterwards. Each article gets a `sources` row (kind `article`, with the licence in `meta`) and an `articles` row (new table, Room migration 1 to 2, no existing data touched). Opening the same article again reuses the saved copy.
- **Licence and source** (CC BY-SA 4.0, link to the page) are shown on every article.
- **Reader screen** (route `reader/{id}`, bottom bar hidden): paragraphs of large text. Tap a word and a bottom sheet shows the word and its sentence, with speaker buttons (Android TTS) and "Add card". "Add card" opens the normal card editor with the word and the sentence already filled in (word and example).
- Remove an article from the saved list with the bin icon. Cards made from it stay.

## 2A-2: meanings in the popup
- Tapping a word now shows two sections under the word and sentence, loaded side by side with a spinner each:
  - **Dictionary:** Wiktionary (English edition, REST `definition` endpoint), meanings in English grouped by part of speech (up to 4 groups, 4 meanings each). Source and licence (CC BY-SA 4.0) are shown. Tries the lower-case form if the exact form is not found.
  - **In this sentence (AI):** a short explanation from Gemini through the ai-proxy (`gemini` provider, no new Edge Function version needed), written in your UI language: dictionary form and reading, what the word means in this sentence, one grammar note. Needs sign-in and the `GEMINI_API_KEYS` secret.
- **Cache:** new Room table `lookup_cache` (migration 2 to 3, nothing existing is touched). Dictionary results are cached per word, AI results per word + sentence + UI language. Only successes are cached, so a failed or rate-limited call is retried next tap. Local only, not synced.
- **Add card** now also pre-fills the Meaning field with the AI explanation when there is one (edit it freely).
- When signed out or out of quota, the dictionary still works and the AI section shows a one-line reason.
- Files: `reading/DictionaryClient.kt`, `reading/LookupRepository.kt`, `data/` (entity, DAO, migration), `ReadViewModel.kt`, `ReadScreens.kt`.

## 2A-3: tokenizers
- `WordSplitter.split(text, lang)` now picks an engine per language, same call as before:
  - **Japanese:** Kuromoji (IPADIC, `com.atilika.kuromoji:kuromoji-ipadic:0.9.0`). Besides splitting it gives the **dictionary form** (食べた gives 食べる) and a **hiragana reading**.
  - **Chinese:** jieba-analysis (`com.huaban:jieba-analysis:1.0.2`), search mode (no overlapping words). Offsets are used, because jieba lower-cases and normalises the words it returns.
  - **Thai:** the platform's ICU word breaker, which ships a Thai dictionary (no extra library). Quality varies by Android version; a dedicated segmenter can go behind the same call if it proves weak.
  - Everything else, and any engine that throws: ICU word breaks.
- Tokens now carry `lemma` and `reading`. In the popup: the reading (shown only for words with kanji) and "→ dictionary form"; the **dictionary lookup uses the dictionary form**, and **Add card uses it as the word** (the sentence still holds the inflected form).
- Splitting runs off the main thread (`produceState` on `Dispatchers.Default`), because the first Japanese or Chinese call loads a dictionary (a second or two, once). Taps are ignored until a paragraph is split.
- **Deviation from the roadmap:** it names Sudachi/MeCab. I used Kuromoji because it is a single Maven dependency with its dictionary inside, works on Android, and is a JVM library like Sudachi. Sudachi needs a separate multi-tens-of-MB dictionary download and set-up; it can replace Kuromoji behind `WordSplitter` later if Kuromoji's accuracy is not enough (its IPADIC is older and weaker on modern vocabulary).
- **Not done:** offline JMdict / CC-CEDICT dictionaries (meanings still come from Wiktionary, online), Chinese pinyin, Thai romanisation. These belong with step 9 (dictionary import) or a small follow-up.
- Risks to check on the first build: APK grows by roughly 15 to 25 MB (Kuromoji dictionary plus jieba's); jieba and Kuromoji load their dictionaries as jar resources, which normally works on Android but is the first thing to check if Japanese or Chinese text shows as one tappable block (the fallback to ICU is silent). `packaging.resources.excludes` was added for duplicate licence files.

## 2A-4: sentence mining
- **Save sentence** button in the word popup: one tap saves the tapped sentence as a `sentences` row (text, language, `sourceId` = the article's source, `position`). The button turns into "Sentence saved" when that sentence is already saved for this article. Saving the same sentence from the same source again reuses the existing row.
- **Add card** now saves the sentence first and links the card to it (`cards.sentenceId`), so a card keeps its sentence, source and position. "Save and add another" links only the first card; later ones are plain cards.
- **Position** is text: `{"paragraph":3,"start":12,"end":57}` = paragraph number (from 0) and character range inside that paragraph. Other sources (video, comic) will use their own keys.
- **Read tab** has a "Mined sentences" list (newest 50, with source title and a remove icon) so you can see what was saved. Removing a sentence does not delete cards made from it.
- **Sentence bounds** come from `WordSplitter.sentenceSpan` (same end marks as before, so the same abbreviation caveat applies).
- **Sync:** `sources` and `sentences` now sync along with decks, cards and the review log (sources and sentences are sent before cards, because the backend links cards to sentences and sentences to sources). Articles themselves still do not sync.
- No Room migration: the `sentences` table has existed since version 1, so existing data is untouched.
- Files: `reading/SentenceRepository.kt`, `reading/WordSplitter.kt` (`sentenceSpan`), `data/Daos.kt` (`SentenceDao`, `SourceDao`), `ReadViewModel.kt`, `ReadScreens.kt`, `CardEditorScreen.kt`, `SyncRepository.kt`.
- Not compiled or run, like the rest of Phase 2. Step 5 (cloze cards) will build on these saved sentences.

## 2A-5: cloze cards
- A card **linked to a mined sentence** (`sentenceId` set, made with Add card in the reader) whose example still contains the word is shown in Review as **fill-in-the-blank**: the sentence with the word replaced by a blank, the card's meaning as a hint (3 lines max). Flip shows the normal back (word, meaning, example with the word highlighted). Cards without a sentence, or whose sentence no longer holds the word, stay normal cards.
- **Inflected forms:** the match goes through `WordSplitter` tokens (surface or dictionary form), so a Japanese card 食べる blanks 食べた in the sentence. Fallback is a plain case-insensitive text search.
- No speech on the cloze front, and auto-play skips cloze cards, because saying the word gives the answer away.
- **No schema change, no migration.** "Auto-generated" = derived at review time from the existing card + sentence link. Same FSRS scheduling as any card.
- Files: `reading/Cloze.kt` (pure), `ui/ReviewViewModel.kt` (`clozeFor`, `ReviewUi.cloze`), `ui/screens/ReviewScreen.kt` (`ClozeFront`).
- Known gaps: cloze is not a per-card choice (no toggle in the card editor, no way to make a plain card from a mined sentence); the blank is a fixed-width underscore run; Anki export does not know about cloze; "Save and add another" links only the first card, so later ones stay plain.

## 2A-6: known-word coverage
- Under the article title: "You know 82% of the words here (410 of 500)". Before any card has graduated it says there are no known words yet.
- **Known** = cards in the target language that have graduated to the REVIEW phase (not new, not in learning steps). Nothing is stored; the number is recomputed whenever cards change.
- Every occurrence counts (a word seen ten times weighs ten). A word is known if its surface form or its dictionary form matches a known card word, ignoring case. Numbers are skipped.
- Splitting runs off the main thread once per article (`ReaderViewModel.splitText`). The reader screen splits each paragraph again for tapping; sharing one split is a cheap cleanup for later.
- Files: `reading/Coverage.kt` (pure), `CardDao.observeKnownWords`, `ReaderViewModel.coverage`, `ReadScreens.kt`.
- Known gaps: card words are the dictionary form, so for languages without a lemmatiser (Spanish, French, German...) inflected forms in the text ("corriendo") do not match the card "correr" and coverage reads low; Thai and Chinese depend on the splitter agreeing with how the card word was typed; no per-article value on the Saved list.

## 2A-7: level tags
- In the word popup, under the reading / dictionary form, small chips show the **exam level** and the **frequency rank** of the tapped word, for example `JLPT N5` `Freq #1,107`, `HSK 1`, `CEFR B1`. The lookup uses the dictionary form first, then the tapped form. Words the lists do not know show no chips.
- **What each language has:**

| Language | Exam level | Frequency rank |
|---|---|---|
| ja | JLPT N1 to N5 (about 8,000 words) | yes |
| zh | HSK 3.0 levels 1 to 6 and 7-9; HSK 2.0 level (shown as "HSK 2.0 · 3") only for words missing from 3.0 | yes |
| en | CEFR A1 to C2 | yes |
| th, ko, es, fr, de, vi, id | none (no open, reliable list found) | yes |

- **Frequency rank** is 1 = most common, from the top 30,000 words of each language. It is a guide to how common a word is, not an exam list.
- **Data** is bundled in `app/src/main/assets/levels/<lang>.tsv` (about 5 MB raw, much less in the APK). `LevelRepository` loads one language the first time it is needed, off the main thread; `Levels.kt` is pure JVM. Rebuild the files with `python3 tools/levels/build_levels.py` (needs network; `pip install wordfreq` for Japanese). Sources and licences: `docs/LEVELS.md`.
- No schema change, no migration, nothing synced.
- Files: `reading/Levels.kt` (pure), `reading/LevelRepository.kt`, `ui/ReadViewModel.kt` (`level`), `ui/screens/ReadScreens.kt` (chips), `tools/levels/build_levels.py`.
- Checked here: `Levels.kt` compiles with kotlinc 2.0.21 and gives the expected chips on the real files (食べる gives JLPT N5, 爱好 gives HSK 1, abandon gives CEFR B1). The Android side (assets, Hilt, popup) is not built, like the rest of Phase 2.
- **Known gaps:**
  - JLPT lists are Jonathan Waller's unofficial lists (there is no official JLPT vocabulary list), so a level can be off by one. Words with kanji are tagged by their kanji form only; kana-only words by their kana.
  - Japanese rank comes from `wordfreq` tokens, so some inflected forms have their own rank; dictionary forms are usually fine.
  - Frequency lists come from film and TV subtitles, so spoken words rank higher than they would in written text and some names or fragments appear.
  - Korean (TOPIK), Spanish / French / German (CEFR) have no tags. A list you may use can go behind the same `.tsv` format.
  - Tags are not stored on the card and the article has no level summary yet (for example "mostly B1"). Both are small follow-ups.

## 2A-8: book, EPUB and text import
- **Add to Read** (the + on the Read tab) now works: **Paste text**, or **Book or file** (.epub, .txt, .md, .html). The file picker accepts any file and checks the extension after. Subtitles (.srt) stay greyed out until the Watch screen (Phase 4, step 6). PDF is not supported (it was listed in the old placeholder).
- **How it is saved:** one `sources` row (kind `manual`, `meta` = format, author, chapter count) plus one `articles` row per chapter, in order (new column `ordinal`, Room migration 3 to 4, existing rows get 0). So the reader, tap-to-lookup, sentence mining, cloze cards and known-word coverage work on imported text with no change. Coverage is per chapter.
- **Why `manual`:** the backend check on `sources.kind` only allows article, video, comic, speaking and manual, so "import" would be rejected on sync. If you prefer a real `import` kind, run `alter table sources drop constraint sources_kind_check; alter table sources add constraint sources_kind_check check (kind in ('article','video','comic','speaking','manual','import'));` in Supabase and change `ImportRepository.SOURCE_KIND`.
- **EPUB:** read with the JDK only (no new library). Chapters follow the book's reading order; titles come from its contents page (EPUB 3 nav or EPUB 2 ncx), else the first heading, else "Chapter n". Files the contents page does not list continue the chapter before. Furigana (`<rt>`) is dropped so Japanese text stays clean; line breaks inside HTML paragraphs are not paragraph breaks. Covers, dividers and image-only pages are skipped. A book with no readable text (copy-protected) gives a clear message.
- **Plain text:** UTF-8 first, then Shift_JIS (ja), GB18030 (zh), Korean and Thai code pages, then Windows-1252. Hard-wrapped books (blank line between paragraphs, line breaks inside) are re-joined; one-line-per-paragraph text is kept. Heading lines such as 第3章, Chapter 3, Capítulo 3, 제3장, บทที่ 3 split the text into chapters when at least two are found.
- **Long text** is cut into parts of about 12,000 characters ("Title (1)", "Title (2)"). The reader splits a whole row at once, and Android cannot read back a database row over about 2 MB. A paragraph over 2,500 characters is cut at a sentence end.
- **Language check:** if a book says it is in another of the 10 languages than your target, import stops and says which language to switch to. Pasted text and books with no language are taken as the target language.
- **Reading:** a book appears once in Saved with its chapter count; tap it for the contents page (route `book/{sourceId}`), then a chapter. In a chapter the top bar says "Chapter 3 of 24" and there are Previous / Next chapter buttons. Mined sentences from a book store the chapter number in `position`.
- Files: `reading/ImportText.kt` (HTML to text, chunking, text import, pure), `reading/EpubReader.kt` (pure), `reading/ImportRepository.kt`, `data/` (entity, DAO, migration), `ReadViewModel.kt`, `ReadScreens.kt` (`BookScreen`), `ToolSheets.kt` (`ImportSheet`), `Root.kt`.
- **Checked here:** the two pure files compile with kotlinc 2.0.21 and pass 24 checks on generated EPUB 3 (nav, ruby, percent-encoded paths, continuation files) and EPUB 2 (ncx, one huge chapter) books, plus text cases. The Android side (Room migration, Hilt, picker, screens) is not built, like the rest of Phase 2.
- **Known gaps:** importing the same file twice makes two copies (remove one); no "continue where you left off" for books; imported books do not sync (only the source row does); no cover image; no footnote handling; DRM books cannot be read; a heading-less .txt is cut into parts by size, not by chapter; very large books take several seconds and the sheet shows a progress bar only.

## 2A-9: Yomitan dictionary import
- **Settings > Dictionaries > Choose file**: pick a Yomitan / Yomichan dictionary `.zip` (as downloaded, do not unzip). It is imported for the language you are learning right now, so switch the target language first. The list below shows each dictionary with its language, entry count and an on/off switch and a remove button. The same dictionary (title + revision) cannot be imported twice for a language.
- **What is read:** `index.json`, `term_bank_N.json` (words and meanings) and `term_meta_bank_N.json` (frequency, pitch accent, IPA). Formats 1 to 3 are understood. Meanings in structured content (the rich format JMdict-style dictionaries use) are turned into plain text, one meaning per line; images, furigana (`<rt>`) and styling are dropped. Tag banks, kanji banks and media are skipped.
- **Where it shows (word popup):**
  - a block per dictionary with the entry's word, reading, tags and meanings (up to 6 entries per dictionary, each cut at 600 characters), above the Wiktionary and AI blocks;
  - chips next to the level tags: frequency per dictionary (the lowest number if a word has several, for example "JPDB 1234"), pitch accent as downstep positions (`Pitch たべる [2]`; `[0]` = flat) and IPA;
  - **Add card** pre-fills the meaning with the AI line, or with the first three meanings of the first imported entry when there is no AI line.
- **Lookup** is by the dictionary form first, then the tapped form (and lower-case versions), exact match on the headword. Dictionaries are shown in import order; within one, by the dictionary's own score.
- **Storage:** three new Room tables (`dictionaries`, `dict_terms`, `dict_meta`), migration 4 to 5, nothing existing is touched. The import runs in one transaction, so a failed or cancelled import leaves nothing behind. Local only, never synced. A big dictionary (JMdict has hundreds of thousands of entries) takes a minute or more and the database is busy while it runs; removing one also takes a few seconds.
- No new libraries: the zip is read with the JDK and the JSON with a small reader in `Yomitan.kt` (so it can be tested on a plain JVM).
- Files: `reading/Yomitan.kt` (pure), `reading/DictionaryRepository.kt`, `data/` (entities, `DictDao`, `MIGRATION_4_5`), `ui/DictionaryViewModel.kt`, `ui/ReadViewModel.kt` (`imported`), `ui/screens/ReadScreens.kt` (`ImportedBlock`, chips), `ui/screens/SettingsScreen.kt` (`DictionariesGroup`).
- **Checked here:** `Yomitan.kt` compiles with kotlinc 2.0.21 and passes 18 checks on generated dictionaries: formats 1 and 3, plain and structured meanings (bullets, furigana dropped, images skipped), frequency as a number, a display value and a per-reading record, pitch, IPA, batching of 7,000 rows, and clear errors for a missing `index.json`, no title, a kanji-only dictionary, broken JSON and a non-zip file. No real community dictionary was available here (the network is limited), so test with one of yours. The Android side (Room migration, Hilt, picker, popup, settings) is not built, like the rest of Phase 2.
- **Licences:** dictionaries are files you bring; each has its own licence (JMdict and CC-CEDICT are CC BY-SA, many frequency lists are not freely redistributable). Fluvour ships none and does not upload them.
- **Known gaps:**
  - Matching is on the exact headword. A dictionary entry written in kana only (a "uk" word) is not found from its kanji form unless the dictionary also lists it; a tapped kana form does not search readings. Deinflection rules (`rules`) are ignored because Fluvour already gets the dictionary form from the tokenizer.
  - No way to reorder dictionaries (import order only); no search or browse screen; a dictionary of another language is listed but only used when that language is the target.
  - The entry's tags show as in the dictionary (`v1 vt`), not expanded to their descriptions.
  - Pitch accent shows downstep numbers, not the drawn pitch line. Images inside entries are not shown. Kanji dictionaries are not supported.
  - The zip is read one file at a time: a single bank file over about 50 MB could run out of memory on an old phone (the standard banks are 10,000 entries each, a few MB).
  - If a zip stores its term banks before `index.json` and the dictionary is format 1, the meanings are read as format 3 (very rare).
  - The "already imported" check runs at the end, so importing a duplicate reads the whole file before it says so.

## AI keys in the app (added after 2A-9)
- **Settings > AI keys:** one entry per provider: **Gemini, Groq, Cerebras, Mistral, OpenRouter, Hugging Face**. Tap a provider to paste one or several keys (separated by new lines, spaces or commas), see each key masked with which one is used next and which hit a limit, remove keys (with a confirm), and type a model name if you do not want the default. The old placeholder row "AI today 14 of 50" is gone (it was a design mock-up, not a real counter).
- **Rotation inside a provider** is Momori's `GeminiKeyPool` / `GeminiProvider` rule (from Torbito's `gemini_keys.py`): fresh keys first with the newest added first, then limited keys with the oldest limit first. A quota error (429, 402, rate limit) stamps the key and tries the next; an invalid key (401, 403) is skipped without a stamp; a busy or missing model moves on to the next model (Gemini: 3.6 Flash, 3.5 Flash, 3.5 Flash-Lite), and a model that failed on every key rests for 90 seconds.
- **Rotation between providers:** text goes to Gemini first, then Groq, Cerebras, Mistral, OpenRouter, Hugging Face. A provider without a key is skipped; a provider that fails (all keys limited, rejected, busy) passes the request to the next. **Pictures use Hugging Face only** (FLUX.1-schnell): the free Gemini API does not generate images, so Gemini is text-only in the app. If the phone is offline the chain stops at once.
- **With at least one key saved for the job**, word explanations and card pictures go straight from the phone to the provider: no sign-in, no limit set by Fluvour. **With no key for that job**, they go through the ai-proxy as before (sign-in needed, daily cap). Without a Hugging Face key, pictures still go through the proxy (which has its own keys); with one, they are direct.
- Keys are stored in a private DataStore on the phone (`fluvour_ai_keys`), never synced, sent only to their own provider in the request header. Gemini keys saved before this change keep working.
- **Endpoints used** (chat is OpenAI-style): Groq `api.groq.com/openai/v1`, Cerebras `api.cerebras.ai/v1`, Mistral `api.mistral.ai/v1`, OpenRouter `openrouter.ai/api/v1`, Hugging Face `router.huggingface.co/v1`; Hugging Face pictures `router.huggingface.co/together/v1/images/generations` with `black-forest-labs/FLUX.1-schnell`, asking for base64. **Default model names are only a starting point (they change often); if one stops working, type the current name in that provider's Model field.**
- Backend: the proxy's flat cap of 200 calls a day per user can be changed with the secret `AI_DAILY_LIMIT` (0 = none). Needed only if you still use the proxy.
- Files: `ai/Providers.kt` (registry and answer parsers), `ai/GeminiClient.kt` (the rotating client; the file keeps its old name so it replaces the earlier version), `ai/GeminiKeyPool.kt`, `ai/GeminiModels.kt`, `ai/AiService.kt`, `ui/AiKeysViewModel.kt`, `LookupRepository.kt`, `ImageRepository.kt`, `SettingsScreen.kt` (`AiKeysGroup`).
- **Checked here:** the pure part compiles and passes 20 checks: rotation and stamping, invalid keys, all limited, all rejected, busy model fallthrough and rest, offline, other errors, 402 and rate-limit as quota, and the four answer parsers (Gemini text, chat text, base64 picture). **Not tested:** any real call to a provider (no keys or network access to them here) and the Android side. Hugging Face pictures and Cerebras / OpenRouter model names are the most likely to need a tweak on the first real try.
- **Picture style (editorial, matches the app):** every AI card picture is asked for as an *editorial spot illustration* in the look of the app's own art: layered flat gouache and cut-paper shapes with a fine stipple grain, matte, sparing ink linework, one clear subject (an abstract word becomes one everyday scene), square, centred on plain cream paper with generous margin. The colours are the five illustration inks of the card's language from `ArtPalettes` (Art.kt: il1 main, il2 soft, il3 warm, il4 deep ink, il5 paper), written as names plus hex (for Japanese: deep navy, terracotta, a lighter terracotta, cream), so a Japanese card picture sits beside the terracotta covers and a Thai one beside the moss ones. The prompt gives the meaning, not the foreign word, so the model has nothing to write on the picture, and it ends with a firm "no text, no letters, no logos, no photo realism, no 3D, no neon". Hugging Face renders it at 768 x 768. The same prompt is used on the proxy path, so the style does not change with the route. In the app the picture gets a hairline border in the theme's line colour (`CardImage`).
- Files: `ai/ImageStyle.kt` (pure: prompt and colour names), `ai/ImageRepository.kt` (`promptFor`), `ui/CardImage.kt`. Checked here: 10 checks on `ImageStyle` (colour names for the Japanese, Thai and English inks, prompt under the proxy's 1000-character limit, meaning in and foreign word out, all five inks present, no crash on bad hex). **Not checked:** how FLUX actually draws it. Look at a few real pictures and tune the wording in `ImageStyle.prompt` (it is the only place).
- Known: the paper is the art's cream, so in dark mode a picture shows as a light square with a hairline edge. Pictures made before this change keep the old plain style until regenerated.
- Not done: reordering providers or keys by hand, a per-provider on/off switch (remove the keys to turn one off), other kinds of AI (speech, translation), Hugging Face keys for anything but text and FLUX pictures, and picture providers other than Hugging Face.

## What is interim
- **Thai** still uses the platform word breaker (see 2A-3).
- **Dictionary is online and English only.** Wiktionary glosses are English; for a Thai UI the AI section answers in Thai. Offline dictionaries (JMdict, CC-CEDICT) arrive with step 3, Yomitan import with step 9, behind `DictionaryClient.lookup`.
- **Articles do not sync** (local only). They can be downloaded again from the saved link. `sources` rows are written locally but are not part of sync yet either.

## Known gaps
- The highlight of the tapped word is cleared only when you tap another paragraph or reopen; it is cosmetic.
- Very long articles are one list item per paragraph, which is fine, but a huge article takes a moment to download on a slow connection.
- Sentence ends are found by `. ! ? 。 ！ ？ … newline`, so "3.5" or abbreviations can cut a sentence early.
- Not compiled or run. The Room migration SQL was written by hand to match the entity; if Room complains about the schema on first launch, the table SQL in `AppDatabase.kt` is the place to look. First Gradle build is still the next real check (Phase 0 and Phase 1 code has not been built either).

- Inflected forms (e.g. Spanish "corriendo") usually show "present participle of correr" rather than a full meaning; the AI line covers the base form. Real lemmatising comes with the tokenizers.
- The AI explanation is not checked against the dictionary; treat it as a helper, and the dictionary as the reference.
- `DictionaryClient.parse` and `LookupRepository.answerText` are pure functions kept ready for unit tests; the app module has no test setup yet. Nothing here has been compiled.

## First test on the phone
000. (step 9) Settings > Dictionaries > Choose file: pick a Yomitan zip for the current target language (for example JMdict for Japanese, or a frequency list). It shows in the list with its entry count. Open a Read article and tap a word in it: the dictionary block and the frequency / pitch chips appear. Switch it off in Settings and tap again: they are gone. Remove it: the entries are deleted. Importing a file that is not a Yomitan zip shows a clear message and changes nothing.
00. (step 8) Read tab, tap +, Paste text: paste a few paragraphs, Import: it opens in the reader. Tap +, Book or file, pick an .epub: you land on its contents page, open a chapter, tap a word, use Next chapter. Pick a .txt of a web novel with 第1章 lines: it splits into chapters. Pick a book in another language than your target: you get a message naming the language to switch to. Remove the book from Saved: all its chapters go.
0b. (step 7) Open a Japanese article and tap 食べた or 読んで: chips show JLPT N5 and a frequency rank. Open an English article and tap "abandon": CEFR B1. A Spanish article shows the frequency chip only.
0. (steps 5 and 6) Open an article, tap a word, Add card, save. Open Review: that card shows the sentence with a blank. Flip: normal back. A card typed by hand (no sentence) still shows the word on the front. Answer a few cards until one graduates (Good, Good), then reopen an article containing it: the coverage line goes up.
1. Build and run, open the Read tab, tap Random (needs internet only, no sign-in).
2. Open an article, tap a word: the dictionary section and (when signed in) the AI section fill in; tap the speaker; tap Add card and check the meaning is pre-filled. Tap the same word again with airplane mode on: both sections come from the cache.
3. Open a Japanese article: tap a conjugated verb and check it shows the dictionary form and, for kanji, the reading. Open a Chinese article and check words are split sensibly.
4. Close the app, turn on airplane mode, reopen: the article is still in Saved articles.
5. Tap a word, tap Save sentence: the button changes to "Sentence saved", and the sentence appears under Mined sentences on the Read tab with the article title. Tap another word in the same sentence: it already shows "Sentence saved".
6. Tap Add card from a different sentence, save the card, then check Mined sentences: the sentence is there too. Sign in, sync, and check the `sentences` and `sources` tables in Supabase.


## Reader upgrade: saved position, pages, bookmarks, highlights

- **Saved position.** `reading_progress` (one row per text) stores paragraph number and character inside it, plus percent. Saved 500 ms after the scroll or page settles and again when the reader closes. Position is paragraph and character, not a pixel or page number, so it survives a change of font size or screen.
- **Layouts.** Reading options (the Aa button) switch between Scroll and Pages, and set page colour (App, Sepia, Night), text size and line spacing. All saved in settings. Pages are built by measuring each paragraph with the same text style the page draws with (`paginate` in `ReaderView.kt`), off the main thread, and rebuilt when size, spacing or screen change.
- **Bookmarks.** `bookmarks` table. The top-bar bookmark icon saves or removes the first position on screen; the list icon opens bookmarks and highlights.
- **Highlights.** `highlights` table, one paragraph per highlight (`startCh` to `endCh`), colour as an ARGB int, optional note. Press and hold a word, drag to extend, pick a colour. A long press on an existing highlight selects it so it can be recoloured, noted or removed. New highlights cut back any they overlap. Palette: `HighlightPalette` in `ReaderView.kt`.
- Migration `MIGRATION_5_6` (database version 6) adds the three tables. Local only for now; rows carry `userId` and soft-delete so they can sync later.
