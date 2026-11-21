# Path rules check (learning path step 6)

Runs `PathState` and `UnitItems` (pure Kotlin, `app/.../path/PathState.kt`) on a plain JVM: unit status (graduated, current, locked),
which path is shown, the locked-unit line, the review-count badge and the unit-to-cards link.
Needs the Kotlin compiler. Run from the project root.

1. `kotlinc app/src/main/kotlin/app/fluvour/path/Curriculum.kt app/src/main/kotlin/app/fluvour/path/PathState.kt tools/pathtest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line starts with `ok`, last line `ALL OK`)
