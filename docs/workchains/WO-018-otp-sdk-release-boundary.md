# WO-018 — OTP, external SDK consumer and Play boundary evidence

**Priority:** P2 external dependencies; release NO-GO until relevant owner gates pass. **Status:** SPECCED / BLOCKED on live service and owner release decisions.  
**Baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40` / `master`. **Existing workchains:** W-07 OTP/identity, W-10 SDK/CI/release.
**Source:** `app/src/main/java/com/intellitrip/AtlasHost.kt`, `core/atlas-routing/.../OpenTripPlannerRoutingProvider.kt` / `HybridRoutingProvider.kt`, `docs/OTP_DEPLOYMENT.md`, `docs/SDK_INTEGRATION.md`, `app/build.gradle.kts`, `docs/PLAY_TRACK.md`.

## SEED and constraint
- Host `engineForRouting(network)` injects `StaticNetworkPlanner(network)` only. The OTP adapter exists but no live cross-agency deployment, app configuration, or real-response compatibility receipt is present.
- OTP adapter constructs a legacy `/otp/routers/default/plan?...` URL and maps identifiers using route short/long names rather than guaranteed GTFS route IDs; OTP versions differ. Static identity reconciliation is not proven.
- `publishAtlasToLocalRepo` publishes KMP variants at `0.2.0` but build success alone cannot prove a separate Android/Flutter or Kotlin/Wasm consumer resolves artifacts and works with its host toolchain.
- Release signing configuration requires user-supplied keystore secrets, Play content/privacy declarations and asset rights. No signing keys or service secrets may be committed; no release claims based on unsigned build.

## SPEC A — OTP service integration (W-07)
1. Decide and pin a **specific OTP version and supported API contract**, verifying whether its itinerary API is REST, GraphQL or another endpoint; do not assume the existing path works for arbitrary OTP releases. Build compatibility fixture responses from the selected version.
2. Configure endpoint via a release-safe setting supplied at build/deploy time (or opt-in UI), never an unprotected hardcoded user secret. Use TLS, bounded timeouts, constrained retries and explicit local-only fallback.
3. Graph ingest: Metro GTFS + OSM derived from legitimate sources, track feed content hash and graph build timestamp; reject mismatch/stale graph as live schedule truth. Graph rebuild and health probes with a sustainable deployment runbook.
4. Route identity: map server response agency/route/trip/stop identifiers to feed-scoped local keys using real GTFS IDs, not route short name. If mapping fails, return a remote route with source metadata that cannot masquerade as locally verified map geometry.
5. Trip geometry, departures, fares and transfers: parse only source-supported fields. Do not claim fare-aware itineraries when server data lacks fares. Distinguish synthetic walking connectors and server-provided street legs.
6. Privacy and cost: before any remote routing, disclose that origin/destination coordinates leave the device and who operates the endpoint; support a local-only opt-out. Never poll OTP when configured off.
7. Hybrid fallback test: valid local plan stays local; legitimate no-local-route may query remote only if opt-in; remote auth/timeout/error leaves local status accurate and does not fabricate journeys.

## SPEC B — external SDK portability (W-10)
- Publish versioned KMP metadata/JVM/Wasm variants to the controlled local Maven repo; pin coordinates and dependency graph, verify checksum and published POM/module metadata consistency.
- Add **separate** external minimal JVM consumer project and Android consumer smoke (using independent Gradle settings), installing only declared published coordinates; no project() shortcuts. Where appropriate, exercise Recovery for All via an isolated Flutter platform-channel adapter without modifying its production app until approved.
- Validate minSdk, Kotlin ABI/runtime compatibility, lifecycle, license transitive dependencies, desugaring, Java-free common modules and browser variant build. Version-change policy: breaking API needs semver and documented migrations.
- SDK's hosted engine must not force Android/MapLibre on pure consumers. Portable platform stubs must be explicit unavailable capabilities, not successful-looking placeholders.

## SPEC C — Play production readiness
- Validate signed **AAB** with upload key supplied solely from owner's secure storage. Verify R8/minified installed artifact on at least supported BLU and Moto hardware.
- Document Data safety with accurate provider destinations, local vs remote routing privacy, analytics absence, NWS/CARTO/RainViewer license and attribution, internet permissions and account/child safeguards.
- Perform production cold start without debug feed, real-feed plan journey, background/foreground/reboot offline restoration, talkback/200% type, network deny fallback, crash monitoring and rate-limit testing.
- Store listing screenshots and feature graphic must reflect real current app, not concept mockups. Do not upload or publish without explicit owner go-ahead.

## PLAN — separate implementation batches (one spec, independently closable gates)

**T1 OTP experimental integration:** pin service API and credentials, implement adapter and identity mapping on a feature branch, run server fixtures and real endpoint integration tests. Keep flag disabled unless fully verified.

**T2 external SDK consumer proof:** validate KMP publications and minimal stand-alone JVM/Android/Wasm consumer builds, close portability regressions.

**T3 release readiness:** environment-only signing, Play data declarations, visual assets, physical-device tests and owner review. Do not combine independent risk gates into a single "green" claim.

## Files expected
- `core/atlas-routing/src/commonMain/kotlin/com/intellitrip/routing/OpenTripPlannerRoutingProvider.kt` and `HybridRoutingProvider.kt`
- `app/src/main/java/com/intellitrip/AtlasHost.kt` and typed settings + opt-in privacy UI
- `core/atlas-domain` / contracts identity mappings if necessary
- `docs/OTP_DEPLOYMENT.md`, `docs/SDK_INTEGRATION.md`, `docs/RELEASE.md`, `docs/PLAY_TRACK.md`, `docs/PRIVACY_POLICY.md`
- New independent `sample/consumer-*` projects and CI/integration tests; never change unrelated protected user files

## VERIFICATION / owner gates
- Real version-pinned OTP instance, graph hash/date receipt, one valid cross-agency route mapped to source identities and geometry; invalid route IDs and endpoint mismatch produce explicit error, not fabricated path.
- Local-only produces no OTP calls; opt-in remote sends coordinates only after notice; 401/403/429/5xx/timeout and graph stale fail safely.
- External consumer resolves Maven coordinates `0.2.0` in clean independent project, builds and runs representative engine API; Wasm consumer validates compile + browser runtime where supported.
- CI, lint, Android instrumentation, actual real-feed journey, signed AAB install+launch, attribution/licensing and owner release checklist; each reported separately.
- NO remote connection or Play upload in this documentation PR. Release NO-GO until owner supplies endpoint/signing/license decisions.

## OUT OF SCOPE
Changing canonical GitHub default branch automatically, provisioning cloud infrastructure, changing user subscription/billing, or committing secrets. Treat stale/unsupported OTP endpoint as BLOCKED until actual API shape is verified.
