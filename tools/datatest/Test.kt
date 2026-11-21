import app.fluvour.srs.*
import java.io.File

var failed = false
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) failed = true }

fun main(args: Array<String>) {
    check("sarawak facet id is stable", facetId("c1", FacetKind.SARAWAK) == facetId("c1", FacetKind.SARAWAK))
    check("sarawak id differs from word/meaning/clip", FacetKind.values().filter { it != FacetKind.SARAWAK }.none { facetId("c1", it) == facetId("c1", FacetKind.SARAWAK) })
    check("old facet ids unchanged", facetId("c1", FacetKind.WORD) == java.util.UUID.nameUUIDFromBytes("c1:WORD".toByteArray()).toString())
    check("old kinds still parse", listOf("WORD", "MEANING", "CLIP", "SARAWAK").all { runCatching { FacetKind.valueOf(it) }.isSuccess })
    check("sarawak: different word needs facet", needsSarawakFacet("tidak", "tak"))
    check("sarawak: same word makes none", !needsSarawakFacet("makan", "makan"))
    check("sarawak: same ignoring case/space", !needsSarawakFacet("makan", " Makan "))
    check("sarawak: empty or null makes none", !needsSarawakFacet("makan", "") && !needsSarawakFacet("makan", null) && !needsSarawakFacet("makan", "  "))

    // files (pass project root as arg 0)
    val root = args.getOrNull(0)
    if (root != null) {
        val sql = File("$root/backend/migrations/002_arabic_malay.sql").readText()
        check("supabase 002: adds 4 columns", listOf("variant_word", "variant_example", "root", "lemma").all { "add column if not exists $it" in sql })
        check("supabase 002: allows SARAWAK", "'SARAWAK'" in sql)
        val schema = File("$root/backend/schema.sql").readText()
        check("schema.sql: kind allows SARAWAK and cards has columns", "'SARAWAK'" in schema && "variant_word" in schema)
        val db = File("$root/app/src/main/kotlin/app/fluvour/data/AppDatabase.kt").readText()
        check("room: version 17 and migration registered", "version = 17" in db && "MIGRATION_15_16, MIGRATION_16_17" in db)
        listOf("variantWord", "variantExample", "root", "lemma").forEach { col ->
            check("room: migration adds $col nullable", "ADD COLUMN `$col` TEXT\")" in db)
        }
        check("room: no old migration changed", "Migration(15, 16)" in db && "Migration(14, 15)" in db)
        val entity = File("$root/app/src/main/kotlin/app/fluvour/data/Entities.kt").readText()
        check("entity: 4 optional fields default null", listOf("variantWord", "variantExample", "root", "lemma").all { "val $it: String? = null" in entity })
        val daos = File("$root/app/src/main/kotlin/app/fluvour/data/Daos.kt").readText()
        check("dao: card query selects new columns", "c.variantWord, c.variantExample, c.root, c.lemma" in daos)
        val sync = File("$root/app/src/main/kotlin/app/fluvour/sync/SyncRepository.kt").readText()
        check("sync: pulls and pushes new columns", listOf("variant_word", "variant_example", "\"root\"", "\"lemma\"").all { sync.split(it).size - 1 >= 2 })
    }
    println(if (failed) "FAILED" else "ALL OK")
}
