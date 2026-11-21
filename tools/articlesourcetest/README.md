# Article source parsing check (Articles tab, step 4)

Runs the pure parts (HtmlText, Rss, NhkParse, EnglishParse, IndonesianParse, ThaiParse) on a plain JVM. The sample pages in `Test.kt` are ASSUMED shapes, not copies of the live NHK pages:
a pass proves the parsing code works, not that NHK still uses these formats. Chat cannot reach nhk.or.jp, so the live check is the phone test.

1. Extract `MiniJson` (from `reading/Yomitan.kt`, the lines of `object MiniJson` up to its closing `}` at column 0) into `MiniJson.kt` with `package app.fluvour.reading`.
2. `kotlinc MiniJson.kt reading/articles/ArticleSource.kt reading/articles/HtmlText.kt reading/articles/Rss.kt reading/articles/NhkParse.kt reading/articles/EnglishParse.kt reading/articles/IndonesianParse.kt reading/articles/ThaiParse.kt Test.kt -d out`
3. `java -cp "out:kotlinc/lib/kotlin-stdlib.jar" TestKt`   (every line should start with `ok`, the last line is `ALL OK`)
