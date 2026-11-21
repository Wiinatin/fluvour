# Facet logic check (learning path step 2)

Runs the pure facet logic (`facetId`, `FacetRules`, `FacetQueue` in `srs/.../Facets.kt`) on a plain JVM.
Needs the Kotlin compiler (`kotlin-compiler-2.0.21.zip` from github.com JetBrains/kotlin releases).

1. `kotlinc srs/src/main/kotlin/app/fluvour/srs/Fsrs.kt srs/src/main/kotlin/app/fluvour/srs/Facets.kt tools/facettest/Test.kt -d out`
2. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line starts with `ok`, last line `ALL OK`)
