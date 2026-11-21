# Article level check (Articles tab, step 3)

Runs `ArticleLevel` (measuring and the level label) on a plain JVM, with a whitespace word splitter instead of the app's.
Chat cannot run Gradle, but it can download the Kotlin compiler from github.com (`JetBrains/kotlin` releases, `kotlin-compiler-2.0.21.zip`).

1. Copy `tools/cliptest/Stubs.kt` next to this folder's `Test.kt`.
2. `kotlinc Stubs.kt reading/Levels.kt reading/ClipLevels.kt reading/ClipMeasurer.kt reading/articles/ArticleLevel.kt Test.kt -d out`
3. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line is `ALL OK`)
