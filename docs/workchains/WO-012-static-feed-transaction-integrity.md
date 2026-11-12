# WO-012 — GTFS snapshot and offline transaction integrity

**Severity:** P0 data correctness and retention. **Status:** SPECCED; not fixed or tested in this PR.  
**Target:** `master` commit `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40`.  
**Source:** `core/atlas-gtfs-static/src/commonMain/kotlin/com/intellitrip/gtfs/StaticFeedSnapshotStore.kt`; `StaticFeedRefresher.kt`; `GtfsStaticParser.kt`; `StaticFeedBinaryCache.kt`.

## SEED — why this merits an isolated workstream

- `StaticFeedSnapshotStore.newStopTimeSink()` places `stop-times.dat` and `stop-times.idx` under shared `staging-stop-times`. `writeStagedPayload()` likewise uses the shared `gtfs.zip.staging`. Two concurrent refresh invocations, retries, or distinct feed IDs can overwrite or move another transaction's staging artifacts.
- `activateStaged()` moves the staged payload and any shared index artifacts, then moves the active pointer. No transaction identifier binds the new archive to its index. A race can make a fully active snapshot contain cross-generation data.
- Both `StaticFeedRefresher.activate()` and `refreshBlocking()` currently construct new metadata carrying `active?.metadata?.validFrom` and `validTo`. That can mark a newly downloaded service period with the previous feed's dates.
- `recordValidators()` and `writeCache()` occur after pointer activation; crash windows and best-effort cache writes need an explicit reconciliation policy.
- Reader cache retention/pruning are optimized; that makes ownership and concurrency semantics especially important before further optimization.

## SPEC — invariants

1. **Single-generation consistency:** for each active pointer, metadata/feed archive/stop-time index/binary cache (if present) all come from the same validated ZIP; never expose a mixed generation.
2. **Independent transactions:** each refresh receives opaque feed-scoped transaction ID and unique isolated staging directory, used for archive, stop-time writer and index. Avoid path construction from untrusted feed id; use an encoded/hash identity and containment checks.
3. **No activation before validation:** parse/validate referential integrity, service calendar, stop-time index writer completion, checksums, metadata and file completeness before any active pointer changes.
4. **Stable commit:** freeze staged contents, write the immutable candidate snapshot directory, atomically commit its active pointer, and only then update optional validators/cache and garbage collection. Under loss of atomic rename, fail without silently pretending atomicity.
5. **Crash resilience:** after crash before pointer swap, old active remains; after pointer swap, new active loads intact. Orphan stages are discoverable and safely cleaned without deleting an in-use generation.
6. **Concurrent policy:** either serialize refreshes per feed through a well-defined lock or reject stale competing commit via generation check. Distinct feeds refresh independently. No cross-process requirement unless explicitly supported, but process concurrency must be guaranteed.
7. **Metadata truth:** derive new validFrom/validTo/version/agency timezone from newly parsed current feed when available; otherwise set to null. Do not inherit previous period unconditionally. Persist accurate hash and fetch time with a traceable source URL.
8. **Conditional HTTP truth:** 304 only accepted when a healthy active snapshot exists; never suppress a missing/corrupt snapshot. Validator changes must never refer to an uncommitted payload.
9. **Cache validation:** binary cache must have compatible format, feed-scoped identity, version and integrity check. On corruption, fall back to same-generation archive/index or surface a recoverable failure.
10. **Space discipline:** retention keeps last healthy generation plus current reader leases, with bounded cleanup and deterministic disk-pressure errors.

## PLAN — file map and work order

**A. Transaction API** — introduce immutable `RefreshTransaction`/`StagedFeed` types, scoped paths and single-owner lifecycle in snapshot store, without changing the public engine facade. Ensure the sink cannot be accidentally reused after abort. Write the transaction state alongside stage for post-crash recovery.

**B. Refresher integration** — both suspending `refresh()` and blocking `refreshBlocking()` call the *same* orchestrator for acquisition, stage creation, parse, commit and cleanup; remove duplicated branches and drift. Ensure callers receiving `RefreshOutcome.Activated` can immediately reopen matching stop-time index.

**C. Service-period metadata** — parse `feed_info` / calendar tables to derive actual metadata, preserving feed validation outcomes. Add explicit unknowns rather than stale borrowed values.

**D. Cleanup and reader ownership** — retain last-known-good snapshot and leased readers; clean incomplete stages on startup and post-success without cross-feed deletion. Ensure index data exposed after pointer switch is same-generation.

**E. Error taxonomy** — distinguish missing/invalid feed, insufficient disk, parse failure, atomic-rename unavailable, conditional request, and corrupted active snapshot. UI shows recoverable actions while retained data remains usable.

## Files anticipated
- `core/atlas-gtfs-static/src/commonMain/kotlin/com/intellitrip/gtfs/StaticFeedSnapshotStore.kt`
- `core/atlas-gtfs-static/src/commonMain/kotlin/com/intellitrip/gtfs/StaticFeedRefresher.kt`
- `core/atlas-gtfs-static/src/commonMain/kotlin/com/intellitrip/gtfs/GtfsStaticParser.kt`
- `core/atlas-gtfs-static/src/commonMain/kotlin/com/intellitrip/gtfs/PlatformFileStore.kt`, JVM/Wasm implementations if transactional semantics require it
- `core/atlas-gtfs-static/src/jvmTest/kotlin/com/intellitrip/gtfs/StaticFeedRefreshTest.kt` and new staged-recovery tests
- `app/src/main/java/com/intellitrip/IntelliTripViewModel.kt` only for status/error propagation

## VERIFICATION — mandatory
- Two overlapping same-feed refreshes (A first ZIP, B second ZIP) complete out of order; final active is exactly one valid generation, never mixed metadata/index/archive; distinct feeds do not share staging.
- Fault injections before/during/after stage write, parse, index close, snapshot completion, pointer move, cache creation and cleanup. At every recovery point the old or new complete snapshot remains usable.
- 304 with healthy active -> retained; 304 with missing active -> forced unconditional fetch or error (not false freshness).
- A future service-period feed must not inherit obsolete validFrom/validTo; checks against source calendar/feed metadata.
- Crash simulation + app restart: index lookup, routes, planner and saved places remain coherent. Repeated refresh bounded disk growth. No path traversal or illegal snapshot names.
- Real 29 MB Metro Transit archive, JVM suites, Android instrumented tests, lint, assembleDebug/release, offline BLU restart; report memory/time regressions.

## REVIEW / CLOSURE
No functional change is made by this PR. Before implementation merge: independent review of transaction state machine, concurrent test determinism, no-reader-deletion guarantee, and actual executed test reports. Do not widen scope to unrelated renderer or visual work.

**Dependencies:** independent of WO-011; recommended before offline basemap and web durable rehydration. `W-12` extends existing workchain inventory.
