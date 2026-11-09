# Play release track

`docs/RELEASE.md` describes signing + building; this is the store-facing
checklist. Android-only: the web shell ships through static hosting, not Play.

## Build & upload

1. Set signing env vars (see `docs/RELEASE.md`): `KEYSTORE_FILE`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them the release
   APK stays unsigned (debug-signed artifacts can never pass as release).
2. Build the AAB: `.\gradlew.bat app:bundleRelease`
3. Google Play Console → Production → Create release → upload AAB, name the
   release, review, staged rollout (10–20% first), halt if crash rate spikes.

Store listing still needs human-authored copy, screenshots (phone 6.5" +
7" + tablet at minimum), and the feature graphic — those are content, not
code; write them once dimensions are shot from the BLU.

## Data safety (form answers, draft)

- We collect **no** personal data and share **no** data: no account, no
  analytics, no ads SDK.
- Location: on-device only, requested only after the locate tap; never sent.
- Network calls (yours/Wi-Fi): Metro Transit GTFS feeds, CARTO tiles,
  RainViewer frames (provisional — may swap to NWS-only), NWS alerts.
  Each sends only the tile/frame request; the tile host sees your IP and
  the viewport, the weather host sees your coordinates. Disclose the host
  list in the privacy policy's data-safety section.
- Stripe/OTP: remote routing is disabled by default (local-only planner).
  Enabling it sends origins/destinations to the server — do not ship with it
  enabled until the notice exists.

## Content rating / declarations

- Transit app: no ads, no membership, no user-generated content.
- Target audience: general. Export compliance: no encryption beyond HTTPS.
- If a restricted permission is ever added (background location is
  *not* requested today), revisit this checklist; Play will ask.

## Versioning

`versionCode` and `versionName` bump together (see `docs/RELEASE.md`).
First upload stays `1` / `0.1.0`; the engine SDK at `0.2.0` is a separate
version line (`com.intellitrip.atlas:0.2.0`) and need not equal the app's.
