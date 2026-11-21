# EPUB cover check (Read library, step 8)

Checks `reading/EpubCover.kt` on a plain JVM: EPUB 3 `cover-image`, EPUB 2 `<meta name="cover">`, cover found by name, no cover, WebP and SVG, a bad first candidate, files over 5 MB, odd paths, and damaged input (must return null, never throw). The test builds its EPUBs in memory.
Chat cannot run Gradle, but it can download the Kotlin compiler from github.com (`JetBrains/kotlin` releases, `kotlin-compiler-2.0.21.zip`).

1. `kotlinc app/src/main/kotlin/app/fluvour/reading/EpubCover.kt app/src/main/kotlin/app/fluvour/reading/EpubReader.kt app/src/main/kotlin/app/fluvour/reading/ImportText.kt tools/epubcovertest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line `ALL OK`)
