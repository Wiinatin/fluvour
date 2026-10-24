plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-library")
}
kotlin { jvmToolchain(17) }

// Pinned in gradle.properties (newpipe.version). The release workflow overrides it with -Pnewpipe.version=<latest tag>.
val newpipeVersion = (findProperty("newpipe.version") as String?) ?: "v0.26.5"

// Writes the version into the code so the backend can report it without reading resources.
val genDir = layout.buildDirectory.dir("generated/version")
val generateVersion = tasks.register("generateNewPipeVersion") {
    val out = genDir.get().file("app/fluvour/extractor/impl/NewPipeVersion.kt").asFile
    inputs.property("version", newpipeVersion)
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        out.writeText("package app.fluvour.extractor.impl\n\ninternal const val NEWPIPE_VERSION = \"$newpipeVersion\"\n")
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(genDir) }
tasks.named("compileKotlin") { dependsOn(generateVersion) }

dependencies {
    api(project(":extractor-api"))
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newpipeVersion")
}
