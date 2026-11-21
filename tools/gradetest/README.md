# Typed-answer grading check (learning path step 9)

Runs `GradeLogic` (pure Kotlin, `app/src/main/kotlin/app/fluvour/path/Grading.kt`) on a plain JVM: the grading prompt, reading the AI's
verdicts (fences, trailing commas, string numbers, bad or cut-off replies) and falling back to the local check.
Run from the project root with the Kotlin compiler.

1. `kotlinc app/src/main/kotlin/app/fluvour/path/MiniJson.kt app/src/main/kotlin/app/fluvour/path/Grading.kt tools/gradetest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line starts with `ok`, last line `ALL OK`)
