# Writing pad check (Heisig pad, step 3)

Checks `reading/PadStrokes.kt` (strokes drawn on the pad: begin, add, end, undo, clear, caps, corner smoothing) on a plain JVM. The pad drawing itself and the card need Android and compile on Win's computer.
Kotlin compiler: `kotlin-compiler-2.0.21.zip` from github.com (`JetBrains/kotlin` releases).

1. `kotlinc app/src/main/kotlin/app/fluvour/reading/PadStrokes.kt tools/padtest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line `ALL OK`)
