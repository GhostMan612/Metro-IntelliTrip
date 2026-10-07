# Product Brief — IntelliTrip

## One-liner
One Android app backed by a reusable sovereign geospatial engine that combines live transit, trip planning, realtime vehicle intelligence, weather/radar, and scalable map scope — with offline capability as an eventual target.

## Target user
Twin Cities riders and planners who want a calm, clean default map plus advanced/developer controls without a separate app fork.

## Pillars
1. **One app** — no lite/pro forks; advanced features behind settings.
2. **Sovereign engine** — Atlas engine is modular and portable so other apps can embed it.
3. **Continuous scope** — focus can be a trip, route, vehicle, radius, viewport, region, or system; camera/data/render scopes are separate.
4. **Provider-neutral** — Metro Transit, NOAA/NWS/MRMS/IEM, RainViewer, CARTO, MapLibre, and future providers plug into contracts.
5. **Plan-first** — no feature implementation without an accepted blueprint and freeze gate.

## Non-goals (v1)
- iOS/web/desktop
- Fare payment or rider accounts
- Production turn-by-turn navigation; routing is planning-oriented first
- Mandating one fixed maximum viewing radius

## Success criteria
- Live vehicles render and update smoothly at local and system-wide zooms
- Routing returns multimodal origin→destination options with transit/walking legs
- Weather/radar overlays compose without overwhelming the default view
- Realtime failures degrade gracefully with visible staleness
- Domain/provider contracts remain portable and testable
