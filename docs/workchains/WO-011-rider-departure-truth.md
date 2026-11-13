# WO-011 — Rider-truth and arrival notification integrity

**Severity:** P0 rider correctness. **Status:** PARTIALLY IMPLEMENTED on the local branch; runtime verification and independent review remain open.
**Target baseline:** `ccf662c7de6e9b0b34e20994366f5ecfec5d5d40` on `master`.  
**Source:** 2026-11-11 audit, `app/src/main/java/com/intellitrip/IntelliTripViewModel.kt`, `ui/DepartureBoard.kt`, `ui/ArrivalCue.kt`, `core/atlas-domain/.../ServiceCalendar.kt`.

## SEED — independently visible defects

1. `nearbyDepartures()` accepts a stop-time when its trip is active on **any** of yesterday/today/tomorrow, then independently calls `DepartureBoard.resolveServiceTime()` to choose the next wall-clock occurrence. This dissociates the chosen time from the calendar service date; a trip can be advertised on a day where no service runs. GTFS times >=24:00 and midnight boundaries amplify the error.
2. `DepartureBoard.build()` computes `live = liveTrips.contains(scheduled.tripId)` and then reports `isLive = live` even when `ScheduledDeparture.reportedTime == null`. Presence of a recent vehicle location is *not* a verified predicted departure at that stop.
3. `onLocateMe()` calls `ArrivalCue.playIfAllowed()` whenever a fresh location update is within 100m of the final journey destination; no per-journey one-shot latch or re-entry hysteresis is shown. Repeated location fixes can trigger repeated cues; approximate GPS could falsely signal arrival.

## SPEC — observable contract

### Service-day mapping
- Derive the agency zone from the effective service agency; for a single-zone Metro Transit feed use its authoritative zone. Do not infer from device zone.
- Enumerate the finite service-date candidates covering the request horizon, including yesterday for >24h GTFS times and trips that continue past midnight.
- For each candidate date, call the shared `ServiceCalendar.isActive(trip.serviceKey, date)` **before** constructing that date's occurrence. Construct the scheduled departure as `date.atStartOfDayIn(zone) + serviceTime.secondsSinceServiceDayStart`; preserve the originating service date alongside the departure.
- Filter to allowed time horizon and already-departed thresholds *after* constructing candidates. Deduplicate by (feedId, tripId, stopId, serviceDate, occurrenceStart) so a frequency template or neighboring service dates cannot double-list an instance.
- Handle DST transitions using timezone-aware conversion. Do not wrap `25:30:00` to `01:30` with no service-date association.
- Do not fabricate journeys for no-calendar-service days; distinguish an empty eligible schedule from a missing snapshot/index error.

### Realtime provenance
- Model **scheduled departure**, **observed vehicle position**, and **stop-specific trip-update prediction** as separate provenance states. The UI may show a vehicle-reporting indicator for a matching current vehicle but must not label the **time** Live without a fresh stop-specific prediction.
- Prefer an actual GTFS-RT StopTimeUpdate predicted timestamp, subject to matching (feedId, tripId, stopId, serviceDate) and freshness. Reject mismatched service days and canceled/skipped stops, with clear rider labels.
- Without a prediction, show scheduled time and explicit Schedule provenance even if a vehicle is reporting elsewhere; never substitute the current clock or vehicle timestamp as a departure.
- Carry prediction source timestamp, age and schedule relationship into accessible labels. Invalid/stale updates must downgrade to schedule with a reason, never silently retain an old live ETA.

### Arrival cue
- Cue at most once per (selectedJourneyId, target alighting stop, arrival window). Arm only with active selected journey, fresh appropriate-accuracy user fix, consent and sound settings.
- Coarse fix with accuracy wider than the 100m threshold must not be treated as proof the rider has arrived. Optionally show an informational proximity banner instead of sound.
- Preserve a one-shot latch across normal location callbacks and recomposition; rearm only after an explicit new journey or defined state reset. Re-entering the geofence for the same trip must not spam.
- Respect ringer silent / audio focus, do not create a `ToneGenerator` for each repeating fix, and surface the cue as an accessible status.

## PLAN — execution order and ownership
1. Introduce a testable pure function `eligibleDeparturesForServiceDate` in the departure domain; change the ViewModel board construction to preserve service-date identity and map frequency occurrences deterministically.
2. Extend the scheduled/realtime departure model and adapter path to carry stop-specific prediction **only when available**; leave an explicit Schedule fallback otherwise. Reconcile route and trip keys at feed scope.
3. Add a small arrival-cue state machine independent of Android audio resources, injectable clock and location-accuracy policy; wire a single UI/audio adapter.
4. Update board, stop detail, map chips and accessibility wording to use provenance, then check basic performance on the BLU.
5. Only after all implementation code is in place, run the agreed verification gate.

## Files anticipated
- `app/src/main/java/com/intellitrip/IntelliTripViewModel.kt`
- `app/src/main/java/com/intellitrip/ui/DepartureBoard.kt`; `DepartureBoardTest.kt`
- `app/src/main/java/com/intellitrip/ui/ArrivalCue.kt` and new pure state-machine/test files
- `core/atlas-domain/.../ServiceCalendar.kt` as needed; GTFS-RT and trip-update adapter path only if necessary for real predictions
- `app/src/main/java/com/intellitrip/ui/ExploreScreen.kt`; `MapFeatureSheet.kt`

## VERIFICATION — required receipts
- Calendar cases: weekday, weekend, excluded holiday, added holiday, late night 25:30, 24:00 boundary, repeated frequency, and DST transitions. Each expected stop time must be pinned to the correct service date.
- Realtime cases: vehicle-only -> Schedule; valid stop update -> Live/predicted; wrong trip/service day/stop -> ignored; stale prediction -> Schedule; skipped/canceled -> explicit status.
- Arrival cases: 20 identical fresh fixes inside radius -> exactly one cue; stale GPS -> zero; coarse inaccurate fix -> zero; silent mode -> zero; new journey -> one new eligible cue; background/resume -> no duplicates.
- Real Metro Transit fixture: verify departure list against one known trip's calendar and indexed stop times; full JVM + Android tests, lint, assemble, device semantics and screenshot evidence.
- Gate reports must identify tests actually executed. No skipped/absent real-feed fixture counted as pass.

## REVIEW / CLOSURE
**Local checkpoint:** implementation and regression tests now cover service-date-bound departures, realtime provenance, arrival-cue state, and planning-time controls. The repository's `test lint` and `assembleDebug` gates passed locally. This does not satisfy the required real-feed, device, accessibility, or independent-review receipts. Keep this workchain open until those receipts exist; release remains NO-GO if any departure is presented as predicted without supporting data.

**Out of scope:** redesigning the planner algorithm, speculative rider ETA, OTP integration, background location permission and full visual overhaul.

**Dependency:** none, can implement before other workstreams. `W-11` is an extension to the existing `docs/workchains/README.md` inventory.
