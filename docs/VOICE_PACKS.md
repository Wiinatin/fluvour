# Voice packs (offline natural voice)

Hand-off and plan: `fluvour-voice-packs-handoff.md`. This file is the short how-to.

## One-time setup: the sherpa-onnx library

The app uses sherpa-onnx 1.13.8 as a local AAR (about 50 MB, not in git or in the paste-over zip).

1. Download `sherpa-onnx-1.13.8.aar` from https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
2. Put it in `app/libs/` (create the folder). Keep the file name.
3. Sync Gradle and build.

Why 1.13.8 and not the newest (2.0.0): 2.0.0 removed the espeak-ng phonemizer. The official Piper and Kokoro voices need it, so they only work on 1.13.x.
Only the arm64 libraries are packed (`abiFilters` in `app/build.gradle.kts`). An x86 emulator cannot run the voice; use a phone.

## Debug test (debug builds only)

Settings > AI keys shows "Test offline voice (debug)". It speaks one sentence with the model folder in
`/sdcard/Android/data/app.fluvour/files/voicetest/` and shows how long it took.

1. Get a model folder (for example `vits-piper-en_US-amy-low` from the `tts-models` release of k2-fsa/sherpa-onnx, unpack the `.tar.bz2` on the computer).
2. With the phone connected: `adb push vits-piper-en_US-amy-low /sdcard/Android/data/app.fluvour/files/voicetest/`
   (the folder can sit directly in `voicetest` or be its only subfolder).
3. Tap the row. The first run includes loading the model.
4. Optional files in `voicetest`: `say.txt` (the sentence, first line) and `sid.txt` (the speaker number). For Kokoro, speaker numbers: 0 to 27 English, 28 to 29 and 53 Spanish, 30 French, 37 to 41 Japanese, 45 to 52 Chinese.

## Publishing the packs (once, from GitHub)

The app downloads packs from the `voicepacks` release of this repo. The repo must be public (the app downloads without logging in).

1. Push the project, including `.github/workflows/voice-packs.yml` and `tools/voicepacks/`.
2. GitHub > Actions > "Voice packs" > Run workflow. It takes a few minutes: it downloads the official sherpa-onnx models, zips them, writes `packs.json` and creates the release.
3. Check the `voicepacks` release has six zips plus `packs.json`. The app lists whatever `packs.json` says.

To change which model speaks a language, edit `PACKS` in `tools/voicepacks/build_packs.py` (or the order of entries) and run the workflow again. The app needs no change. Bump `version` of a pack when its zip changes.
Can also be run on a computer: `python3 tools/voicepacks/build_packs.py out`.
