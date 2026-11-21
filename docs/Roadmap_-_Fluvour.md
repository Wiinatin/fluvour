# Roadmap - Fluvour

**Fluvour** is an Android-first, open-source-based language learning ecosystem covering receptive skills (reading, listening), vocabulary retention, and productive skills (speaking, writing).

**Name meaning:** *flu-* (Latin *fluere*, "to flow", as in fluent) + *-vour* (as in flavour/savour). Every language has its own flavour, and you savour it while becoming fluent.
**Tagline ideas:** "Savour every language." / "Flow into the flavour." / "Taste it. Speak it."
**Name to-do:** check Play Store ("Fluvour" and "Fluvor"), register domain (fluvour.com / fluvour.app), create GitHub repo, trademark search, reserve social handles.
**Part of the family:** Fluvour sits alongside Fylospehere (lesson materials), Calco (money), Momori (journal) and Torbito (students, assignments, teaching income).

**Approach:** build from scratch (see the fork vs scratch decision record in Phase 2). Nibomo (FSRS spaced repetition, AI features) and Barbera (Flutter UI) are references to learn from, not bases to fork.

**Stack (decided):** native Android, **Kotlin + Jetpack Compose**. Reasons: the screen-OCR overlay, screen capture, audio recording and on-device OCR are all native Android work; Japanese tokenizers such as Sudachi and Kuromoji are JVM libraries and run directly; ML Kit is first-class; Android-only for now. Trade-off accepted: iOS later means a rewrite or Kotlin Multiplatform (check its status then). Keep the spaced repetition module free of Android and UI code so it can be ported to TypeScript for Torbito web in Phase 9.

---

## Guiding principles

- **Independent first, integrate last.** Phases 0 to 8 do not need to respect Torbito. Only Phase 9 aligns with it.

- **Every input becomes a card.** Anything read, watched, said or written can be mined into reviewable material.
- **Any target language.** Language choice drives articles, AI output, dictionaries and quizzes.
- **Real content only for reading.** Articles are sourced online (never AI-generated); AI is used for questions, explanations and feedback.
- **Two reading paths.** The in-app sourced reader (2A) is for content Fluvour provides. The screen-OCR overlay (2B) is separate and works over any app.
- **Personal vs student editions.** Only safe features go to students; the overlay and comic-source features stay personal.
- **Cache AI results.** Generate once per word or sentence, then store. Free tiers have rate limits.
- **Backend required.** API keys (Hugging Face, Gemini, YouTube) must live server-side, not in the app.

---

## UI and art status (read this before touching screens)

The full UI is ported to Compose (see docs/UI.md). Screens whose feature is not built yet use sample data (`ui/sample/SampleData.kt`) and carry a `TODO(name)` marker; find them with `grep -rn "TODO(" app/src`.

- **Real features:** review (FSRS, per-facet), Path sub-tab with lessons, drills and graduation test (see the Learning path section), reading and tap-to-lookup, cards and card editor, settings (language, UI language, theme, mode, goal, auto-play, account, Anki export), streaks.
- **Sample data only:** Quiz (Phase 3), Watch (Phase 4), Output speaking (Phase 5), Output writing (Phase 6), Progress and weekly review (Phase 8).
- **Art system:** each of the 10 languages has its own mark and ambient background. ja, th and en are drawn by hand in `Art.kt` and `Ambient.kt`. zh, ko, es, fr, de, vi and id come from `LangArt.kt`, a small runtime that draws scene data, plus 7 generated files, one per language, in `ui/art/lang/` (`ZhArt.kt` to `IdArt.kt`). **Do not hand-edit the generated files.** To change a design, edit its HTML board in `tools/langart/sources` and re-run the converter. `docs/LANG_ART.md` has the prompt for a Claude working on this.
- **Icons:** adaptive launcher icon (foreground, background, monochrome) plus legacy and Play Store versions.
- **Build status:** the language art and icons are compiled and installed on a device. Still open: a visual pass of each screen against the HTML boards, and separate per-language covers and Watch stages (the marks are reused for now). Only Home and Watch were designed per language; Cards, Read, Output and Profile were not.

---

## Phase 0 — Foundation (setup and decisions)

- [x] Skim Nibomo's FSRS and AI-feature design for ideas (reference only, license permitting) *(MIT licensed; notes in docs/NIBOMO_NOTES.md)*
- [x] Stack decided: Kotlin + Jetpack Compose (see header)
- [x] Project setup: Gradle, Compose, dependency injection, local database (Room or SQLDelight, Postgres-style schema with device-generated UUIDs and a `user_id` owner column on every table) *(Hilt + Room; written, first Android Studio build pending)*
- [x] Standalone spaced repetition module (pure Kotlin, no Android or UI imports) *(FSRS-6 with learning steps; matches ts-fsrs 5.4.2 on 3,377 generated reviews)*
- [ ] Set up backend (auth, database, secrets, AI proxy endpoints) *(separate free Supabase project `fluvour` in Singapore created and core schema applied; the ai-proxy Edge Function (Gemini text and image providers with key rotation and model fallback ported from Momori, plus Hugging Face with key rotation ported from Fylosphere, version 4) is deployed; creating your login and adding the Gemini key secret are still to do)*
- [x] Data model: languages, decks, cards, sentences, sources, review history, "versions" of speaking topics
- [x] Language selector (target language + UI language, e.g. English / Thai) *(10 target languages: ja, th, en, zh, ko, es, fr, de, vi, id. Each has its own accent, mark and ambient background; see "UI and art status" below)*
- [x] Basic app shell, theming, navigation *(default theme is now **Warm**, shown as "Fluvour", with accent and tint following the language being learned; Calm and Playful remain as extra themes; light/dark/system, where the dark palette is new and unreviewed; tabs are Home, Review, Read, Output, Watch, where Immerse became Watch)*

**Done when:** you can pick a language and create/edit cards on a phone.

---

## Phase 1 — Core vocabulary loop (MVP)

1. **Spaced repetition** (FSRS), daily review queue, due counts
2. **Card creation:** word, meaning, example, notes
3. **AI image per word** (Hugging Face FLUX.1-schnell first, rotating several tokens as in the Fylosphere app, with Gemini's image model as the fallback; both go through the ai-proxy), auto-generated when a word is added, cached on the device, with a regenerate button. Text-only when signed out or when every source is out of quota
4. **Text-to-speech** for words and sentences
5. **Streaks, daily goals, review heatmap**
6. **Offline review** (local storage + sync)
7. **Export to Anki** (builds trust, avoids lock-in)

**Done when:** you can use it daily as a better flashcard app.

---

## Phase 2 — Reading and tap-to-lookup

Phase 2 has two separate parts that share the same lookup engine (tokenizer, dictionary, popup, card creation). 2A is the in-app reader with sourced content. 2B is a standalone screen-OCR overlay. They are built and shipped independently.

### 2A — In-app sourced reader

1. **Article reader** with sourced content (Wikipedia, Wikinews, licensed or open sources; check licenses). Content is always real and sourced, never AI-generated *(Wikipedia search and random in the target language, saved for offline reading; written, not yet built; see docs/PHASE2.md)*
2. **Tap a word → popup meaning** (dictionary + AI context-aware explanation) *(tap, popup with speech and Add card written; Wiktionary meanings plus a cached AI explanation in the sentence's context written, not yet built; see docs/PHASE2.md)*
3. **Tokenization for no-space languages:** Japanese (Sudachi/MeCab + JMdict), Chinese (jieba + CC-CEDICT), Thai (word segmentation), Korean (word and particle splitting; Korean now has its own art and is not yet covered by any tokenizer, so a decision is needed). Spanish, French, German, Vietnamese and Indonesian use spaces and are mostly fine *(splitting written, not yet built: Kuromoji for Japanese with dictionary forms and readings, jieba for Chinese, platform ICU for Thai; JMdict / CC-CEDICT still to do; see docs/PHASE2.md)*
4. **One-tap sentence mining:** saves the full sentence, source, and position *(Save sentence button and card linking written, not yet built; sources and sentences sync; see docs/PHASE2.md)*
5. **Cloze cards** auto-generated from mined sentences *(written, not yet built: a card linked to a mined sentence is shown as fill-in-the-blank, inflected forms matched through the tokenizer; see docs/PHASE2.md)*
6. **Known-word coverage:** "you understand 82% of this article" *(written, not yet built: line under the article title, known = cards that graduated to REVIEW; see docs/PHASE2.md)*
7. **Level tags:** CEFR / JLPT / HSK / frequency rank *(written, not yet built: popup chips with JLPT for Japanese, HSK for Chinese, CEFR for English and a frequency rank for all 10 languages; the other languages have no exam tags yet; see docs/PHASE2.md and docs/LEVELS.md)*
8. **Book / EPUB and text import** (light novels, web novels, own files) with the same tap-to-lookup *(written, not yet built: paste text, .epub, .txt, .html; each chapter is saved as its own reader text with a contents page and next / previous; see docs/PHASE2.md)*
9. **Yomitan-format dictionary import** (many community dictionaries: meanings, frequency, pitch accent). Check each dictionary's license *(written, not yet built: Settings > Dictionaries imports a Yomitan zip per language; meanings, frequency and pitch accent show in the word popup; see docs/PHASE2.md)*
10. **Book library on the Read page** (covers, search, offline download, shelf, continue reading) *(written, not yet compiled or run on a phone; see docs/UI.md, "Read library")*
    - **Sources:** Project Gutenberg through the Gutendex JSON API (free, no key, language filter, cover and EPUB links), and the user's own EPUB files. Other catalogs (OPDS, Standard Ebooks, Wikisource, Aozora) are later. Pirate-site connectors are out and will not be built. PDF books, manga and OCR are a separate project.
    - **Flow:** search, open the detail page, Download (EPUB through the same importer as own files, so tap-to-lookup and sentence mining work unchanged), heart to keep on the shelf, Continue picks up in the chapter last read. Un-hearting never removes a downloaded book. Remove on the detail page deletes only the text and cover; cards and mined sentences stay.
    - **Storage:** local Room table `library_books` (database version 13, `MIGRATION_12_13`), covers saved in `filesDir/covers`. Only hearted or downloaded books get a row; search results are cached in memory only.
    - **Covers:** saved file, else the catalog image, else a generated cover (title and author on a block in the language colors). Own EPUBs use the cover inside the file when there is one.
    - **Limits:** free catalogs are mostly older public-domain books, and Thai and Japanese choices are thinner than English. If Gutendex is down, search shows a retry line and the page keeps the downloaded books. Gutendex terms and rate limits are still to be checked before heavy use.
    - **Checks without Android:** `tools/gutendextest`, `tools/librarytest`, `tools/epubcovertest` (plain JVM). Everything that touches Room, Compose or the network is untested until the Gradle compile.
11. **Articles tab: online articles matched to your level** *(written, not yet compiled or run on a phone; nothing tested against the live sites; see docs/UI.md, "Articles tab")*
    - **Sources (one small adapter each in `reading/articles/`, listed in `ArticleCatalog.all`):** Japanese: NHK News Web Easy and NHK News. English: Breaking News English, News in Levels, VOA Learning English, Simple English Wikipedia. Indonesian: BBC Indonesia and Detik. Thai: Thai PBS and BBC Thai. Wikipedia (ja, en, th, id) is the fallback for every language. Kompas and Tempo are not added because no working feed address was found. Paywall bypass, login scraping, pirate or leaked sites and scheduled crawling are out and will not be built.
    - **Use:** personal use only, not shared or published. Reuse terms of the news sources are unchecked. Each adapter can be dropped alone.
    - **Level measuring:** the app measures every article from its own words with the Watch clip method (`ClipMeasurer`, `Coverage`, `Levels`), never from a site label. Scales: ja JLPT N5 to N1, en CEFR A1 to C2, th and id six word-frequency bands shown as ~A1 to ~C2. Under 20 words, or without a level list, an article gets no level.
    - **Storage:** local Room table `article_levels` keyed by url (database version 14, `MIGRATION_13_14`), not synced. A level is stored when an article is opened or checked. A saved article is the usual `sources` plus `articles` rows, reused by url.
    - **Politeness:** one request at a time, 1.5 s between requests to the same site, an honest User-Agent, lists fetched when the tab opens and kept in memory, article text fetched only when opened. "Check levels" fetches up to 8 articles and keeps only their level.
    - **Limits:** a row has no level until it is opened or checked, because the level needs the text. Site layouts change, so any adapter can break; a failing source is skipped and named in a "Skipped" line. Thai has the thinnest choice, expect mostly Thai Wikipedia. Levels are estimates from word lists, and th and id levels are rough bands.
    - **Checks without Android:** `tools/articleleveltest`, `tools/articlesourcetest` (plain JVM, sample page shapes that are assumed, not taken from the live sites). Room, Hilt, Compose and all network calls are untested until the Gradle compile and a phone test.

**Done when:** you can read a real article, tap unknown words, and have them become cards in one tap.

### 2B — Screen-OCR overlay (separate, Yomitan-style for any app)

A floating overlay that works over any app (Mihon, YouTube, games, browsers), so comics and other image text can be mined without Fluvour owning any sourcing.

1. **Floating button + screen capture** (native Android module: overlay window and screen capture)
2. **OCR** of the captured area: ML Kit on device where the script is supported (Latin, Chinese, Japanese, Korean, Devanagari), manga-ocr for Japanese comics, Gemini vision as a flexible fallback (needs internet, has free-tier limits)
3. **Cache OCR results** per page or region so each page is only processed once
4. **Reuse 2A's tokenizer, dictionary and popup** for tap-to-lookup on the OCR'd text
5. **One-tap sentence mining** with source reference (app, series, chapter, page, or video and timestamp). Any screenshot crop stays on the device; only text syncs
6. **Handle limits gracefully:** some apps block screen capture, stylised fonts reduce accuracy, and vertical or long-strip layouts need region selection

**Done when:** you can read a comic in another app, tap a word on screen, and save a card with its sentence and source.

### Decision record: fork vs scratch

- **Decision:** build Fluvour from scratch, and use the overlay (2B) for comics instead of forking Mihon/Komikku.
- **Why:** most of Fluvour (spaced repetition, AI images, quizzes, speaking, journal, mining) is not in a comic reader, a fork brings a large codebase and upstream maintenance, and a small clean codebase is easier to build in staged chat sessions.
- **Revisit if:** after a few weeks of real use the overlay feels too clunky. A personal-only fork (Mihon family, Apache 2.0 as far as I know; verify) remains an option, built as a separate "personal edition" so student-facing builds exclude source extensions.
- **Student distribution:** only safe features go to students (vocabulary, spaced repetition, quizzes, speaking, sourced reading). The overlay and any comic-source features stay personal.

---

## Phase 3 — AI-driven quizzes

1. **Context quizzes:** read a short or long sourced paragraph containing your vocabulary, then pick the correct meaning of the word *in that context* (multiple choice)
2. **Quiz variety:** fill in the blank, listening choice, "which word fits?", translation choice
3. **i+1 filtering:** prefer sentences where you know all words but one
4. **Leech detection:** words you keep failing switch to a new angle (new image, mnemonic, new example sentence)
5. **Multiple example sentences per word** rotating across reviews
6. **Tatoeba integration** for extra example sentences with native audio

**UI status:** the Quiz screen is built with sample questions only; the real quiz engine is still to do. The leech sheet trigger is real (lapses of 6 or more) but its content is placeholder.

**Done when:** vocabulary is reinforced from many angles, not just front/back flashcards.

---

## Learning path (Genki) — facets, Path tab, lessons, graduation test

Built in chat steps 1 to 9 (written; Gradle build of steps 2 to 8 passed, device testing and step 9 compile still open). Decisions live in the handoff file, not here.

1. **Facets:** one card, several tracks. Table `card_facets` (kinds WORD, MEANING, CLIP), each with its own FSRS state; `review_log.facetId`. MEANING unlocks after 2 non-Again WORD reviews and a 3+ day interval (`FacetRules`). Room v15, Supabase `backend/migrations/001_card_facets.sql` (run by hand), facet sync in `SyncRepository`. `CardEntity` FSRS columns are a legacy copy.
2. **Study tab:** bottom tab Review is now Study. On Cards, a mode row (Mixed, Clip, Meaning, Word) with due badges. Mixed = due-time order across facets, new last, one facet per card per session. Mode is in memory, Mixed on start and on language switch.
3. **Curriculum:** `assets/curriculum/ja-genki1.tsv`, made by `tools/curriculum/build_genki1.py` (edit the script, never the TSV). Own capped units in book order, no book text. `CurriculumRepository.hasCurriculum(lang)` hides the Path sub-tab.
4. **Path sub-tab:** unit list (graduated, current, locked), unit detail, review-count nudge (never blocks). Room v16 `path_units` (local only).
5. **Lessons:** one AI request per unit, cached in `filesDir/lessons`, local validation (`LessonLogic`). 4 chunks plus 8 drills. Misses are written to the card notes.
6. **Unit check:** up to 30 questions (`TestRules`: 1.4 per word, 10 to 30), about 83% to pass, fresh on every retake. The app picks the unit's words and about 30% older words from FSRS state; the AI only writes the questions (`TestLogic`) and may use only words the learner knows (unit and earlier-unit words, cards at 14+ days, Heisig kanji at 20+ days, names of people), checked with Kuromoji (`VocabCheck`). Unit 1 has no check: it graduates when its drills are finished, and Unit 2's check covers both. Flag button (up to 3), misses sent to review.
7. **Typed grading:** AI judges typed answers (`GradeLogic`). Drills grade in the background; the test grades all typed answers in one request; offline drills use the local check and a queue (`filesDir/grading/queue.tsv`).

**Open:** device testing; Genki I fit check (`ja-genki1.tsv` now 87 units, ids changed in the book-order rebuild); typed answers in Cards reviews do not exist; extras (warm-up, images, unit story, speak-it, kanji hints, listening questions in the test); Genki II and Tobira; reading quiz mode (independent).

---

## Phase 4 — Listening and video

1. **In-app video player (YouTube content, Fluvour look)** that plays YouTube videos like local videos in Fluvour's own interface, with subtitle display and tap-to-lookup (see the decision record below)
2. **Clip recommendations (4.2):** Gemini writes search phrases that fit your level and learned words, NewPipe search finds *real* videos (no YouTube Data API), and each clip is measured from its real subtitles, so nothing is hallucinated. Status: Phase 1 built and compiled. Phase 2, a background-grown local clip library, is written but not yet compiled or run on a phone (see the decision record below)
3. **Video clip cards:** save the 3–5 seconds around a mined sentence
4. **Speed control and A-B loop** on audio and video
5. **Dictation mode:** hear a sentence, type it
6. **Subtitle import** (your own .srt files from shows/anime) for lookup and mining
7. **Shadowing mode:** repeat a clip, get pronunciation feedback

**UI status:** the Watch screen (watch, dictate, transcript, say it back) is built with sample data and a simulated playhead. No real video, subtitles or mining yet. Clip recommendations (item 2) add one quiet row to the idle Watch page and a clips view, see `docs/UI.md`.

**Done when:** you can watch a video inside the app and mine it as easily as an article.

### Decision record: video player

- **Decision:** videos come from YouTube but play like local videos, as NewPipe does, inside a player built to match Fluvour's own interface. It must not look like YouTube.
- **Approach:** use NewPipeExtractor as a library (stream URLs and subtitle tracks) and play them in our own Media3/ExoPlayer screen. Do not fork the NewPipe app: it is a large codebase and would bring its YouTube-style UI with it (same reasoning as the Mihon decision above).
- **Why this is allowed:** Fluvour is for personal learning only and is not distributed. NewPipeExtractor is GPLv3, whose conditions apply on distribution, and Play Store policy is not a concern for a private build. Costs accepted: the extractor needs updating when YouTube changes, and it goes against YouTube's terms, so this stays out of anything shared.
- **Why not the official embedded player:** it cannot do tap-to-lookup on the real subtitle text, our own A-B loop and speed controls, 3 to 5 second clip cards, or background audio.
- **Layout (portrait):** the Watch screen as built has an art banner on top, segmented progress, captions with an accent-coloured underlined word, Watch and Dictate tabs, and a say-it-back flow. The real video goes in a rounded 16:9 frame on the off-white page, not a black edge-to-edge block, with a live transcript sheet below it, the current line highlighted in the language accent and every word tappable. **Open decision:** when the player is built, does the real video replace the art banner, or does the banner stay as a fallback (no video, audio-only, loading)?
- **Controls:** waveform scrubber with A-B loop markers, a big play button in the language accent, speed and subtitle-language buttons, and a "mine this line" button that saves the sentence, source link and timestamp (optionally the clip).
- **Not like YouTube:** no red, no comments, no recommendations, no thumbs. Fluvour's own chips instead, such as a level tag and "78% known". The one exception is the clip list of item 2, which was asked for on purpose: it is level and vocabulary based, has no YouTube-style chrome, and is not an engagement feed.
- **Landscape:** subtitles overlay the video. Tapping a word pauses it and opens the same lookup bottom sheet as the reader.
- **Mini-player:** a floating button keeps audio playing while you switch tabs.
- **Architecture:** the player screen talks only to a small `MediaSource` interface. The YouTube extractor is one implementation; local files and audio are others. If the extractor breaks or the approach changes, the player design does not.
- **Personal app only:** this player approach is for the personal Android app. The student version lives on the Torbito web (Phase 9), where NewPipeExtractor cannot run, so web video needs a different approach, decided in Phase 9.

### Decision record: clip recommendations (4.2)

- **Goal:** recommend short real clips that fit the user's level and the words they have learned, and tag each with a level and two "how much do I know" numbers. The user never types a search.
- **Pipeline:** one Gemini call writes search phrases (language, declared level, up to 30 learned words in, JSON out, no URLs or titles). NewPipe search finds videos for each phrase. A cheap cut keeps clips of 30 s to 5 min that are not in the watch history. Subtitles of the survivors are read, de-duplicated (auto captions repeat lines in a rolling window), counted, and measured. Every measured video is saved in the Room table `analyzed_videos` (word counts as JSON, level, token total, unique count), so the numbers can be recomputed as the user learns words, without new downloads.
- **Two numbers per clip:** "<your level> knows N%" (running words at or below the declared level) and "you know M%" (the same, plus words of the user's graduated cards). English, Japanese and Chinese use exam level codes; every other language, Thai included, uses approximate bands of the frequency rank, and its numbers are shown with `~`. English has no lemmatizer, so its numbers are also `~`.
- **Ranking:** normal levels aim for 90% "you know" inside an 85 to 95% band. At the top two levels of a scale percentages saturate, so clips with at least 85% "you know" are ordered by new words per minute. Constants live in `ClipRanking` and `ClipPlan`.
- **Level per language:** set in the profile sheet, stored in settings. Clip count (1 to 30, default 6) is a Settings stepper. Result cache 24 h in SharedPreferences, cleared by the reload button or by changing language, level or count.
- **Free only:** the only external call is Gemini, through the keys already in the app. If the quota is out the Watch row hides and nothing else is tried. Filmot and the YouTube Data API were rejected (paid or key-bound).
- **Same personal-use basis as the player:** it uses NewPipe's unofficial interface, so it stays out of anything shared.
- **Phase 2 (written, not yet compiled or run on a phone): a local clip library grown in the background.**
  - **Why:** no public index matches subtitles to a learner's words (YouGlish runs its own crawl; Filmot is a paid API and was rejected). So the phone builds a small personal index: thousands of clips, not all of YouTube.
  - **How videos are found:** the related videos that NewPipe returns next to a clip. `ExtractedMedia.related` (extractor API version 3) fills from the video page that is opened anyway, so it costs no extra request. Only clips that fit the learner (85 to 95% "you know", or at least 85% at the top two levels) seed the queue: up to 8 related videos of 30 s to 5 min each, skipping videos already in the library. Both the clips list and the background job seed. Channel listing was left out (it needs its own API bump).
  - **Queue and job:** table `index_queue` (database version 10). `ClipIndexWorker` (WorkManager) runs every 3 h, only while charging and on an unmetered network, for the current learning language: one video at a time, a random 3 to 8 s pause, at most 100 videos a day, 8 minutes per run, and it stops after 5 failures in a row (WorkManager backs off). A link that fails 3 times is dropped. The measuring code is shared with the clips list (`ClipIndexer`).
  - **Library:** same `analyzed_videos` table. A video without subtitles is kept as a marker row so it is never read again. Cap 5,000 usable clips (pruned to 4,500: clips outside the level prefilter first, never-shown and oldest first), markers capped at 20,000. A clip that is gone (private, removed, locked, no stream) is marked unavailable when it fails to open from the list; network errors do not mark it.
  - **Recommending:** the clips list first shows a random sample of library rows near the learner's level (one level below to one above), ranked with the same code as Phase 1, with fresh Gemini search results added and ranked together at the end. Percentages are recomputed from stored word counts, so tags follow the learner's words with no new downloads. Clips shown recently are left out of the pool for 3 days so reload gives different ones.
  - **Settings:** `Grow clip library in background` (default on) and `Library: N clips analyzed`.
  - **Extractor update:** the version bump to 3 means the app ignores version 2 plugins until the "Extractor plugin" workflow has published a version 3 plugin; run it once by hand after pushing.
  - **Risk:** bulk background reading is a heavier use of the unofficial YouTube interface than occasional search. It can be throttled or break, and it goes against YouTube's terms. Personal use only, never in anything shared. Hence slow, capped, off a metered connection, and optional.
  - **Numbers are guesses** (daily cap, library cap, delays, pool size), all in `IndexPlan` and `ClipPlan`; tune after measuring on a phone. Handoff and step notes: `fluvour-4-2-phase2-handoff.md`.

---

## Phase 5 — Speaking ("Language Lord" practice)

1. **Pick a topic**, record yourself speaking in the target language
2. **Substitution allowed:** say English/Thai words where you don't know the target word
3. **Speech-to-text** (e.g. Whisper) → AI rewrites it naturally and flags substituted words
4. **Unknown words become flashcards automatically**
5. **Version history:** save v1 of each topic (what you said, improved version, new words)
6. **Record v2 later** on the same topic; compare fluency and vocabulary growth
7. **AI conversation partner:** voice roleplay (ordering food, interviews, etc.)
8. **Pronunciation, pitch accent and tone feedback** (important for Japanese, Chinese, Thai, Vietnamese)
9. **Minimal-pair drills**

**UI status:** the Output screens (calls, topics) are built with sample data. Recording, speech-to-text, AI rewriting and version history are still to do.

**Done when:** you can speak on a topic, learn from the corrections, and measurably improve on a retry.

---

## Phase 6 — Writing and handwriting

1. **Paper handwriting + scan:** app gives a prompt, you write on paper, take a photo in the app
2. **Gemini vision** transcribes and checks spelling, characters and grammar (Google ML Kit as offline fallback where the script is supported)
3. **Camera guide frame, auto-crop, retake hints**; one item per line for accuracy
4. **Printable practice sheets** (PDF grids, e.g. genkouyoushi for kanji)
5. **Stroke-order animations** shown before practice (photos can't verify stroke order)
6. **Daily journal with AI corrections:** explains each mistake, mistakes become cards
7. **Grammar patterns as cards** (reviewable like vocabulary)
8. **Heisig finger-writing pad** *(written, not yet compiled or run on a phone; see docs/UI.md, "Heisig writing pad")*
    - On the Heisig review card you see the keyword, write the kanji from memory with your finger on a pad, tap Show answer, and see your drawing next to the correct kanji. A switch lays the answer over your strokes as a ghost.
    - Extras: dashed guide grid toggle, numbered stroke-order animation (KanjiVG, tap to replay), Heisig story on the back, and the four ratings relabelled Wrong / Close / Good / Perfect.
    - **Scheduling:** unchanged. The labels map to the same four `Rating` values (Again, Hard, Good, Easy), so there is no second scheduler.
    - **Drawings are temporary:** never saved or synced, and no database change.
    - **Left out:** automatic handwriting recognition (ML Kit Digital Ink) as a verdict, because finger scribbles are unreliable; saving a history of drawings.
    - **Checks without Android:** `tools/padtest` (plain JVM) covers the stroke model. The pad, the screen and the stroke loading are untested until the Gradle compile.

9. **Heisig practice pages** *(written, not yet compiled or run on a phone; see docs/UI.md, "Heisig practice pages")*
    - A place to practise writing any Heisig kanji as often as you like: on the Heisig tab, right under Study, a search box and a grid of the whole deck. Tap a kanji for a full-screen page with a large pad, a dimmed copy to trace (switch), the Heisig story (switch) and a Submit button that clears the pad. A `Practice writing` button on the back of the review card opens the same page.
    - **Maturity tiers:** kanji you have studied are framed by how well you know them (Copper, Silver, Gold, Jade, Radiant), from your Study ratings only. Gold and up move. The same colours tint kanji in reading and watch text, with one Settings switch.
    - A small coral dot marks kanji due today.
    - **Nothing is saved:** drawings and Submit counts are temporary, and there is no database change. Only the two switches and the colour setting are remembered.
    - **Left out:** saving drawings, history, scores, handwriting recognition.
    - **Checks without Android:** `tools/practicetest` (plain JVM) covers the search filter and the tier rules. Everything on screen is untested until the Gradle compile.

**UI status:** the journal, handwriting and stroke-order screens are built with sample data. Camera capture, Gemini vision checking and AI corrections are still to do.

**Done when:** writing practice feeds back into your review queue.

---

## Phase 7 — Immersion everywhere

1. **Android share-sheet capture:** share text from any app (browser, Reddit, etc.) to mine it
2. **Photo mining:** snap a menu, sign or manga page; Gemini vision extracts sentences
3. **Browser extension** for tap-to-lookup on any site (later)
4. **Home-screen widget / lock-screen micro-reviews**
5. **Word families and etymology** (one new word unlocks related words)
6. **Duplicate / near-duplicate sentence detection**
7. **Visual novel / book mode** with tap-to-lookup for any language

---

## Phase 8 — Insights and polish

1. **Skill dashboard:** reading, listening, speaking, writing shown separately
2. **Mining stats:** which sources (article, video, speaking) give the most useful cards
3. **Weekly AI review:** progress summary and suggested focus
4. **"First met this word in..."** memory links back to the original source
5. **Offline packs:** dictionaries, articles, decks
6. **Onboarding, settings, accessibility, performance tuning** *(partly in place: Illustrations off and Reduce motion switches exist but reset on restart, so persisting them is still to do; onboarding saves language and goal but level is not stored and it does not launch on first run yet; High contrast is shown in Settings with no effect yet)*
7. **Optional later:** community / language exchange

**UI status:** the Progress and weekly review screens exist with placeholder numbers. Real today: due count, streak, week, heatmap and words known on Home.

---

## Phase 9 — Torbito integration (students and classes)

**Approach:** Fluvour is built independently in Phases 0 to 8 (its own schema, its own spaced repetition, its own design). Only in Phase 9 does the Torbito web get rebuilt from the finished Fluvour system. The only students on the Torbito website today are two test users (you and your partner), so there is no real student progress to protect and Torbito's current Leitner scheduler can simply be replaced.

**What Phase 9 needs to know about Torbito today:**
- Torbito Desktop (Python + pywebview, local SQLite, sync outbox) and Torbito Web (Next.js student PWA) share one Supabase project.
- Two student types: private students (`students`, linked by `student_auth_id`) and classroom students (QR join code, then `student_signups`, then teacher approval).
- Students never touch tables directly. They call `SECURITY DEFINER` functions that work out who they are from `auth.uid()`. Teacher-side views are on-demand `SECURITY INVOKER` functions.
- Multi-teacher isolation is in place (`teacher_id` with default `auth.uid()` on `students`, `classroom_vocab`, `student_signups`).
- Student-facing tables are web-only (no desktop SQLite pair), so there is no "install the desktop update first" sync trap. Teacher-facing tables need a paired SQLite migration.
- Torbito's current scheduler is Leitner-style doubling (cap 30 days, mastered at 14 days or more). Replace it with Fluvour's scheduler.

**Steps:**
1. **Handoff spec + `schema.sql`** written in Torbito's stage-file format (migrations first, naming registry, grep anchors) so the Torbito chat can rebuild Fluvour's features on the web
2. **New web-only tables** (cards, reviews, mined sentences, speaking versions) with an owner column, following the existing function-based access pattern
3. **Replace the web scheduler** with Fluvour's spaced repetition module, and migrate any existing `student_vocab` / `classroom_vocab_progress` data (only test users, so this can be simple)
4. **Events table** (reviews done, time spent, quiz scores, speaking attempts), all text, plus an on-demand teacher function so Torbito desktop can show what each student did
5. **Row Level Security:** students see only their own data; the teacher sees their own classes
6. **Class and assignment hooks:** assign decks, readings, quizzes and speaking topics to a class from Torbito
7. **Server-side AI calls** (e.g. Supabase Edge Functions) using your own Gemini key, which stays server-side, with a per-student daily limit so students need no setup. The limit is dynamic: a daily budget (the combined free-tier quota of about three Gemini keys, with a safety margin) is divided by the number of recently active students, with a minimum and a maximum per student, so 3 active students each get much more than 50 would. The Edge Function picks the key with the most quota left and moves to the next on a rate-limit error. Cached results do not count against a student. A student who wants more than the limit can add their own Gemini key (optional). Where that key is stored (browser only vs encrypted in Supabase) is decided in this phase
8. **Text-only storage rules:** store image prompts/URLs (not images), transcripts and corrections (not audio), transcriptions (not handwriting photos), links and timestamps (not video); any file storage lives elsewhere
9. **Tighten `curriculum_items` RLS** (migration `0010`): it currently uses the broad `auth.role() = 'authenticated'` policy, which would let any logged-in student read and write it. Scope it to `is_teacher()` plus ownership, and verify the migration number against the live project
10. **Check Supabase free-tier terms** (size limits, project pausing) and student data privacy, especially for minors

**Habits to keep in Phases 0 to 8 so this phase is easy:**
- Postgres-style schema from the start, even if Fluvour begins local
- Device-generated UUID ids
- Spaced repetition as a standalone module with no database or UI code inside
- An owner column (`user_id`) on every table
- All AI calls behind one thin layer, so API keys can later move server-side
- Text-only storage

**Done when:** a student can practise on the Torbito web, and you can see their progress from Torbito desktop.

---

## Technical notes and risks

| Area | Note |
|---|---|
| AI images | Hugging Face's own docs list under $0.10 of free credits per account each month, which is why one token runs out quickly; rotating several tokens (the Fylosphere pool) is what makes it workable, and a 402 or 429 moves to the next token. Gemini's image model is the fallback (its free image availability is not officially confirmed). Generate once per word, cache, one request at a time. |
| Gemini + YouTube | Never trust Gemini to name videos. Use it to generate queries; YouTube Data API supplies real results. |
| Video playback | NewPipeExtractor (library, GPLv3) + Media3/ExoPlayer, personal use only. Needs updating when YouTube changes; against YouTube's terms. |
| Article sourcing | Open licenses first (Wikipedia, Wikinews, Tatoeba). The Articles tab also reads learner and news sites (NHK, VOA, Breaking News English, News in Levels, BBC, Detik, Thai PBS) for personal use only; their terms are unchecked, so the app must not be shared with them as sources. One small adapter per site, so any can be removed. |
| CJK / Thai lookup | Needs a tokenizer plus a dictionary. Hardest technical piece; start with one language (e.g. Japanese). |
| Speaking | Whisper (or similar) for transcription, LLM for rewriting; store audio + text per version. |
| Handwriting | Gemini vision first, ML Kit offline fallback. Thai support in ML Kit is uncertain, so verify. |
| AI costs and limits | Cache everything, add per-user rate limits, degrade gracefully when quotas run out. |
| Licenses | Check Nibomo's license and every dictionary / dataset license before shipping. |

---

## Suggested build order (if time is short)

1. Phase 0 + 1 (flashcards + images)
2. Phase 2 (reading + tap-to-lookup + sentence mining)
3. Phase 5 (speaking), your most unique feature
4. Phase 3 (quizzes), then Phase 4 (video)
5. Phase 6 onward as polish and expansion

## Highest-return picks

Sentence mining with clips, cloze + auto variants, i+1 filtering, known-word coverage, leech detection, and the AI conversation partner.
