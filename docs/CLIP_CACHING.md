# Clip caching: fewer YouTube requests

Goal: a clip card asks YouTube as little as possible, never asks for the same video twice in a short time, plays without waiting, and still works while YouTube is blocking. Nothing here hides the app from YouTube; it only sends fewer requests. `YouTubeGuard`'s pacing (1.5 to 4 s) and cooldown (15 min doubling to 6 h) are unchanged.

## The pieces

| What | Where | Behaviour |
|---|---|---|
| Request counter | `media/YouTubeGuard.kt`, Settings | Counts guarded requests actually sent today (cache hits and cooldown refusals are not counted). One lookup = 1; an extractor-update retry = 2. Shown as "YouTube requests today: N". |
| Lookup cache | `YouTubeGuard.run(key, block, ttlMs)`, `YouTubeSource.load` | A video lookup (stream address + subtitles for both languages) is saved for 2 h under `l:<url>|<lang>|<nativeLang>`. Searches and subtitle reads keep the 20 min default. A block clears the whole cache. |
| One fresh retry | `ReviewViewModel.onPlayerError` | If a clip's stream fails to play, the saved lookup is dropped and loaded once more before FAILED (skipped while cooling down). |
| Review cache | `ReviewViewModel` | The old 3-video / 30 min map is gone; the lookup cache (2 h, 60 videos) does that job. |
| Word-mode fallback | `ReviewViewModel.ytBlocked`, `ReviewScreen` | While `YouTubeGuard.coolingDown()`, clip cards show their word: no spinner, no failed clip, no look-ahead. Re-checked when each card appears. |
| Look ahead by video | `ReviewViewModel.publish()` | Prepares the next 2 distinct videos (scans 20 cards) that are not cached and not the one on screen. Same-video cards count once. |
| Saved clips | `media/ClipCache.kt`, `media/ClipAheadWorker.kt` | Background job saves the few seconds each due clip card needs on the SD card (see below). |

## Saved clips

- Storage: one media3 `SimpleCache` in the `clips` folder of the SD card's app folder (else phone external, else internal). Cap 500 MB (`ClipCache.MAX_BYTES`). Bytes are keyed `clip:<video url>|<itag>`, not by stream address, which changes each lookup.
- Making a clip ready: a screenless, silent player buffers the clip range, then the clip is replayed from disk with no network. Only a clip that passes is marked ready (prefs `clip_ready`).
- Playing: a ready clip plays with no lookup and no request. If it fails, its mark is dropped and the 2 h-lookup retry runs. Every clip played is also saved as it plays.
- Eviction: whole videos, least recently used first (`ClipCache.trim`), so a ready clip is never half-removed.
- Job: every 3 h and 30 s after app start; any network; charging at any level or at least 20% battery; only while the card front is "clip"; one lookup per video, at most 6 videos and 8 min per run; stops on cooldown.
- Settings, "Saved clips": switch (on by default), size used, Clear.

## Not done / watch

- Not compiled or run on a phone when written; first real test is on the device. Compare "YouTube requests today" before and after studying about 50 clip cards.
- Stream address lifetime (about 6 h, assumed) was not verified; the 2 h lookup cache and the retry cover it.
- If a card is reached while its video is still being looked up ahead, a second request for it can be sent (share one in-flight lookup per key to fix).
- Background indexer (`ClipIndexWorker`) and Watch suggestions were not measured or reduced.
- Size cap is a constant (no Settings control).
