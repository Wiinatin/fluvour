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
- Navigation (`ui/Root.kt`): Home, Review, Read, Output, Watch. Immerse became Watch. Old route `review` is gone (Review is the tab).

## Screen map
| Design | Compose | State |
|---|---|---|
| Home, language sheet, profile sheet | HomeScreen, ProgressScreens | Real: due count, streak, week, heatmap, words known. Placeholder: Read/Output/Watch done flags, skill bars |
| Review (flip, swipe, ratings) | ReviewScreen | Real (FSRS). Missing: cloze, reading line, "first met in" |
| Leech "new angle" | ToolSheets.LeechSheet | Trigger is real (lapses >= 6), content placeholder |
| Browse, card editor | Screens.kt, CardEditorScreen | Real. Missing: reading field, AI image toggle |
| Quiz | QuizScreens | Sample questions |
| Read, reader, word sheet | ReadScreens | Real. Added: Add-to-Read sheet (UI), Aa sheet (size works, reading aid not stored) |
| Output: calls, topics | SpeakScreens | Sample |
| Journal, handwriting, stroke order | WriteScreens | Sample |
| Watch, dictate, transcript, say it back | WatchScreen | Sample, simulated playhead |
| Settings | SettingsScreen | Real: language, UI language, theme, mode, goal, auto-play, account, Anki. Rest in-memory |
| Onboarding | OnboardingScreen | Language and goal save. Level not stored. Not launched on first run yet |
| Progress, weekly review | ProgressScreens | Placeholder numbers |
| Screen lookup setup, widget preview | ToolSheets | UI only |

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
