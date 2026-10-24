pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
rootProject.name = "fluvour"
include(":app", ":srs", ":extractor-api", ":extractor-impl", ":extractor-plugin")
