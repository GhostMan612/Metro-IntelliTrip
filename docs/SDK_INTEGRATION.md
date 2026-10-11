# Integrating Atlas into Recovery for All

Recovery for All is the intended first consumer of this SDK. It lives in a separate
repository (a Flutter app), so it cannot use Gradle's `includeBuild` across repos.
The SDK therefore publishes to a Maven coordinate and Recovery for All depends on
that coordinate like any other library.

## 1. Publish the SDK

From this repository:

```powershell
.\gradlew.bat publishAtlasToLocalRepo
```

Artifacts land in `build/atlas-maven/`. Nothing is written to `~/.m2`, so an
ordinary `build` can never publish by accident and the output is trivial to delete.

Published coordinates — group `com.intellitrip.atlas`, version `0.1.0`:

| Artifact | What it gives you |
| --- | --- |
| `atlas-engine` | `AtlasEngine`, the composition root. **Start here.** |
| `atlas-domain` | Feed-scoped identities, journeys, scope, geo types |
| `atlas-contracts` | `RoutingProvider`, `RadarProvider`, `ProviderResult`, freshness |
| `atlas-scope` | `Focus` → camera/data/render resolution |
| `atlas-routing` | `StaticNetworkPlanner`, `HybridRoutingProvider`, OTP adapter |
| `atlas-gtfs-static` | GTFS ZIP/CSV parsing, snapshots, disk stop-time index |
| `atlas-gtfs-realtime` | GTFS-RT vehicles, trip updates, alerts |
| `atlas-map` | Culling, LOD, clustering, interpolation, GeoJSON, route geometry |
| `atlas-weather` | Radar, NWS alerts, layer composer |
| `atlas-offline` | Capability report, staleness, basemap policy, basemap packages |

Deliberately **not** published:

- `atlas-map-android` — an Android library that depends on MapLibre. It renders, so
  it belongs to whichever host draws the map. Recovery for All has its own renderer
  or Flutter map; pulling MapLibre in through the engine would force a renderer
  choice on every consumer.
- `atlas-transit` — the quarantined prototype (ADR-014). Publishing it would put
  superseded code on a resolvable coordinate.
- `app/`, `sample/host` — build-time-only.

## 2. Point Recovery for All at the repo

In `C:\Recovery for All\android\settings.gradle.kts`, add the local repository to
`dependencyResolutionManagement`. Note it must be `RepositoriesMode.PREFER_SETTINGS`
or equivalent if the Flutter plugin loader manages repositories, and the repository
block has to be visible to the app module:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Atlas SDK, published by Metro-IntelliTrip's publishAtlasToLocalRepo.
        maven { url = uri("file:///C:/Metro%20IntelliTrip/build/atlas-maven") }
    }
}
```

The path is URL-encoded because it contains a space.

## 3. Depend on it

In `C:\Recovery for All\android\app\build.gradle.kts`:

```kotlin
dependencies {
    // The engine pulls the portable capabilities transitively. Depend on the
    // engine rather than individual modules so the wiring stays coherent.
    implementation("com.intellitrip.atlas:atlas-engine:0.1.0")
}
```

Because every core module is pure Kotlin (ADR-004) and Recovery for All already
sets `isCoreLibraryDesugaringEnabled = true`, `java.time` and the rest work without
extra configuration. Check `minSdk`: Atlas targets 26. If Recovery for All supports
lower, raise it or the engine will not run on those devices.

## 4. Call it from Flutter

Atlas is Kotlin with no Flutter bindings, so a platform channel is the integration
point. Recovery for All owns that adapter; the shape is:

- Kotlin side: one class taking a `MethodChannel`, constructing `AtlasEngine` with
  the providers Recovery for All wants, and exposing a small surface —
  `loadStaticNetwork`, `planJourney`, `loadLiveTransit`, `scopeFor`.
- Dart side: invokes those methods and renders the results with Flutter's own
  widgets and map, rather than embedding the Compose UI.

`AtlasEngine` takes every provider as optional, so Recovery for All can adopt one
capability at a time. It does not need to take the whole engine to start.

## 5. Keep it honest

- **Only the engine boundary is public API.** Modules beneath it may change. If
  Recovery for All finds itself importing `atlas-routing` directly rather than going
  through `AtlasEngine`, that is a signal the engine boundary needs widening — not a
  reason to depend on the module.
- **Version in lockstep.** There is one version for the whole SDK (`0.1.0`), not per
  module. Bump it in `build.gradle.kts` and republish.
- **No secrets in this repository.** No API keys, no hosts. Configuration is the
  consumer's, at their end.
- A `PortabilityGuardTest` fails the build if a portable module imports Android. If
  it fires while you work, the fix belongs in the module, not in the guard.

## Verifying it worked

```powershell
# In Recovery for All
flutter build apk --debug
```

If Gradle cannot resolve `com.intellitrip.atlas:*`, the repository block is not being
seen — check `dependencyResolutionManagement` first, since `FAIL_ON_PROJECT_REPOS`
would reject a repository declared inside `app/build.gradle.kts` instead.