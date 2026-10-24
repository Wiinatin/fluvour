# Multi-theme in Fluvour

**Short answer: yes, and it is cheap if we follow one rule: screens never hard-code colours, shapes or layout choices. They read tokens.**

## How it is built (already in the code)
- `ThemeSpec` (ui/theme/Theme.kt) bundles four things: light colours, dark colours, shapes, and structural style flags.
- `FluvourTheme(spec, mode)` provides the spec to every screen and also feeds Material 3, so dialogs, text fields and switches follow the theme automatically.
- Components (`FCard`, `FButton`, bottom bar) read `Fluvour.colors`, `Fluvour.shapes`, `Fluvour.style`.
- The choice (theme + light/dark/system) is saved in DataStore and applied instantly from Settings.
- Calm (default) and Playful are implemented from the mockup's CSS values. Adding a theme means adding one `ThemeSpec` in Themes.kt and one enum entry.

## Three levels of difference, and what each costs
1. **Colour and shape themes** (Calm, Playful): one data object each. Nearly free.
2. **Layout flags** (Playful's big blue header, card shadow depth): a few `if (Fluvour.style.heroHeader)` branches in shared screens. Cheap, keep the list short.
3. **Character themes** (Doodle: new font and background doodles; Game: Rubik font, sunburst background, mascot, reward milestones): these need extra assets, fonts and sometimes different components. Moderate cost per screen. Doable, but each one is a mini design system.

## Recommendation
Ship Calm only through Phases 1 to 2 while screens change quickly. Keep Playful as a proof the system works. Add Doodle or Game after Phase 8 polish, when screens are stable, and only if wanted. Fonts: Figtree and Noto Sans Thai Looped should be bundled in res/font (not included here), because Thai rendering depends on it.

## Rules to keep it cheap
- No `Color(0x...)` outside ui/theme. No hard-coded corner radii in screens.
- Every new screen is checked in light and dark, in Calm and Playful, before it is called done.
- Theme-specific decoration (mascot, doodles) goes behind `Fluvour.style` flags, not copied screens.
- Student edition (Phase 9) can lock the theme list, or let students pick, with no code change.


## Warm (Fluvour UI, default)
`Warm` is the finalized design. `style.languageAccent` swaps `accent`, `tint` and `primary` per target language
(`LanguagePalettes`), `style.designFonts` switches to the bundled fonts, `style.flat` uses hairline borders instead of shadows.
New tokens: `tint` (wash behind card faces) and `scrim` (behind sheets). `FluvourTheme` now takes the target language code.
See docs/UI.md.
