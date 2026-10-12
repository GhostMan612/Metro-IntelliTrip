# Security & Privacy

## Credentials
- No secrets committed to Git.
- Provider keys/tokens stored in secure platform storage or local untracked config.
- `local.properties`, keystores, `.env` files remain untracked.
- Providers declare auth requirements in contracts.

## Location privacy
- Location is used on-device for map centering/scope unless user explicitly chooses otherwise.
- Do not upload raw location traces by default.
- Advanced features requiring shared location must be opt-in.
- Permission is requested only from an explicit "locate me" tap, never at startup.
- Freshness and precision are decided separately. Only a stale fix is refused for camera
  movement; a fresh but coarse fix still centers the map, because refusing to honor a
  rider who granted only approximate location reads as a broken locate button. Precision
  governs the wording ("Location +/-12 m" vs "Approximate location, +/-2 km") and the size
  of the accuracy halo, never whether the map moves.
- A stale fix is still shown, labelled as last known with its age, rather than dropped.

## Transport
- HTTPS only for provider traffic.
- No cleartext API endpoints.

## Provider identification
- HTTP clients must send an appropriate `User-Agent` with an application/contact identifier as required by providers (notably NWS).
- The identifier is adapter-configurable; no false or misleading identity is hardcoded.
- No personal contact data, emails, or secrets are committed to the repo.

## Logging/redaction
- Never log auth headers, tokens, exact user locations, or full request URLs containing secrets.
- Realtime parse failures may log entity ids/error classes, not personal data.

## Trust boundaries
- Provider DTOs are sanitized at the edge.
- Malformed realtime entities are rejected/logged without crashing.
- Attribution/provider terms are surfaced in UI where required.
