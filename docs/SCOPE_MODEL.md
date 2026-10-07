# Scope Model

IntelliTrip scope is continuous and scalable. It is not a small enum of app modes.

## Three separate scopes
1. **Camera scope** — what the user is viewing.
2. **Data scope** — what the engine requests and retains.
3. **Render scope** — what subset/detail level is rendered.

## Focus targets
Focus may be:
- trip
- route
- vehicle
- radius
- viewport
- region
- system
- none

Focus influences camera, data requests, and render emphasis, but does not force a separate app mode.

## Continuous examples
- My trip → immediate surroundings → ½ mile → 2 miles → 5 miles → viewport → region → system

## Data scope rule
A citywide camera may request system data, but only viewport-intersecting and LOD-appropriate data is rendered.

## Render scope rule
At low zoom, vehicles cluster/simplify. At high zoom, individual vehicles render. Zoom transitions interpolate rather than hard-switch.

## State
`ScopeState` should include focus, cameraScope, dataScope, and renderScope. See `DOMAIN_MODEL.md`.
