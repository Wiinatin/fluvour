# Lyrics logic check
Parsers (enhanced LRC, Kugou KRC) and matching (title guess, version tags, script, offset). Plain JVM, no network.
kotlinc app/.../media/MediaSource.kt app/.../lyrics/LyricsText.kt app/.../lyrics/LyricsParsers.kt tools/lyricstest/Test.kt -d out
java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt   (every line starts with ok)
