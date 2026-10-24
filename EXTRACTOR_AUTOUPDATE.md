# NewPipe auto-update

## How it works
1. A GitHub Action (`.github/workflows/extractor-release.yml`) runs every 6 hours. If NewPipeExtractor has a new release, it builds a plugin APK from it and publishes it plus a small `latest.json`.
2. The app reads `latest.json` at start (at most every 6 hours) and whenever a video load or search fails. If it lists a newer version, the app downloads the APK, checks its SHA-256, test-loads it on a real video, and only then switches to it.
3. If anything goes wrong, the app keeps the working extractor (the last good download, or the one built into the app).

## Setup (once)
Nothing to type: the app finds its update address from your git `origin` remote (github.com/OWNER/REPO) when you build.
1. Push the project to GitHub, then open Actions > "Extractor plugin" > Run workflow once.
2. Build the app from that same git folder. (No git remote when building = auto-update stays off. To use another address, set `fluvour.extractorManifestUrl` in `gradle.properties`.)
   - The release repo must be public (the app downloads without logging in). If your app repo is private, make a tiny public repo, set the repo variable `EXTRACTOR_RELEASE_REPO` to `OWNER/that-repo`, and set `fluvour.extractorManifestUrl` in `gradle.properties` to its `.../releases/download/extractor-latest/latest.json` address.
   - GitHub pauses scheduled workflows after 60 days without repo activity; re-run it by hand if that happens.

## When you add new NewPipe features
Only `extractor-impl/.../NewPipeBackend.kt` may touch NewPipe classes. For something new (say, channel pages), add plain types and a function to `extractor-api/.../ExtractorApi.kt`, implement it in `NewPipeBackend`, and bump `ExtractorApi.VERSION`. Apps older than the bump simply ignore the newer plugins.

## If you turn on R8/minify later
Add: `-keep class app.fluvour.extractor.api.** { *; }`
