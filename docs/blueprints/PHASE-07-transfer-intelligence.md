# Phase 07 — Transfer Intelligence

## Objective
Add transfer confidence signals to journey options.

## Prerequisites
- Phase 06 routing working.

## In scope
- Transfer confidence model.
- Realtime delay/headway inputs.
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
- Journey options expose transfer confidence with rationale.

## Freeze gate
- Transfer model stable before offline/SDK phases.

## Artifacts
- Transfer confidence rules, tests, UI badges.

## Handoff
- Phase 08 can prioritize local availability of needed transfer data.
