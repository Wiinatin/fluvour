// Not an app anyone installs. It exists so Android's build tools turn NewPipeExtractor and everything it needs
// (with the same desugaring as the app) into one APK that the app can load as code at runtime.
// Build: ./gradlew :extractor-plugin:assembleDebug -Pnewpipe.version=v0.26.5
plugins { alias(libs.plugins.android.application) }
android {
    namespace = "app.fluvour.extractor.plugin"
    compileSdk = 35
    defaultConfig { minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "plugin" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    packaging { resources { excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md", "META-INF/NOTICE.md") } }
}
dependencies {
    implementation(project(":extractor-impl"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
