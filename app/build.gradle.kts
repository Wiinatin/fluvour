plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
// Where the app looks for newer extractor builds. Taken from gradle.properties if set there; otherwise worked out from this
// folder's git "origin" remote (github.com/OWNER/REPO), so nothing needs typing. Empty (no git remote) = auto-update off.
val extractorManifestUrl: String = (project.findProperty("fluvour.extractorManifestUrl") as String?)?.takeIf { it.isNotBlank() } ?: runCatching {
    val p = ProcessBuilder("git", "remote", "get-url", "origin").directory(rootDir).redirectErrorStream(true).start()
    val remote = p.inputStream.bufferedReader().readText().trim(); p.waitFor()
    val m = Regex("github\\.com[:/]+([^/]+)/([^/]+?)(\\.git)?$").find(remote)
    if (m != null) "https://github.com/${m.groupValues[1]}/${m.groupValues[2]}/releases/download/extractor-latest/latest.json" else ""
}.getOrDefault("")

android {
    namespace = "app.fluvour"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.fluvour"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        // Public project URL and anon key (safe to ship; row-level security protects the data). Set in gradle.properties.
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("fluvour.supabaseUrl") ?: ""}\"")
        // Where the app looks for newer extractor builds (see EXTRACTOR_AUTOUPDATE.md). Empty = auto-update off.
        buildConfigField("String", "EXTRACTOR_MANIFEST_URL", "\"${extractorManifestUrl}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("fluvour.supabaseAnonKey") ?: ""}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md", "META-INF/NOTICE.md") } }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":srs"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")     // NewPipeExtractor needs it below Android 13
    implementation("androidx.media3:media3-exoplayer:1.5.1")                  // Phase 4: the in-app video player
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")                    // Mini player: media notification + background playback
    implementation("io.coil-kt:coil-compose:2.7.0")                            // thumbnails in the watch history
    implementation(project(":extractor-impl"))                                // Built-in NewPipeExtractor (version: newpipe.version in gradle.properties). The app replaces it at runtime with newer plugin builds.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.material3)
    implementation(libs.compose.icons); implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose); implementation(libs.navigation.compose)
    implementation(libs.lifecycle.vm); implementation(libs.lifecycle.runtime)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler); implementation(libs.hilt.navigation)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.kuromoji)   // Japanese word splitting, base forms, readings (2A-3)
    implementation(libs.jieba)      // Chinese word splitting (2A-3)
    // Screen lookup (2B): on-device text reading. Each script is its own library with its model bundled in the app.
    implementation("com.google.mlkit:text-recognition:16.0.1")             // Latin: en, es, fr, de, vi, id
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
