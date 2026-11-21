# Audio splitter check (batch voice)

Runs `PcmSplit` (pure Kotlin, `app/src/main/kotlin/app/fluvour/audio/PcmSplit.kt`) on a plain JVM with fake speech (tone bursts and silences):
the right count of pieces, a short pause inside a sentence is not a cut, a wrong count gives null, noise is ignored.
Run from the project root with the Kotlin compiler.

1. `kotlinc app/src/main/kotlin/app/fluvour/audio/PcmSplit.kt tools/splittest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line starts with `ok`, last line `ALL OK`)
