# Unit test logic check (learning path step 8)

Runs `TestLogic` (pure Kotlin, `app/.../path/UnitTest.kt`) on a plain JVM: scoring older words from FSRS state, picking and spreading them,
the 30 question places (21 unit, 9 older, types, spare places), the prompt, reading a model reply (assigned word present, seen sentences
dropped, spare takeover, top-up), answer checks and the score with flags. Run from the project root with the Kotlin compiler.

1. `kotlinc app/src/main/kotlin/app/fluvour/path/Curriculum.kt app/src/main/kotlin/app/fluvour/path/MiniJson.kt app/src/main/kotlin/app/fluvour/path/Lesson.kt app/src/main/kotlin/app/fluvour/path/UnitTest.kt app/src/main/kotlin/app/fluvour/path/TestPlan.kt tools/unittest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line starts with `ok`, last line `ALL OK`)

Also checked (Unit 1 rework): which units have a check and what it covers (`TestRules`), the check's size and pass mark, set-phrase mode, the known-word check (`VocabCheck`, with fake tokens, so no Kuromoji is needed), and the vocabulary rule in the test and lesson prompts.
