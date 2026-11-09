# Release

Ship steps stub. Implemented: minified, desugared release builds with opt-in
signing. Not implemented: store listing copy, screenshots, data-safety answers.

Store-facing track (AAB upload, listing, data-safety, declarations) is now
described in `docs/PLAY_TRACK.md`.

## Signing (opt-in)

`app/build.gradle.kts` wires `releaseFromEnv` only when these are set:

```powershell
$env:KEYSTORE_FILE="path\to\upload-key.jks"
$env:KEYSTORE_PASSWORD="..."
$env:KEY_ALIAS="upload"
$env:KEY_PASSWORD="..."
.\gradlew.bat assembleRelease
```

Create the upload key once:

```powershell
keytool -genkeypair -keystore upload-key.jks -alias upload -keyalg RSA `
  -keysize 2048 -validity 10000
```

The `.jks` file and passwords are never committed (see `.gitignore`).
Without the env vars the build is unsigned by design.

## Play

- Developer account: $25 one-time.
- First upload is manual review; updates are not.
- `versionCode`/`versionName` live in `app/build.gradle.kts` — bump together.
