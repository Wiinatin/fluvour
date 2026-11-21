# masterytest

Runs `MasterySession` and `MasteryBuilder` (pure Kotlin, `app/.../path/Mastery.kt`) on a plain JVM: the items (every unit word and every lesson sentence), the drills built for them locally, points, spacing, the proof question, saving and restoring progress, and how long a session is for learners of different accuracy.

```
kotlinc app/src/main/kotlin/app/fluvour/path/Curriculum.kt app/src/main/kotlin/app/fluvour/path/MiniJson.kt app/src/main/kotlin/app/fluvour/path/Lesson.kt app/src/main/kotlin/app/fluvour/path/Mastery.kt tools/masterytest/Test.kt -d out
java -cp out:<kotlin-stdlib.jar> TestKt
```
Numbers to tune are in `MasteryRules` (points, penalty, gaps, turn cap).
