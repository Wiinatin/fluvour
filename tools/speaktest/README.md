# Speaking reply check (speaking, step 5)

Checks `ai/SpeakingPrompt.kt` (the improve prompt, the parsers for the transcribe and improve replies, the flagged-word segments, the stored JSON, and the version measures in `ai/SpeakingMeasures.kt`) and the transcribe prompt in `ai/SpeechTranscriber.kt` on a plain JVM, with sample and messy replies.
Chat cannot run Gradle, but it can download the Kotlin compiler from github.com (`JetBrains/kotlin` releases, `kotlin-compiler-2.0.21.zip`).

1. Extract `MiniJson` (from `reading/Yomitan.kt`, the lines of `object MiniJson` up to its closing `}` at column 0) into `MiniJson.kt` with `package app.fluvour.reading`.
2. `SpeechTranscriber.kt` uses Android classes, so copy only its `TranscribePrompt` object (the first object in the file, no imports needed) into `TranscribePrompt.kt` with `package app.fluvour.ai`.
3. `kotlinc MiniJson.kt TranscribePrompt.kt ai/SpeakingPrompt.kt ai/SpeakingMeasures.kt tools/speaktest/Test.kt -d out`
4. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line `ALL OK`)
