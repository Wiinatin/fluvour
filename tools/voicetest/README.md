# Voice pack folder check (voice packs, step 2)

Checks `audio/ModelLayout.kt` (how a voice-pack folder is recognised) on a plain JVM, against real model folders.
Chat cannot run Gradle, but it can download the Kotlin compiler from github.com (`JetBrains/kotlin` releases, `kotlin-compiler-2.0.21.zip`).

1. Get two real folders next to the test: `vits-piper-en_US-amy-low` and `kokoro-int8-multi-lang-v1_0` (both are `.tar.bz2` files in the `tts-models` release of `k2-fsa/sherpa-onnx`).
2. `kotlinc app/src/main/kotlin/app/fluvour/audio/ModelLayout.kt tools/voicetest/LayoutTest.kt -d out`
3. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" LayoutTestKt`   (every line should start with `ok`)

# Pack catalog check (voice packs, step 3)

Checks `audio/VoicePacks.kt` (reading `packs.json`, choosing a pack per language) on a plain JVM. It needs `MiniJson` too.

1. Extract `object MiniJson` from `reading/Yomitan.kt` (from its first line to the closing `}` at column 0) into `MiniJson.kt`, with `package app.fluvour.reading` on top.
2. `kotlinc MiniJson.kt app/src/main/kotlin/app/fluvour/audio/VoicePacks.kt tools/voicetest/PacksTest.kt -d out`
3. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" PacksTestKt`   (every line should start with `ok`)

# Pack file check (voice packs, step 4)

Checks `audio/PackFiles.kt` (unzip with path and size guards, sha256, download resume rules) on a plain JVM.

1. `kotlinc app/src/main/kotlin/app/fluvour/audio/PackFiles.kt tools/voicetest/FilesTest.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" FilesTestKt`   (every line should start with `ok`)

# Voice slot check (voice packs, step 5)

Checks `audio/VoiceSlot.kt` (one model loaded, swap, idle free, stop, speaker clamp, serialised calls) and `audio/Pcm.kt` (WAV with the model's rate) with a fake engine, on a plain JVM.

1. `kotlinc app/src/main/kotlin/app/fluvour/audio/Pcm.kt app/src/main/kotlin/app/fluvour/audio/VoiceSlot.kt tools/voicetest/SlotTest.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" SlotTestKt`   (every line should start with `ok`)
