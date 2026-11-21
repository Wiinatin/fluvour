# Library progress check (Read library, step 4)

Checks `reading/LibraryProgress.kt` (overall book progress, one Continue reading entry per book, safe cover file names) on a plain JVM. The Room table, DAO and `LibraryRepository` need Android and compile on Win's computer.
Kotlin compiler: `kotlin-compiler-2.0.21.zip` from github.com (`JetBrains/kotlin` releases).

1. `kotlinc app/src/main/kotlin/app/fluvour/reading/LibraryProgress.kt tools/librarytest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line `ALL OK`)
