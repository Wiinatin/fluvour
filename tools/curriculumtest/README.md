# Curriculum data check (learning path step 5)

Runs `CurriculumParser` (pure Kotlin, `app/.../path/Curriculum.kt`) on `app/src/main/assets/curriculum/ja-genki1.tsv`
and checks the data rules: unit caps (6 to 12 words, 1 or 2 grammar points), ids and order, prerequisites point backwards,
no word in two units, bad lines skipped.
Needs the Kotlin compiler (`kotlin-compiler-2.0.21.zip` from github.com JetBrains/kotlin releases). Run from the project root.

1. `kotlinc app/src/main/kotlin/app/fluvour/path/Curriculum.kt tools/curriculumtest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line starts with `ok`, last line `ALL OK`)

The data file is generated: edit `tools/curriculum/build_genki1.py`, run `python3 tools/curriculum/build_genki1.py`, rerun this check.
