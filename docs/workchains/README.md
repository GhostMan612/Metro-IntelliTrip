# IntelliTrip deferred implementation workchains

This is a **proposed interoperable artifact-chain convention**, not a claim that it reproduces Pathfinder God's local or Meta Muse work verbatim. The named Pathfinder artifact-chain specification was not discoverable in the public `pathfinder_god` tree during this review. Adapt field names and manifest format once its authoritative artifacts are available.

**Purpose:** prevent a large audit from turning into TODO-shaped dead code. Defer uncertain work in **documents**, with precise contracts, owners and gates; do not add a runnable fake adapter or UI control to simulate completeness.

## Contract: one item, traceable artifacts

Each workchain ID has seven artifacts, generated/updated by agents only when evidence exists:

1. **SEED** — exact audit finding, source links/line ranges, baseline SHA, reproduction or static proof, severity.
2. **SPEC** — rider/SDK behavior, exact data contract, failure semantics, concurrency, persistence/privacy/security boundaries, exclusions, dependencies.
3. **PLAN** — concrete file map, ordered changes, interfaces and compatibility plan; avoid parallel modification of shared composition roots.
4. **IMPLEMENTATION** — branch/commit and diff inventory, distinguishing production wiring from fixtures or stubs.
5. **VERIFICATION** — runnable commands and CI/device/real-feed reports; negatives and skipped tests are evidence, not pass.
6. **REVIEW** — independent adversarial check for ghost features, no-op controls, incorrect degraded modes, simulated data in release builds, portability regressions.
7. **CLOSURE** — linked receipt index, explicit PASS/FAIL/BLOCKED per gate, decision and follow-up issues. Never set DONE on an unverified dependency.

## State machine

`DISCOVERED -> SPECCED -> READY -> IMPLEMENTING -> IMPLEMENTED -> VERIFYING -> REVIEWED -> CLOSED`

Allowed alternate states: `BLOCKED` (external data, licensing, missing provider, unsatisfied toolchain), `DEFERRED` (approved out-of-scope), `REOPENED` (new evidence or regression). No automatic promotion based on a model's narrative. Every transition cites a durable artifact and the upstream gate. `CLOSED` requires an independent CI or device receipt. A compile-only pass never proves network or UI availability.

## Canonical metadata for a future machine-readable manifest

```yaml
schema_version: 1
id: W-03
title: wasm GTFS-Realtime decoder
state: SPECCED
source_baseline: 15009e67cd94ddcc129f10ac87e6a36dc1bc2a2d
severity: high
platforms: [browser, wasmJs]
depends_on: []
scope: "Replace fail-closed decoder with a validated portable protobuf implementation"
out_of_scope: ["Android JVM decoder replacement", "synthetic production feed"]
files:
  - core/atlas-gtfs-realtime/src/wasmJsMain/kotlin/com/intellitrip/gtfsrt/WasmFeedDecoder.kt
acceptance:
  - "byte-for-byte-known GTFS-RT fixture decodes all supported vehicle/trip/alert entities"
  - "malformed payload surfaces explicit provider failure"
  - "real browser fetch, decode and render proven in Chromium"
receipts: []
blockers: []
```

A future orchestration tool should validate this schema, compare referenced SHAs to the current branch, and reject stale or contradictory evidence rather than carrying unverified “PASS” forward. The workchain should be runnable by any agent harness, but **agents must not push or merge autonomously**. Scope and testing sequence must honor project `AGENTS.md` and human approval.

## Deferred work inventory (2026-11-10)

The table below is the original acceptance inventory, not a current completion
claim. The local implementation checkpoint is separate and deliberately keeps
source implementation distinct from device, browser, external-service, and
release verification.

| ID | Work order | Definition of done / evidence |
| --- | --- | --- |
| W-01 | Vehicle follow and unsubscribe lifecycle | Latest selected `VehicleKey` maps to a fresh matching fix; camera follows while selected, handles stale/missing vehicle, stops on toggle-off/manual pan; UI+device tests |
| W-02 | Offline basemap install and binding | Document provider/license; SAF picker validates `.mbtiles`/style/size/digest/coverage, atomic store, renderer uses installed style, offline map restarts intact; security tests |
| W-03 | wasm GTFS-RT protobuf | Real portable wire decoder, fixture parity with JVM, bound errors, real browser decode; no synthetic fallback |
| W-04 | wasm durable snapshots/offline files | Rehydrate validated payload from IndexedDB on reload with staleness and integrity checks; no promise of offline basemap until tile store is durable; reload tests |
| W-05 | Walking mode | Choose either implemented street-network walk routing or clear read-only straight-line estimate with no route claim; Plan control respects mode; tests |
| W-06 | Weather alerts layer | Decide polygon geometry contract versus truthful summary-only UI; never render fabricated alert point; viewport refresh and attribution tests |
| W-07 | OTP/route identity | Version-appropriate OTP endpoint, transport and feed ID/stop ID mapping, config surface, credential/privacy checks, fallback semantics, real integration tests |
| W-08 | Scope preset anchoring | Center on live fresh fix or current map viewport (explicit priority); never force Minneapolis; camera/gesture tests |
| W-09 | Web MapLibre lifecycle + production data | Style-ready event and deferred source/radar install, style reset reattachment, real feed bootstrap, no demo data in production; Chromium eye-check |
| W-10 | SDK/CI/release boundary audit | Validate published KMP variants in external JVM/Android consumer; check actual JUnit executions, signing configuration and default branch; release evidence |

## Current local implementation checkpoint

This checkpoint describes source in the current local working tree. It is not
evidence of a GitHub merge, release approval, or closure of a workchain.

| ID | Source implementation status | Remaining proof or known gap |
| --- | --- | --- |
| W-01 | Partial | Selected-vehicle follow, fresh-fix updates, stale/missing pauses, manual-pan stop, and explicit stop are wired in source; map-camera/device lifecycle verification remains open. |
| W-02 | Partial | Basemap registry/import code exists; licensed archive validation, offline renderer binding, and device restart proof remain open. |
| W-03 | Implemented; targeted Wasm tests passed locally | Portable decoder and browser transport exist; real-feed/CORS and end-to-end rendering still need browser verification. |
| W-04 | Partial | GTFS static payload persistence/rehydration now waits for IndexedDB transaction completion and cleans canceled continuations; `OfflineFileStore.wasmJs.kt` remains process-memory-only, so offline basemap files are not durable. |
| W-05 | Implemented in source | Walk-only UI and planner behavior have regression tests; device interaction proof remains open. |
| W-06 | Partial | Coverage handling exists; Android alert geometry is not drawn on the map. |
| W-07 | Adapter only | No version-pinned live OTP service, verified identity mapping, or real integration receipt. |
| W-08 | Implemented in source | Presets use a deliberate map viewport before a fresh location, with current map scope and system fallback; resolver tests cover precedence. Gesture/camera behavior still needs device verification. |
| W-09 | Partial | Production browser entry/data path and renderer lifecycle code exist; radar frame replacement, restyle replay, omitted-layer clearing, and unchanged-source suppression are implemented. Chromium end-to-end and packaging verification are still required. |
| W-10 | Partial | Local Maven publication and JVM sample host exist; an independent consumer and Play/release gates remain open. |

Work-order follow-ups in this branch:

| Work order | Local status | Remaining proof |
| --- | --- | --- |
| WO-011 | Partial implementation; `test lint` and `assembleDebug` passed locally | Real-feed, Android-device, accessibility, and independent-review receipts. |
| WO-012 | Transaction staging and JVM regression coverage implemented; local build gates passed | Crash/fault recovery matrix, multi-tab/process policy, real-feed/device durability, and independent review. |
| WO-016 | Decoder and static-feed IndexedDB rehydration implemented; put/get await transaction completion and canceled operations release pending continuations; targeted Wasm tests passed locally | Browser live-feed/CORS/render test and offline reload/corruption/quota coverage. Wasm `OfflineFileStore` remains memory-only. |
| WO-017 | Partial basemap registry/storage implementation; Android engine reports offline capability only when a usable static snapshot exists | Licensed archive, complete MapLibre offline binding, durable restart, and physical-device verification. Snapshot availability does not prove offline basemap rendering. |
| WO-018 | OTP adapter and local SDK publication/sample path exist | Version-pinned live OTP, independent external consumer, signing, licensing, privacy, and owner release gates. |

## Suggested execution order

**Strike A — correctness:** W-08, W-05 (choose honest degraded UX), W-06, and W-01; work in one shared Android shell integration phase, **single batch verification after the code changes**.

**Strike B — browser portability:** W-09 renderer lifecycle first, then W-03 decoder and W-04 durable rehydration. Live browser feeds can be signed off only after fetch/decode/cache/map work as one end-to-end slice.

**Strike C — external dependencies:** W-02 offline packages, W-07 OTP and W-10 external consumer/Play. Keep blocker receipts until credentials, licensing or test environment are available.

Each strike gets a single one-shot work order with an explicit file inventory, non-overlapping ownership, rollback path, tests at the requested implementation gate, and final artifact receipt index. **Do not create runtime stubs for deferred features**; use explicit fail-closed states or label controls read-only instead.
