# Integrating Atlas into Recovery for All

Recovery for All is the first consumer of this SDK. It lives in a separate
repository (a Flutter app), so it cannot use Gradle's `includeBuild` across
repositories. The SDK therefore publishes to a versioned Maven repository and
Recovery for All depends on that coordinate like any other library.

The distribution contract, immutability rules, provenance, and clean-consumer
commands live in [`docs/ATLAS_MAVEN_DISTRIBUTION.md`](ATLAS_MAVEN_DISTRIBUTION.md).

## 1. Consume the published repository

The published repository is the generated `atlas-maven` branch:

```text
https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven
```

Published coordinates: group `com.intellitrip.atlas`, version `0.2.0`.

| Artifact | What it gives you |
| --- | --- |
| `atlas-engine` | `AtlasEngine`, the composition root. **Start here.** |
| `atlas-domain` | Feed-scoped identities, journeys, scope, geo types. KMP: every module publishes `*-jvm` + `*-wasm-js` variants; a Kotlin/JS or wasm consumer resolves the right variant from the same coordinate. |
| `atlas-contracts` | `RoutingProvider`, `RadarProvider`, `ProviderResult`, freshness |
| `atlas-scope` | `Focus` -> camera/data/render resolution |
| `atlas-routing` | `StaticNetworkPlanner`, `HybridRoutingProvider`, OTP adapter |
| `atlas-gtfs-static` | GTFS ZIP/CSV parsing, snapshots, disk stop-time index |
| `atlas-gtfs-realtime` | GTFS-RT vehicles, trip updates, alerts |
| `atlas-map` | Culling, LOD, clustering, interpolation, GeoJSON, route geometry |
| `atlas-weather` | Radar, NWS alerts, layer composer |
| `atlas-offline` | Capability report, staleness, basemap policy, basemap packages |

Deliberately **not** published:

- `atlas-map-android` — an Android library that depends on MapLibre. It renders,
  so it belongs to whichever host draws the map. Recovery for All has its own
  renderer or Flutter map; pulling MapLibre in through the engine would force a
  renderer choice on every consumer.
- `atlas-transit` — deleted by ADR-014. There is no module to publish.
- `app/`, `sample/host`, `sample/sdk-consumer` — build-time-only. The consumer
  deliberately resolves the published coordinates instead of project
  dependencies.

### Local staging for SDK development

If you are changing the SDK and need a consumer build before publishing:

```powershell
.\gradlew.bat publishAtlasToLocalRepo
```

Artifacts land in `build/atlas-maven/`; nothing is written to `~/.m2`, so an
ordinary build cannot publish by accident and the output is trivial to delete.
Point the consumer at that directory with its local override, never by editing
a checked-in machine path.

## 2. Point Recovery for All at the repository

In `android/settings.gradle.kts`, declare the repository in
`dependencyResolutionManagement`. The checked-in default is the portable remote
URL; a local property/environment override is optional development tooling:

```kotlin
val localProperties = java.util.Properties().apply {
    val file = java.io.File(rootDir, "local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Default is the published Atlas Maven branch. Developers may override
        // with ATLAS_MAVEN_REPO or atlas.maven.repo for local SDK work.
        val atlasMavenUrl = System.getenv("ATLAS_MAVEN_REPO")
            ?: localProperties.getProperty("atlas.maven.repo")
            ?: "https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven"
        maven { url = uri(atlasMavenUrl) }
    }
}
```

Do not add `file:///C:/Metro IntelliTrip/...` as a fallback. A clean clone and CI
must be able to resolve the pinned version without a producer checkout.

## 3. Depend on it

In `android/app/build.gradle.kts`:

```kotlin
dependencies {
    // The engine pulls the portable capabilities transitively. Depend on the
    // engine rather than individual modules so the wiring stays coherent.
    implementation("com.intellitrip.atlas:atlas-engine:0.2.0")
}
```

Because every core module is pure Kotlin (ADR-004) and Recovery for All already
sets `isCoreLibraryDesugaringEnabled = true`, the JDK time surface works without
extra configuration. Check `minSdk`: Atlas targets 26. If Recovery for All
supports lower, keep the bridge gated and return the existing unavailable
contract on older devices rather than silently raising the app minimum or
crashing at runtime.

### Migrating from 0.1.0 (breaking, 2026-11-08)

0.2.0 replaces `java.time` with `kotlinx.datetime` + `kotlin.time.Duration`
throughout (the KMP seam, `docs/blueprints/KMP-01-migration.md` M2b):

- `java.time.Instant` -> `kotlinx.datetime.Instant` (`Instant.now()` ->
  `Clock.System.now()`, `Instant.EPOCH` -> `Instant.DISTANT_PAST`)
- `java.time.Duration` -> `kotlin.time.Duration` (`Duration.ofSeconds(n)` ->
  `n.seconds`, `Duration.between(a,b)` -> `b-a`, `.toMillis()` ->
  `.inWholeMilliseconds`)
- `java.time.LocalDate` -> `kotlinx.datetime.LocalDate` (`LocalDate.of(y,m,d)` ->
  `LocalDate(y,m,d)`, `plusDays` -> `plus(n, DateTimeUnit.DAY)`)
- `java.time.ZoneId` -> `kotlinx.datetime.TimeZone` (`ZoneId.of` ->
  `TimeZone.of`, `systemDefault()` -> `currentSystemDefault()`)
- `AgencyId`/`FeedId` are now `data class` (were `@JvmInline value class`);
  construction and `.value` are unchanged.
- `atlas-domain` exports `kotlinx-datetime:0.6.2` +
  `kotlinx-serialization-core:1.8.0` as `api`, so engine-only consumers get
  them transitively. `kotlinx.datetime` needs no core-library desugaring
  (it is pure Kotlin), though keeping desugaring enabled is harmless.

`0.1.0` was never published as a portable Maven repository version; do not use
it as a compatibility escape hatch. Port the bridge to the documented 0.2.0
surface.

## 4. Call it from Flutter

Atlas is Kotlin with no Flutter bindings, so a platform channel is the integration
point. Recovery for All owns that adapter; the shape is:

- Kotlin side: one class taking a `MethodChannel`, constructing `AtlasEngine` with
  the providers Recovery for All wants, and exposing a small surface:
  `loadStaticNetwork`, `planJourney`, `loadLiveTransit`, `scopeFor`.
- Dart side: invokes those methods and renders the results with Flutter's own
  widgets and map, rather than embedding the Compose UI.

`AtlasEngine` takes every provider as optional, so Recovery for All can adopt one
capability at a time. It does not need to take the whole engine to start.

On Android, isolate the engine behind an API-26+ gate. The bridge on older
devices must return the existing `SDK_NOT_INSTALLED`/unavailable contract so the
host fallback remains reachable; do not load file-backed Atlas classes there.

## 5. Keep it honest

- **Only the engine boundary is public API.** Modules beneath it may change. If
  Recovery for All finds itself importing `atlas-routing` directly rather than
  going through `AtlasEngine`, that is a signal the engine boundary needs
  widening, not a reason to depend on the module.
- **Version in lockstep.** There is one version for the whole SDK (`0.2.0`), not
  per module. Bump it in `gradle.properties` and republish.
- **No secrets in this repository.** No API keys, no hosts. Configuration is the
  consumer's, at their end.
- **Remote routing stays consent-gated.** The SDK artifact does not authorize
  network calls or telemetry.
- A `PortabilityGuardTest` fails the build if a portable module imports Android. If
  it fires while you work, the fix belongs in the module, not in the guard.

## Verifying it worked

A standalone consumer lives in `sample/sdk-consumer/`. It is not part of the root
build, so it can only see published Maven artifacts:

```powershell
# Local staging check:
.\gradlew.bat publishAtlasToLocalRepo
.\gradlew.bat -p sample/sdk-consumer run

# Published repository check (fresh resolution):
.\gradlew.bat -p sample/sdk-consumer run `
  -PatlasMavenUrl=https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven `
  -PatlasVersion=0.2.0 `
  --refresh-dependencies
```

It fails if the engine POM is incomplete, if a portable module leaks an Android
dependency, or if the public API requires an unpublished class. CI runs the local
contract and, on release, the remote check in a fresh Gradle home. The remote
check is the proof that no machine-specific path is required.

```powershell
# In Recovery for All
flutter build apk --debug
```

If Gradle cannot resolve `com.intellitrip.atlas:*`, verify the repository block
is in `dependencyResolutionManagement` first; `FAIL_ON_PROJECT_REPOS` rejects a
repository declared inside `app/build.gradle.kts`. Also verify the pinned
version exists in `maven-metadata.xml` and its checksums, rather than falling
back to a local producer checkout.
