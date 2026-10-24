# Phase 1 status

Style: Calm (default) while screens change quickly.

| # | Item | Status |
|---|---|---|
| 1 | Spaced repetition, daily review queue, due counts | **1a written, not yet built or run** (see below) |
| 2 | Card creation (word, meaning, example, notes) | **2 written, not yet built or run** (see below) |
| 3 | AI image per word | **3 written, not yet built or run** (see below) |
| 4 | Text-to-speech | **4 written, not yet built or run** (see below) |
| 5 | Streaks, daily goals, review heatmap | **5 written, not yet built or run** (see below) |
| 6 | Offline review + sync | **6 written, not yet built or run** (see below) |
| 7 | Export to Anki | **7 written, not yet built or run** (see below) |

## 1a: review loop
- Home shows the due count and a "Start review" button.
- `ReviewScreen` (route `review`, bottom bar hidden): progress bar, New / Learning / Due chips, card front with the word and its example (word highlighted), tap or "Show answer", then four buttons with the delay under each (Again, Hard, Good, Easy; only Good is dark, as in the Calm mockup).
- `ReviewViewModel` builds the session queue: cards already in progress first, then up to 20 new cards. Cards in learning steps that are due within 20 minutes return to the end of the queue. Each rating goes through `CardRepository.review`, which updates the card and writes the `review_log` row.
- `formatInterval` turns the delay into "10m", "3h", "2d", "4mo", "1.5y".

## Known gaps in 1a
- No undo button yet (the mockup has one).
- The 20 new cards limit applies per session, not per day, so starting another session offers 20 more. A real daily count arrives with daily goals (item 5).
- The queue loads once when the screen opens. Cards added while reviewing appear next session.
- Not compiled: Phase 0 has not been built yet either. Build Phase 0 first, then this.

## 2: card creation
- The Cards tab opens a full-screen editor (route `card?id=...`) for new and existing cards, replacing the Phase 0 dialog. The bottom bar is hidden there.
- Fields: word, meaning, example sentence, notes. A soft warning shows when the word already exists (saving is still allowed).
- New cards also have "Save and add another", which clears the form and counts how many you added.
- Editing has a confirmed Delete. The card list has a search box (word or meaning) and shows the example under each card.

Known gaps: the language-specific keyboard is not forced (the phone's keyboard decides), and the AI image field arrives with item 3.

## 4: text-to-speech
- `TtsManager` wraps Android's built-in TextToSpeech. No network, no keys, no backend. The manifest declares the TTS service query so the app can find engines on Android 11 and newer.
- Review card: speaker buttons next to the word and the example sentence, on both the front and the back.
- Settings > Audio: "Play the word when a card appears" (off by default).
- If the phone has no voice for the language, the review screen shows a short hint to install one under Android's Text-to-speech settings. Thai and Japanese voices come with Google's speech engine, but check on the real phone.

Known gaps: speech rate and voice choice are not adjustable yet, and the Cards tab has no speaker buttons.

## 5: streaks, daily goal, heatmap
- Logic lives in the pure-Kotlin srs module (`StudyStats`) with unit tests (`StudyStatsTest`), so it can be ported to TypeScript for Torbito web. The Kotlin tests have been run (see Verification below).
- Streak rule: the number of days studied in an unbroken run. A single missed day is a rest day and does not break it; two missed days in a row end it. Not having studied yet today never breaks it.
- Home now scrolls and shows a streak card (streak, a warm line, and a progress bar toward the daily goal) and a heatmap of the last 17 weeks (Monday first, five shades of the accent colour).
- Settings > Daily goal: 10, 20, 30 or 50 reviews a day (default 20).
- Counts are review events from `review_log`, so a card that comes back in the same learning session counts again.

Known gaps: "rest day" is automatic, not something you pick, and the heatmap cells are not tappable yet.

## 7: export to Anki
- Settings > Your data > "Export cards to Anki" lets you pick where to save `fluvour-cards.txt`. It contains every card in every language.
- Format: Anki's tab-separated text import with header lines (needs Anki 2.1.54 or newer). Front = word, Back = meaning, example and notes, Tags = language code. In Anki: File > Import, choose the file.
- The format lives in the pure-Kotlin srs module (`AnkiExport`) with tests. The tests have been run (see Verification below).

Known gaps: review history and scheduling are not exported (cards arrive as new in Anki), and images and audio are not included yet.

## Verification so far
- The pure-Kotlin `srs` module (scheduler, `StudyStats`, `AnkiExport`) was compiled with the real Kotlin 2.0.21 compiler, the same version as the project. All 15 tests pass: the 3,377 golden FSRS reviews, the 7 streak and day-count tests, and the 4 Anki format tests. They ran through a small stand-in test runner, not Gradle.
- The Android app code (screens, view models, Room, Hilt, Compose) has NOT been compiled. That needs Android Studio, so the first Gradle build is still the next real check.

## 3 and 6 need a signed-in account
Both use your Supabase login. The app still works fully offline and signed out (cards stay text-only and nothing syncs).
- `AuthRepository`: email and password sign-in against Supabase Auth (plain HTTP, no new libraries), token refresh, sign-out. Tokens are kept in the app's private storage (not yet encrypted).
- Settings > Account and sync: sign in, "Sync now", sign out. Sync also runs quietly when the app opens.
- Backend address and public key come from `gradle.properties` (`fluvour.supabaseUrl`, `fluvour.supabaseAnonKey`). The anon key is public by design; row-level security protects the data. The manifest now has the INTERNET permission.

## 6: sync
- `SyncRepository`: pull first (a row wins if its `updated_at` is newer), then push local changes. Covers decks, cards and the review log. Pull uses a saved cursor, push sends rows changed since the last sync, review log rows are insert-only.
- Image files never sync, only the image prompt. A pulled card keeps the local image.
- Known gaps: last-write-wins by device clock; sources, sentences and speaking tables are not synced yet (nothing writes them yet); deleting a card is a soft delete that syncs; no background sync, only on app open and "Sync now".

## 3: AI images
- New words get a picture in the background after saving (when signed in). The card editor has "Generate image" / "Regenerate image". The review card shows the picture on the back, not the front, so it does not give the answer away.
- Generation goes through the ai-proxy (new `gemini-image` provider, deployed as version 2), one request at a time. The picture is shrunk to at most 512 px, saved as a small JPEG in the app's private storage and cached.
- Source order (ai-proxy v4, provider `image`): Hugging Face FLUX.1-schnell first when `HF_API_KEYS` is set, Gemini's image model as the fallback. Earlier I had switched to Gemini only because Hugging Face's docs list under $0.10 of free credits per account; your Fylosphere app shows the workable answer is rotating several tokens, which is now ported.
- Shares the 200 calls per day limit with text calls (one call per picture, however many keys or sources are tried).

## Before this works end to end (dashboard steps)
1. In the `fluvour` Supabase project (Edge Functions > Secrets), add `GEMINI_API_KEYS` with one or more Gemini keys separated by commas or new lines. Optional: `GEMINI_MODELS` and `GEMINI_IMAGE_MODELS`. For pictures from Hugging Face also add `HF_API_KEYS` (tokens separated by commas or new lines; each token needs "Make calls to Inference Providers" enabled). Optional `HF_IMAGE_MODEL`.
2. Create your user (Authentication > Users > Add user), then turn off public sign-ups.
3. Build in Android Studio, sign in under Settings > Account and sync.

## ai-proxy v3: key rotation and model fallback (ported from Momori)
Same rules as `ai/GeminiKeyPool.kt` and `ai/GeminiProvider.kt` in the Momori app:
- Keys: never-limited keys first, with the key listed LAST tried first; then limited keys, oldest limit first. A quota error stamps the key and moves to the next one; an invalid key is skipped without a stamp; no timed cooldown. Stamps live in the table `gemini_key_state` (key id = first 4 bytes of sha256, never the key).
- Models: tried in order (default `gemini-3.6-flash`, `gemini-3.5-flash`, `gemini-3.5-flash-lite`, taken from Momori). A busy or missing model moves on to the next; a model busy or out of quota on every key rests 90 seconds in that instance.
- Images use `GEMINI_IMAGE_MODELS` (default `gemini-2.5-flash-image`; Momori has no image model, so check the current name).
- The proxy was type-checked with `tsc` using stand-in Deno typings; it has not been called against Gemini yet.
## ai-proxy v4: Hugging Face (ported from Fylosphere main.py)
- Model: `black-forest-labs/FLUX.1-schnell` through Hugging Face Inference Providers with provider `auto`, using the official `@huggingface/inference` client (version 4.x), the same call as your `InferenceClient.text_to_image`. Override with `HF_IMAGE_MODEL`.
- Rotation (from `generate_image_with_key_pool`): a 401, 402, 403 or 429 stamps the key (table `hf_key_state`) and tries the next; any other failure stops, because it would fail on every key. Never-limited keys go first, OLDEST listed first (this is the opposite of the Gemini order, which follows Momori), then stamped keys, oldest stamp first.
- Time limits: 40 seconds per key and 75 seconds for all Hugging Face attempts, so the Gemini fallback still fits inside the function's time limit.
- Checked: the proxy and the client calls type-check with `tsc` against the real package. NOT checked: a live call to Hugging Face. First test: generate one picture.
