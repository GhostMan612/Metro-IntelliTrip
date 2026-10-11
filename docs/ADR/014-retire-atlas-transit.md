# ADR-014: `core/atlas-transit` is retired, not migrated
Status: Accepted

Context: `core/atlas-transit` was the original prototype. It predates the module
split and violates the portability rule the rest of the repo follows (it is an
Android module inside `core/`). It has been quarantined and marked
non-authoritative since Phase 00, and Phase 01 onward moved its responsibilities
into `core/atlas-gtfs-static`, `core/atlas-gtfs-realtime` and `core/atlas-domain`.

## Evidence that it is unused

Verified 2026-10-11:

- `settings.gradle.kts:19` includes the module, so it still compiles.
- No module declares a dependency on `:core:atlas-transit`.
- No source file anywhere in `app/`, `core/` or `sample/` imports
  `com.intellitrip.transit`.

It therefore contributes nothing to any shipped artifact. Its behaviour is fully
superseded:

| Prototype concern | Current owner |
| --- | --- |
| `GtfsStatic.kt` feed parsing | `core/atlas-gtfs-static` |
| `GtfsRtClient.kt` realtime fetch | `core/atlas-gtfs-realtime` |
| `TransitRepository.kt` | superseded by `AtlasHost` composition root |
| `MetroTransitApi.kt` | superseded by the provider contracts |

## Decision

Formally retired. The files stay on disk unmodified because they are explicitly
protected user work, and deleting them is the user's call, not this project's. The
module is documented as non-authoritative so no future contributor treats it as a
starting point.

## Consequences

- One module still compiles that serves no purpose. Removing it from
  `settings.gradle.kts` is a one-line follow-up if the user approves deleting the
  sources.
- There is no migration work outstanding. The question that Phase 00 deferred
  ("quarantined/refactored before extraction") is answered: quarantine was
  sufficient, because everything of value was reimplemented against the contracts.

## Follow-up (needs user approval)

To fully close this out, delete `core/atlas-transit/` and drop
`include(":core:atlas-transit")` from `settings.gradle.kts`. Not performed here
because those files are protected.