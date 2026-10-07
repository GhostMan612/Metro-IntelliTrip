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

## Transport
- HTTPS only for provider traffic.
- No cleartext API endpoints.

## Logging/redaction
- Never log auth headers, tokens, exact user locations, or full request URLs containing secrets.
- Realtime parse failures may log entity ids/error classes, not personal data.

## Trust boundaries
- Provider DTOs are sanitized at the edge.
- Malformed realtime entities are rejected/logged without crashing.
- Attribution/provider terms are surfaced in UI where required.
