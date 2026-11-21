# Building Fluvour on GitHub

Push to `main` (or Actions > build > Run workflow). About 10 minutes later the APK is under Releases as "Fluvour build N". Install it over the old app.

## Secrets (repo Settings > Secrets and variables > Actions > New repository secret)
- `APP_KEYSTORE_PASSWORD` (required): opens `signing/fluvour-release.jks`.
- `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD` (optional): use these ONLY to keep updating an app that is already installed from a build signed with a different key (for example Android Studio's `debug.keystore`: alias `androiddebugkey`, passwords `android`). When `KEYSTORE_BASE64` is set, CI uses it instead of the file in the repo.
  Windows: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore"))`

## Rules
- Never change the signing key after you have installed a build, or the phone will refuse the update.
- versionCode is 100 + the run number, so every build is newer than the last.
- Settings and cards live in the phone's app data. Updating over the old app keeps them. Uninstalling wipes them.
