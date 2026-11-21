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
// Voice packs live on the same repo as the extractor plugin, under the "voicepacks" release. Same address, other tag and file name.
// Gradle property fluvour.voicePacksIndexUrl overrides it; empty means packs are off.
val voicePacksIndexUrl: String = (project.findProperty("fluvour.voicePacksIndexUrl") as String?)?.takeIf { it.isNotBlank() }
    ?: extractorManifestUrl.takeIf { it.endsWith("/releases/download/extractor-latest/latest.json") }
        ?.removeSuffix("/releases/download/extractor-latest/latest.json")?.plus("/releases/download/voicepacks/packs.json")
    ?: ""

// Release signing. The same key must sign every build, or Android refuses to update the installed app over the old one.
// APP_KEYSTORE_PASSWORD (GitHub secret) opens signing/fluvour-release.jks. To keep updating an app that was installed from a build
// signed with another key (for example Android Studio's debug key), set the secret KEYSTORE_BASE64 too: CI then uses that file
// (with KEY_ALIAS / KEY_PASSWORD) instead. See docs/GITHUB_BUILD.md.
val ksPass: String? = System.getenv("APP_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val ksFile: File = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: rootProject.file("signing/fluvour-release.jks")
val ksAlias: String = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "fluvour"
val ksKeyPass: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: ksPass
val runNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

android {
    namespace = "app.fluvour"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.fluvour"
        minSdk = 26
        targetSdk = 35
        // Voice packs (sherpa-onnx) ship native libraries. Every current phone is 64-bit, so only arm64 is packed: it keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 100 + runNumber   // grows with every GitHub build, so each one installs over the last
        versionName = "0.0.1"
        // Public project URL and anon key (safe to ship; row-level security protects the data). Set in gradle.properties.
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("fluvour.supabaseUrl") ?: ""}\"")
        // Where the app looks for newer extractor builds (see EXTRACTOR_AUTOUPDATE.md). Empty = auto-update off.
        buildConfigField("String", "EXTRACTOR_MANIFEST_URL", "\"${extractorManifestUrl}\"")
        // Where the app finds the voice-pack list (packs.json on the "voicepacks" release of the same repo). Empty = packs off.
        buildConfigField("String", "VOICEPACKS_INDEX_URL", "\"${voicePacksIndexUrl}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("fluvour.supabaseAnonKey") ?: ""}\"")
    }
    signingConfigs {
        if (ksPass != null) {
            create("release") { storeFile = ksFile; storePassword = ksPass; keyAlias = ksAlias; keyPassword = ksKeyPass }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPass != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }   // CI builds the APK; lint is for Android Studio
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
    implementation("androidx.media3:media3-datasource:1.5.1")                 // saved clip cache (SimpleCache, CacheDataSource)
    implementation("androidx.media3:media3-database:1.5.1")
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
    // Voice packs: on-device neural text-to-speech. Download sherpa-onnx-1.13.8.aar into app/libs (see docs/VOICE_PACKS.md).
    // Version 1.13.8 on purpose: 2.0.0 removed espeak-ng, which the official Piper and Kokoro voices need.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.work:work-runtime-ktx:2.9.1")                    // Background clip library (roadmap 4.2 phase 2)
    implementation("com.github.luben:zstd-jni:1.5.6-4@aar")   // unpacks Anki .apkg decks (Heisig import)
    implementation(libs.kuromoji)   // Japanese word splitting, base forms, readings (2A-3)
    implementation(libs.jieba)      // Chinese word splitting (2A-3)
    // Screen lookup (2B): on-device text reading. Each script is its own library with its model bundled in the app.
    implementation("com.google.mlkit:text-recognition:16.0.1")             // Latin: en, es, fr, de, vi, id
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
