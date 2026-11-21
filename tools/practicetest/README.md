# Practice search check (Heisig practice pages, step 2)

Checks `reading/HeisigSearch.kt` (the search box filter for the practice grid: keyword, the kanji itself, or frame number) on a plain JVM. The grid and the practice page need Android and compile on Win's computer.
Kotlin compiler: `kotlin-compiler-2.0.21.zip` from github.com (`JetBrains/kotlin` releases).

1. `kotlinc app/src/main/kotlin/app/fluvour/reading/HeisigSearch.kt app/src/main/kotlin/app/fluvour/reading/HeisigTier.kt srs/src/main/kotlin/app/fluvour/srs/Fsrs.kt tools/practicetest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line `ALL OK`)

`HeisigSearch.filter` takes three small selectors instead of `HeisigEntry`, because that class sits in a file that imports Android. In the app:
`HeisigSearch.filter(all, query, { it.ch }, { it.frame }, { it.keyword })`

Step 6b adds `reading/HeisigTier.kt` (maturity tiers); its checks need `srs/.../Fsrs.kt` compiled too (already in the command above).
