# Gutendex parser check (Read library, step 3)

Checks `reading/GutendexParse.kt` (search URL, reply parsing, EPUB and cover picking, name flip, messy replies) on a plain JVM. `GutendexClient.kt` uses the app's `Http`, so it is not part of this test.
Chat cannot run Gradle, but it can download the Kotlin compiler from github.com (`JetBrains/kotlin` releases, `kotlin-compiler-2.0.21.zip`).

1. Extract `MiniJson` (from `reading/Yomitan.kt`, the lines of `object MiniJson` up to its closing `}` at column 0) into `MiniJson.kt` with `package app.fluvour.reading`.
2. `kotlinc MiniJson.kt reading/GutendexParse.kt tools/gutendextest/Test.kt -d out`
3. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line `ALL OK`)
