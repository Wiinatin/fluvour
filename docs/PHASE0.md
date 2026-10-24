# Phase 0 status

| Item | Status |
|---|---|
| Skim Nibomo's FSRS and AI-feature design | Done: see docs/NIBOMO_NOTES.md (MIT license; FSRS-6 upgrade recommended) |
| Project setup (Gradle, Compose, Hilt, Room) | Written; first Android Studio build pending |
| Standalone spaced repetition module | Done. Pure Kotlin FSRS-6 with learning steps, matching ts-fsrs 5.4.2 on 3,377 generated reviews (0 mismatches) |
| Backend (auth, database, secrets, AI proxy) | Drafted: backend/schema.sql and ai-proxy stub. Not applied or deployed |
| Data model | Written: Room entities and matching Postgres schema |
| Language selector (target + UI language) | Written (Settings screen) |
| Basic app shell, theming, navigation | Written: 5-tab shell, Calm + Playful themes, light/dark/system |

**Done when:** you can pick a language and create/edit cards on a phone. Blocked only on the first build and run in Android Studio.

## First run
1. Open the folder in Android Studio (Ladybug or newer), let Gradle sync, accept any version bumps it suggests.
2. Run `:srs:test` (should pass), then run `:app` on a device.
3. Settings (gear on Home): pick language, style, light/dark. Cards tab: add, edit, delete a card.

## Known gaps
- Fuzz uses our own deterministic generator, so fuzzed intervals will not equal ts-fsrs (vectors are generated with fuzz off).
- Relearning with more than one step is unsupported (ts-fsrs clips w17/w18 in that case; not ported).
- Learning-step cards are due by the minute; review cards are offered from the start of their due day.
- Fonts not bundled yet; the system font is used.
- Gradle wrapper files are not included; Android Studio generates them.
- UI language only stores the choice; translated strings come later.
