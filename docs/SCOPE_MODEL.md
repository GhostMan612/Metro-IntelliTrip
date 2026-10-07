# Scope Model

IntelliTrip scope is continuous and scalable. It is not a small enum of app modes.

## Resolution pipeline
```text
Focus (+ user intent)
  -> ScopePolicy / ScopeResolver
      -> CameraScope
      -> DataScope
      -> RenderScope
```
Policy/resolver semantics are established here and in `DOMAIN_MODEL.md` scope primitives; Phase 00/01 documents the primitives and resolver contract, Phase 03 consumes them, Phase 05 expands policy/UI/advanced behavior.

## Three separate scopes
1. **Camera scope** — what the user is viewing.
2. **Data scope** — what the engine requests and retains.
3. **Render scope** — what subset/detail level is rendered.

## Focus targets
Focus may be:
- trip (`TripKey`)
- route (`RouteKey`)
- vehicle (`VehicleKey`)
- radius
- viewport
- region (bounds + agencies)
- multiple agencies
- system
- trip corridor plus nearby transit
- none

Focus influences camera, data requests, and render emphasis, but does not force a separate app mode.

## Resolver cases
The `ScopeResolver` maps each Focus (plus defaults) into camera/data/render scopes:
- **trip** — camera follows trip path; data scoped to that trip + immediate surroundings; render emphasizes trip shape, stops, assigned vehicle.
- **route** — camera fits route bounds; data scoped to route's trips/vehicles; render emphasizes route shape and stops.
- **vehicle** — camera tracks vehicle; data scoped to vehicle/trip; render emphasizes the vehicle marker.
- **radius** — camera centered at radius center at derived zoom; data scoped to radius; render clustered per zoom.
- **viewport** — camera/data bounded by viewport; render culled to viewport.
- **region** — bounds across one or more agencies; system-wide fetch may apply with local filtering.
- **multiple agencies** — data scope lists explicit `agencies`; resolver derives union bounds.
- **system** — data scope `systemWide = true`; render heavily clustered/LOD-reduced.
- **trip corridor plus nearby transit** — data along trip's corridor (`TripCorridor`) plus nearby transit feeds; camera fits corridor bounds.

## Continuous examples
- My trip → immediate surroundings → ½ mile → 2 miles → 5 miles → viewport → region → system

## Data scope rule
`DataScope` supports agency lists, route/trip/vehicle sets, bounds, radius, time window/freshness (`maxAge`), alerts inclusion, and system-wide flag. A citywide camera may request system data, but only viewport-intersecting and LOD-appropriate data is rendered. Adapters may fetch a broader result and filter locally to satisfy the scope.

## Render scope rule
At low zoom, vehicles cluster/simplify. At high zoom, individual vehicles render. Zoom transitions interpolate rather than hard-switch.

## State
`ScopeState` includes focus, cameraScope, dataScope, and renderScope. See `DOMAIN_MODEL.md`.
