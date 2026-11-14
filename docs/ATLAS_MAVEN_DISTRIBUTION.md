# Atlas Maven distribution contract

Status: **implemented** for the `0.2.0` SDK line.

The portable Atlas modules are published as an immutable Maven repository on
the `atlas-maven` branch of this repository. The branch is generated; do not
hand-edit it. The source of truth is `gradle.properties` (`atlasVersion`) and
the `publishAtlasToLocalRepo` task.

## Coordinates

- Group: `com.intellitrip.atlas`
- Current version: `0.2.0`
- Maven repository URL:
  `https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven`

The engine is the only required entry point:

```kotlin
repositories {
    maven {
        url = uri("https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven")
    }
}

dependencies {
    implementation("com.intellitrip.atlas:atlas-engine:0.2.0")
}
```

The `atlas-engine` POM and Gradle module metadata carry the portable capability
graph transitively. Consumers that need a capability directly may depend on it,
but should normally go through the engine boundary.

Deliberately not published:

- `atlas-map-android` (MapLibre renderer; host-owned)
- `atlas-web-spike` (application, not a library)
- the Android app and sample hosts

## Version policy

1. `atlasVersion` in `gradle.properties` is the only version source. Module
   publications read it through Gradle properties; do not hardcode a second
   version in a build file.
2. A version is published once. If a published artifact changes, bump the
   version. The staging tool fails rather than overwrite an existing version.
3. A version bump is a deliberate SDK event: update the consumer dependency in
   the same change, and document any API migration.
4. `0.1.0` was never published as a portable repository version and is
   superseded by `0.2.0` (the `kotlinx.datetime` migration). Consumers should
   not pin `0.1.0`.

## Building and staging locally

```powershell
.\gradlew.bat publishAtlasToLocalRepo
python tools/atlas_maven.py stage `
  --source build/atlas-maven `
  --destination ..\atlas-maven `
  --source-commit (git rev-parse HEAD) `
  --source-ref local `
  --repository-url https://github.com/GhostMan612/Metro-IntelliTrip
python tools/atlas_maven.py verify --repo ..\atlas-maven --version 0.2.0
```

`stage` is idempotent for an identical version. It verifies every published
file, refuses a changed file set or checksum, regenerates
`maven-metadata.xml`, writes the README, and records provenance under
`provenance/<version>.json`.

`verify` recomputes MD5, SHA-1, SHA-256, and SHA-512 for every artifact,
validates the POM graph and required JVM/wasm variants, checks the provenance
manifest, and rejects absolute local paths. It is the same gate used in CI.

## CI publication

`.github/workflows/atlas-publish.yml` runs on an `atlas-v*` tag (or a manual
dispatch whose input matches `gradle.properties`):

1. Requires a clean source checkout.
2. Builds `publishAtlasToLocalRepo`.
3. Stages into a worktree of `atlas-maven`; a version that already exists must
   be byte-identical.
4. Verifies the publication contract.
5. Commits and pushes only when the branch changes.
6. Runs the standalone consumer in a fresh `GRADLE_USER_HOME` against the
   remote branch URL. This is the proof that resolution does not depend on
   `C:\Metro IntelliTrip` or any other machine-specific path.

The branch commit message records the source commit. The provenance file records
the source repository, commit, ref, Gradle/Java versions, build command, and a
SHA-256 for every artifact.

## Clean consumer check

```powershell
# From the producer root, after the branch is published:
.\gradlew.bat -p sample/sdk-consumer run `
  -PatlasMavenUrl=https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven `
  -PatlasVersion=0.2.0 `
  --refresh-dependencies
```

The consumer project has `FAIL_ON_PROJECT_REPOS` and no source-module
dependencies, so it can only resolve published artifacts.

## Consumer integration rules

- The default repository URL is portable and checked into the consumer. A local
  override (for example `ATLAS_MAVEN_REPO` or `atlas.maven.repo`) is allowed
  for development, but must never be required for a clean clone or CI.
- The consumer must pin an explicit version. Dynamic versions, snapshots, and
  "latest" are not part of this contract.
- Remote routing remains separately consent-gated in the host. Publishing an
  engine artifact does not authorize network calls, telemetry, or remote
  provider use.
- If the SDK fails to initialize, the host must retain its existing offline and
  external-directions fallback. Treat an Atlas failure as an unavailable
  capability, not as a reason to block the rest of the app.
- On Android below API 26, the host must not load the engine's file-backed
  paths. The Recovery for All adapter gates on `Build.VERSION.SDK_INT >= 26`
  and reports the existing unavailable-capability contract on older devices.

## Rollback and incident response

- Do not rewrite or delete a published version directory; that breaks every
  consumer and the provenance guarantee.
- To withdraw a bad release, publish a fixed version and move consumers to it.
  If a security issue requires removing an artifact, document the incident and
  the replacement version in this file and in the release notes.
- The `atlas-maven` branch is append-only by workflow policy. Review changes to
  `tools/atlas_maven.py` and the publication workflow as security-sensitive.
