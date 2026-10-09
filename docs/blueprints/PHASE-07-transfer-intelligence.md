# Phase 07 — Transfer Intelligence

## Objective
Add transfer confidence signals via `TransferConnection` objects on journey options.

## Prerequisites
- Phase 06 routing working.

## In scope
- Transfer confidence model on `TransferConnection(arrivingLeg, departingLeg, transferStop, scheduledBuffer, predictedBuffer, walkingDuration, confidence, rationale)`.
- Realtime delay/headway inputs.
- `JourneyOption` holds 0..n `TransferConnection`s (no journey-level confidence).
- UI badges/warnings.

## Out of scope
- Full predictive ML.
- Navigation instructions.

## Components affected
- routing domain, UI presentation.

## Requirements
- Confidence values explainable enough for user trust.
- Unknown/stale realtime data yields `UNKNOWN` or conservative confidence, not false certainty.

## Tests/gates
- Confidence classification tests.
- Stale realtime behavior tests.

## Definition of Done
- Journey options expose transfer confidence with rationale via `TransferConnection` objects.

## Freeze gate
- Transfer model stable before offline/SDK phases.

## Artifacts
- Transfer confidence rules, tests, UI badges.

## Status
Implemented in `core/atlas-routing`. `TransferConfidenceScorer` evaluates each `TransferConnection` with explainable rules and always emits a rationale; `TransferIntelligenceService` annotates planned journeys and returns worst-case confidence plus warnings. Scoring is deliberately conservative: missing or stale realtime data yields `SCHEDULED_UNKNOWN` rather than false certainty, and a canceled departure is `LOW`. Confidence is never placed on the journey itself. UI badges are wired into the journey presentation when the trip-planner screen lands; the scoring API and warning list are ready for it.

## Handoff
- Phase 08 can prioritize local availability of needed transfer data.
