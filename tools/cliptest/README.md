# Clip logic check (roadmap 4.2)

Runs the pure parts of clip recommendations (ClipLevels, ClipMeasurer, ClipPrompt) on a plain JVM, with a whitespace word splitter instead of the app's.
Chat cannot run Gradle, but it can download the Kotlin compiler from github.com (`JetBrains/kotlin` releases, `kotlin-compiler-2.0.21.zip`).

1. Extract `MiniJson` (from `reading/Yomitan.kt`, the lines of `object MiniJson` up to its closing `}` at column 0) into `MiniJson.kt` with `package app.fluvour.reading`.
2. `kotlinc Stubs.kt MiniJson.kt reading/Levels.kt reading/ClipLevels.kt reading/ClipMeasurer.kt reading/ClipPlan.kt reading/IndexPlan.kt ai/ClipPrompt.kt Test.kt -d out`
3. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`)
