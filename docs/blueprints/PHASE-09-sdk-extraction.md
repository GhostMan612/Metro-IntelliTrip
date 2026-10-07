# Phase 09 — SDK Extraction

## Objective
Extract the reusable Atlas engine boundary for other apps.

## Prerequisites
- All prior phases frozen.

## In scope
- Public API boundary.
- Provider contract packaging.
- Domain portability checks.
- Recovery for All integration spike.

## Out of scope
- New product features.
- Rewriting in another language.

## Components affected
- core modules, docs, packaging.

## Requirements
- Domain modules remain Android-free pure Kotlin; `app/`, `platform/`, and adapters isolated.
- App shell depends only on public engine APIs.
- Provider adapters replaceable.
- Prototype code violating the portability rule is audited/quarantined/refactored before extraction.

## Tests/gates
- Portability tests.
- API compatibility tests.
- Smoke integration in second app or sample host.

## Definition of Done
- Atlas engine can be consumed independently of IntelliTrip UI.

## Freeze gate
- Public API accepted.

## Artifacts
- SDK docs, sample host, packaging config.

## Handoff
- Product teams can build on Atlas Engine.
