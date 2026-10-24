# Nibomo skim (reference only)

Repo: kirill-markin/flashcards-open-source-app (formerly "Flashcards Open Source App"). License: **MIT** (permissive: ideas and code can be reused with the copyright notice kept). Checked July/October 2026 from the repo README and docs/fsrs-scheduling-logic.md.

## Ideas worth taking
1. **Learning steps before graduation.** New cards go through short steps (default 1 and 10 minutes), relearning has its own step (10 minutes), then the long-term FSRS interval applies. Our module currently jumps straight to day intervals.
2. **FSRS-6, not FSRS-5.** Nibomo mirrors the official `ts-fsrs` 5.2.3 flow with the official FSRS-6 default weights. Our module is FSRS-5 with 19 weights typed from memory.
3. **Golden test vectors.** One shared JSON of expected schedules is run against every implementation (backend, iOS, Android). Perfect for our Kotlin module and the future TypeScript port for Torbito (Phase 9): generate vectors from `ts-fsrs`, test both.
4. **Hidden state per card:** stability, difficulty, last reviewed time, scheduled days, card state, step index. Invariants: untouched new cards have no memory fields; learning/relearning cards must have a step index.
5. **Counters:** reps increments on every review; lapses only on Again from the review state. Ours increments lapses on any Again after the first review; align this.
6. **Elapsed days from calendar-day boundaries**, and throw if last review is after the review time.
7. **Deterministic fuzz** (same inputs, same interval) so intervals are not all clumped, yet tests stay stable.
8. **Settings are forward-only:** changing retention or steps affects future reviews only. Weights are pinned, not user-editable.
9. **Queue order:** recently reviewed due cards (within 1 hour) first, then other due cards, then new cards; stable tie-breakers; the card on screen stays pinned.
10. **Conversational review contract** (next card, reveal, submit with idempotency): a model for our Phase 5 AI partner and Torbito's student API.

## Do not copy
- Its backend is AWS plus workspaces and sync for a multi-platform product; ours is Supabase and a single user until Phase 9.
- Hosted MCP/agent API: out of scope.

## Follow-up (done)
`srs/` is now FSRS-6 with learning steps and optional fuzz. Weights and logic were taken from the `ts-fsrs` 5.4.2 source, and `srs/src/test/resources/fsrs-vectors.txt` / `.json` hold 360 scenarios (3,377 reviews, six configs) generated from the real library. The Kotlin test replays them; the JSON is ready for the TypeScript port in Phase 9. Not copied from Nibomo: its same-day review rule differs from ts-fsrs, so we follow ts-fsrs.
