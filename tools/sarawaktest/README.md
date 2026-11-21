# Sarawak deck check (handoff step 5)

Runs the pure pack parser, card mapping and unlock rule (`reading/SarawakPack.kt`) on a plain JVM. Needs the Kotlin compiler (`kotlin-compiler-2.0.21.zip`).

1. `kotlinc srs/src/main/kotlin/app/fluvour/srs/Fsrs.kt srs/src/main/kotlin/app/fluvour/srs/Facets.kt app/src/main/kotlin/app/fluvour/reading/HeisigTier.kt app/src/main/kotlin/app/fluvour/reading/SarawakPack.kt app/src/main/kotlin/app/fluvour/reading/SarawakSuggestPrompt.kt tools/sarawaktest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt app/src/main/assets/sarawak/pack.tsv`  (every line `ok`, last line `ALL OK`)

Not covered (needs a device): the Android loop in `SarawakRepository` that calls `CardRepository.save`.
