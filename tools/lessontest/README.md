# Lesson logic check (learning path step 7)

Runs `MiniJson` and `LessonLogic` (pure Kotlin, `app/.../path/MiniJson.kt` and `Lesson.kt`) on a plain JVM: reading a model reply, the
local checks (chunk count, drill shape, target-word coverage), dropping bad drills, and the typed-answer check.
Run from the project root with the Kotlin compiler.

1. `kotlinc app/src/main/kotlin/app/fluvour/path/Curriculum.kt app/src/main/kotlin/app/fluvour/path/MiniJson.kt app/src/main/kotlin/app/fluvour/path/Lesson.kt tools/lessontest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line starts with `ok`, last line `ALL OK`)
