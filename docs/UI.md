# Fluvour UI patch (from Fluvour-UI Finalized.html)

The finalized HTML design is ported to Compose. Screens whose features do not exist yet are built as UI with sample data
(`ui/sample/SampleData.kt`) and carry a visible "placeholder" note or a `TODO(...)` comment where the real feature plugs in.
Nothing here has been compiled or run on a device: the sandbox that made this patch has no Android SDK. Expect a few compile
fixes on the first Gradle sync, then a visual pass against the HTML.

## What changed
- Theme: new `Warm` ThemeSpec (id `Warm`, label "Fluvour"), now the default for new installs. Existing users keep their stored theme.
  Accent and tint follow the language being learned (`LanguagePalettes`: ja/zh/ko terracotta, th moss, en and the rest slate blue).
  The design is light only; the dark palette is new and unreviewed.
- Fonts (`res/font`): Figtree, Newsreader (+ italic), Noto Serif Thai, Noto Sans Thai Looped. Japanese uses the system CJK serif
  because Noto Serif JP is 13 MB. Add it to `FluvourFonts` if you want the exact design.
- Kit (`ui/Components.kt`): FCard, FButton, FRow, Tag, UnderlineTabs, switches, steppers, FSheet, fields, rings, wave, CoverArt (now the real illustrations).
- Navigation (`ui/Root.kt`): Home, Study (was Review), Read, Output, Watch. Immerse became Watch. Old route `review` is gone (Review is the tab).

## Screen map
| Design | Compose | State |
|---|---|---|
| Home, language sheet, profile sheet | HomeScreen, ProgressScreens | Real: due count, streak, week, heatmap, words known, level per language. Placeholder: Read/Output/Watch done flags, skill bars |
| Review (flip, swipe, ratings) | ReviewScreen | Real (FSRS). Missing: cloze, reading line, "first met in" |
| Leech "new angle" | ToolSheets.LeechSheet | Trigger is real (lapses >= 6), content placeholder |
| Browse, card editor | Screens.kt, CardEditorScreen | Real. Missing: reading field, AI image toggle |
| Quiz | QuizScreens | Sample questions |
| Read (Books / Articles / Mined), reader, word sheet | ReadScreens, BookCovers | Real, not yet compiled. Books tab is the library (see below). Added: Add-to-Read sheet (UI), Aa sheet (size works, reading aid not stored) |
| Book detail | BookDetailScreen, BookDetailViewModel | Real, not yet compiled. Route `bookdetail/{libraryId}` |
| Output: calls, topics | SpeakScreens | Sample |
| Journal, handwriting, stroke order | WriteScreens | Sample |
| Watch, dictate, transcript, say it back | WatchScreen | Sample, simulated playhead. Real: the clips row and clips view (see below). The Recent list is kept per language |
| Settings | SettingsScreen | Real: language, UI language, theme, mode, goal, auto-play, suggested clips, clip library switch and count, account, Anki. Rest in-memory |
| Onboarding | OnboardingScreen | Language and goal save. Level not stored. Not launched on first run yet |
| Progress, weekly review | ProgressScreens | Placeholder numbers |
| Screen lookup setup, widget preview | ToolSheets | UI only |

## Clip recommendations on Watch (roadmap 4.2)
No new tab, screen or route. Files: `WatchScreen.kt` (row, clips view), `SuggestViewModel.kt` (state), `ProgressScreens.kt` (`LevelSheet`), `SettingsScreen.kt`.
- **Row:** one muted line above Recent on the idle Watch page and on the search page over a loaded video: `Clips for 遅刻, 締め切り and 13 more ›`
  (one word `Clips for a`, two `Clips for a and b`, none `Clips for you`). With no level set it reads `Set your level to get clips ›` and opens the
  level picker. It is hidden, with no error text and no spinner, when Gemini fails or the language has fewer than 5 cards. It never starts a search.
- **Clips view:** tapping the row swaps the page body, like the search mode. Header `← Clips for you ↻`; system back and the arrow return to the page
  under it, `↻` gets new clips. Each clip is a normal video card (thumbnail with length, title, channel and length) plus a muted tag line
  `C1 · C2 knows 98% · you know 94%` (`N2 · N4 knows 31% · you know 24%` for Japanese). A leading `~` marks approximate numbers.
  While reading: `Reading subtitles, 6 of 12` (`Looking for clips...` before that). The list grows one batch at a time; a partly failed run
  leaves `Some clips could not be loaded.` Tapping a clip opens it with the normal player.
- **Profile sheet:** a `ValueRow` `<Language> level` (current language) opens `LevelSheet`: English A1 to C2, Japanese N5 to N1, Chinese HSK 1 to 7-9,
  other languages approximate CEFR-style bands.
- **Settings:** a `Watch` group with three quiet rows (`SettingsRepository`, `SettingsScreen.kt`, `ClipLibraryViewModel.kt`):
  ```
  Suggested clips                       [ - 6 + ]
  Grow clip library in background          [on]      (sub: Only while charging and on Wi-Fi)
  Library: 1,240 clips analyzed
  ```
  The switch is on by default. The count is the clips of the current learning language that can be recommended.
- **Library (Phase 2, no new screen):** the clips view looks the same. It fills at once from the local library (a sample near the user's level),
  then fresh search results join, and `↻` brings different clips (clips shown in the last 3 days are left out). The library grows in the
  background only while charging on Wi-Fi. A clip that is gone stops being offered. See the decision record in the roadmap.

## Read library (roadmap 2A item 10)
- **Read page** (`ReadScreen`): tabs Books, Articles, Mined. Mined holds the sentences that used to fill the page.
  - **Books:** search box (Gutendex), chips `All`, `Gutenberg`, `My files`. Rows: Continue reading (covers with a progress line), Shelf (covers),
    My files (cover, author, heart), then `Popular on Gutenberg` or `Results` (cover, author, Offline note, heart). `More books` loads the next page,
    `Try again` shows when the catalog fails. The `+` for a book, file or pasted text is unchanged.
  - **Articles:** Wikipedia search, Random, Articles online, Saved (see "Articles tab" below).
- **Cover** (`BookCover`): saved file, else the catalog image through Coil, else a generated cover from the language palette. The generated cover is
  always drawn under the image, so a slow or failed image never leaves a hole.
- **Heart:** `HeartButton` (WatchScreen.kt, now `internal`, takes a `what` label) is shared by Watch and Read.
- **Book detail** (`BookDetailScreen`): cover 124x184, title, author, tag pills, `Download` (or `Read` / `Continue` once downloaded), Contents,
  `Remove` with a confirm, heart in the top bar, progress line with percent, About. Own files show `Your file` and use ids like `file:<sourceId>`.
  `Read` opens the first chapter, `Continue` the chapter last read. Continue reading rows and own files can still open the reader directly.
- **Removing (no hint lines):** `SwipeToRemove` (`ui/screens/SwipeRemove.kt`) is the Watch history swipe: left, bin icon, the row springs back and a confirm opens.
  Used on own files, on downloaded Gutenberg rows (not-downloaded rows do not swipe), and on Output topics (confirm names the recording count; the
  recording files are deleted too, cards stay). Long-press a Continue reading or Shelf cover for `Remove from shelf` and `Remove download` / `Delete file`.
- **Copy:** short plain sentences, no em dashes.
- **Data:** `LibraryRepository`, `BookDownloader`, `GutendexClient`, `EpubCover` under `reading/`; table `library_books` (see roadmap 2A item 10).
- **Not checked:** the screens have not been compared with the approved mockup file (https://claude.ai/artifact/QJgVtdBUznk4YhD6NrSc6H), and nothing here has been compiled or run.
- **Not built:** other catalogs (OPDS, Standard Ebooks, Wikisource, Aozora). PDF books and manga are a separate project.

## Articles tab (roadmap 2A item 11)
- **Header:** search box and `Random` (Wikipedia, as before). When a level is set on the Watch tab, two chips follow: `Near my level` (default) and `All levels`.
- **Articles online:** one row per article from the sources of the target language. Title, then the source name (and `Saved` if a copy exists), then the level
  label on the right (`N3`, `B2`, or `~B2` for Thai and Indonesian) once it is known. Tap opens the existing reader.
  - `Near my level` keeps rows within one level of the declared level, plus rows with no level yet. Known levels sort nearest first.
  - Without a declared level the chips are hidden and a line says to set it on the Watch tab.
  - Under the list: `Check levels` (measures up to 8 rows without a level, one after another, saves only the level) and `Refresh` (asks the sites again).
  - A source that fails is skipped and named in a `Skipped: ...` line. With nothing to show, a short line and `Try again`. A progress bar shows while listing or checking.
  - Opening shows a spinner on the row. If the download fails, one line says so and nothing is saved. A saved copy opens at once, without downloading.
- **Saved:** unchanged. Articles from any source are kept for offline reading.
- **Reader:** the line under the title names the source and license: `Wikipedia · CC BY-SA 4.0 (Wikipedia)` for Wikipedia, the stored license line for other
  sites (for example `NHK News (personal use)`). The top bar says `Wikipedia` or `Article`.
- **Copy:** short plain sentences, no em dashes.
- **Data:** `reading/articles/` (`ArticleCatalog`, one `*Source.kt` per site, `ArticleLevel`, `ArticleLevelStore`, `PoliteHttp`), `ArticleRepository.saveFetched` and
  `savedIds`, state in `ReadViewModel` (`list`, `loadArticles`, `openArticle`, `checkLevels`), table `article_levels` (see roadmap 2A item 11).
- **Not checked:** nothing here has been compiled or run, and no source has been tested against its live site. The row layout has not been compared with a mockup.

## Heisig writing pad (roadmap 6 item 8)
- **Front of the card** (`HeisigStudyScreen`): `Frame N`, the keyword in serif 38sp, one hint line, then `WritingPad` (`ui/screens/WritingPad.kt`).
  The pad is a square, 280dp by default (smaller on short screens, 160dp minimum), 16dp corners, a darker fill than the card (`page`), a `line` hairline,
  and `ink` strokes about 7dp wide, rounded and lightly smoothed. Under it: `Undo`, `Grid on` / `Grid off` (accent outline when on), `Clear`, all at least 44dp high.
  `Show answer` stays the full-width coral button.
- **Touch:** the pad takes the first finger only and consumes its touches. The card is no longer tappable, so only `Show answer` reveals.
- **Back of the card:** two equal squares, `YOU` (`PadPreview`, the saved drawing with the grid) and `ANSWER` (the kanji, large). Then the switch
  `Show answer over my drawing` (off by default; the ghost is the kanji in the accent colour behind your strokes). Then the stroke-order box
  (`StrokeOrderView`, 96dp, tap to replay) with `Stroke order`, `Tap to replay` and the stroke count; kanji with no stroke data have no box.
  Then Components and Story. The card scrolls if the story is long. Ratings: `Wrong`, `Close`, `Good` (emphasised), `Perfect`, each with its interval.
- **State:** the drawing and the overlay switch live in `HeisigStudyScreen` (`remember(card.ch, ui.answered)`), not in `HeisigStudyUi`, so they survive
  front to back and start clean on the next card. The grid setting is kept across cards. Nothing is saved.
- **Haptics:** a soft tick when the answer is shown.
- **Data:** `reading/PadStrokes.kt` (`PadDrawing`, `PadMath`), `StrokeRepository` (KanjiVG), `HeisigStudyViewModel.strokes(ch)`.
- **Copy:** short plain sentences, no em dashes.
- **Not checked:** the screens have not been compared with the approved mockups (`heisig-pad-draw.png`, `heisig-pad-compare.png`) on a phone, and nothing
  here has been compiled or run. The KanjiVG credit is shown on the Kanji tab but not on this screen.

## Heisig practice pages (roadmap 6 item 9)
- **Heisig tab** (`HeisigTab`): stats, the `Study` button, then a search box ("Search kanji to practise") and a 4-column grid of every Heisig kanji that fills the rest
  of the screen and scrolls on its own. Search filters as you type (keyword, the kanji, or frame number, `HeisigSearch`); "N matches" or "No match" shows while searching.
  The search text and scroll position are kept when you come back from a practice page. Tile: 84dp high, kanji 30sp over the keyword, 8dp gaps (`TierTile`).
- **Practice page** (`HeisigPracticeScreen`, full screen): bar with close X, `Frame N` (plus the tier name, e.g. `Frame 6 · Gold`) and previous / next arrows that walk the
  grid's filtered list; two pill switches `Trace guide` and `Story` (36dp, accent outline and dot when on, on by default, remembered); keyword in serif 28sp with the story
  (12.5sp, up to 3 lines) and a 52dp stroke-order thumbnail; the large square `WritingPad` with the trace guide (the kanji in `ink` at low alpha, via the pad's `ghost` slot);
  Undo / Grid / Clear; a full-width coral `Submit` (soft haptic, clears the pad, counts "Submitted N times") disabled while the pad is empty. The pad shrinks first on short screens.
- **Entry points:** a grid tile (route `heisig/practice?ch=&q=`), and an outlined `Practice writing` button on the card back. The card-back version opens as an overlay so
  the YOU drawing is still there afterwards.
- **Maturity tiers** (`reading/HeisigTier.kt`, `ui/TierFrame.kt`): Plain, Copper, Silver, Gold, Jade, Radiant from FSRS stability (7, 21, 60, 180 days; learning = Copper). Grid
  tiles get an inset chamfered frame per tier (look from `kanji_tiers_v3.html`). Plain, Copper and Silver are still; Gold, Jade and Radiant animate on one shared clock that
  pauses while scrolling and is off when system animations are off. Answer box kanji on the card back is tinted. No frames on the practice page.
- **Reading and watch:** kanji with a tier are tinted in the metal base colour (static). Settings > Heisig deck > `Kanji maturity colours` turns it off. Not applied to
  missed, karaoke-filled or current-line words in watch.
- **Due dot:** a 7dp coral dot at a tile's top right for cards due today.
- **State:** drawing and counter live in the screen (`remember`, per kanji), never saved. Switches and the colour setting are stored in the `heisig` preferences.
- **Copy:** short plain sentences, no em dashes.
- **Not checked:** nothing here has been compiled or run, the frames have not been compared with the HTML mockup on a phone, and the tier thresholds are starting guesses.

## Study tab, Path and tests (learning path)

Design source: `fluvour-clickable-mockup.html` (a layout guide only; the app's theme and components win).
- **Study sub-tabs:** Cards, Path, Quiz, Heisig. Path shows only when the language has a curriculum file.
- **Cards:** under the sub-tabs a mode row: Mixed, Clip, Meaning, Word, selected one outlined, due badge hidden at 0. A small "Front: word / meaning / clip" line sits above the flashcard (Undo at its left). The flashcard and grade buttons are unchanged.
- **Path list** (`PathScreens.kt`): title, "N of M units graduated", progress bar, rows: done check, current (number, "N due" or "New" badge), locked (lock icon; tap shows "Locked. Graduate the current unit first.").
- **Unit detail:** goal, words, grammar, Start lesson, Practice until learned (a mastery session over every word and sentence; the unit check opens after it), Unit check (its own number of questions and pass mark; "Covers Units 1 and 2" on Unit 2), Take or Retake test ("Finish the lesson first." until the lesson is done). A unit with no check (Unit 1) shows "Graduation" text and "Finish the drills to graduate" instead.
- **Lesson** (`LessonScreen.kt`): chunk counter and progress bar, tap-a-word sentence card with speaker, gloss, pattern note. Drills: build the sentence, choose, type the meaning. Typed answers show "Checking..." while the AI grades; Continue is never blocked. A miss shows the reason and "Saved with this card."
- **Unit test** (`UnitTestScreen.kt`): "Question N of 30", no feedback, flag button, "Typed answers are graded at the end of the test." Then a result screen: score, pass line, from this unit, from older units, sent to review, flagged; Back to the path or Retake.
- Not built yet: lesson chunk preview on the unit page, warm-up, images, unit story.

## Art and animation (ui/art/Art.kt and ui/art/Ambient.kt)
Ported from `Fluvour-1-14.html`, which is the current design. Everything is drawn in code, so one drawing serves every language.
The random generator is the design's own (mulberry32), and the seeded values (stipple, petals, the flock's starting positions)
were checked against the JavaScript and match.
- **Covers and banners** (Art.kt): terraces, sunhills, leaf, moon, coast, sprouts, bloom, mist; banners b1 Thai, b2 Japanese, b3 English
  (twinkling stars). Used by the Home next-up cover, Output and Read thumbnails, and the Watch banner.
- **Language marks** (`Modifier.languageMark`, `LanguageMotif`): a blossoming branch (Japanese), a lotus (Thai), the London skyline
  reflected in the river with twinkling lamps (English). Faint in a screen's top corner; larger on "All done" and onboarding.
- **Ambient background** (`Modifier.ambient`): behind Home, Review, Quiz, Read, Output and Watch, top 420dp, fading out downward.
  - Thai: slow water rings and falling drops. Japanese: nine drifting blossoms.
  - English: two flocks of birds (22 birds) that fly by flocking rules (separation, alignment, cohesion, a wandering goal), with
    wings that flap between up and down poses, plus six sliding wave lines. Home keeps the birds on the right, other screens
    use a smaller, higher area (the design's `eh` and `eo`). One flock is shared, so it keeps flying when you switch tabs.
- **Header patterns:** stipple mist behind Home, terrace lines behind Read, 12% opacity, fading out.
- **Grain:** speckle on every cover, plus the whole-app paper grain at 4.5% (Fluvour theme only).
- **Settings:** Illustrations off hides the ambient background, language marks, finish motif and header patterns. Covers and grain stay
  (the design's `.noart`). Reduce motion stops the ambient background and the twinkling. Both switches live in `ArtPrefs` and reset
  when the app restarts (`TODO(settings)`).

## Not ported
- The design CSS has leftover rules for kites and a single bird (`.ambk`, `.ambo`), but nothing in the file creates them. The English
  flock is the birds.
- The grain is an approximation: the HTML uses an SVG turbulence filter, Compose draws a seeded noise tile instead.
- The app grain does not cover bottom sheets and dialogs, because Compose draws those in their own windows.
- The ambient animation runs on every frame while a screen with it is open. If battery or smoothness is a concern, a frame-rate cap
  is a one-line change in `rememberAmbientClock`.
- High contrast (shown in Settings, no effect yet). Other animation in the design (sheet and flip transitions) still obeys the system
  setting, not the Reduce motion switch.
- The screens themselves were ported from an earlier version of the design. They have not yet been checked against `Fluvour-1-14.html`.

## Next steps
1. Gradle sync and fix compile errors.
2. Add `reading` to `CardEntity` (migration) and show it on card back and editor.
3. Store the in-memory settings in `SettingsRepository`, and a first-run flag to launch onboarding.
4. Replace samples one feature at a time; each has a `TODO(name)` marker: `grep -rn "TODO(" app/src`.
