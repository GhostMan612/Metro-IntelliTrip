# OpenTripPlanner self-hosting (free tier)

Decision recorded in ADR-007: routing is **hybrid**. The in-process
`StaticNetworkPlanner` answers first; OTP covers what it cannot (cross-agency
trips, fare-aware itineraries). This file is the deployment target for the OTP half.

## Cheapest durable free option: Oracle Cloud Always Free

Always-Free Ampere A1 shapes give 4 OCPU / 24 GB RAM and **do not expire**. That is
enough to build a Metro Transit graph and serve plans comfortably.

Rejected alternatives:

| Option | Why not |
| --- | --- |
| Fly.io / Render / Railway free tiers | Credits or monthly allowances that expire; scale-to-zero means OTP re-builds its graph on wake, which takes minutes |
| Google Cloud Run free tier | Scale-to-zero, same cold-start problem; free credit expires |
| Public OTP demo instances | Not SLA-backed, aggressive rate limits, and every user's origin/destination goes to a third party |

Oracle requires a credit card for signup verification, but the Always Free tier
itself is not charged. Idle Always Free instances *can* be reclaimed; keep the graph
warm with a periodic health check or accept an occasional rebuild.

## Bring-your-own

The app reads its OTP base URL from a Gradle property at build time. Nothing here is
committed: no host, no credentials, no keys.

```
gradlew.bat assembleRelease -Pintellitrip.otpEndpoint=https://<your-instance>/otp
```

Accepted forms are the deployment root (`https://host`), the OTP context root
(`https://host/otp`), the router root (`https://host/otp/routers/default`), or a
complete plan endpoint (`https://host/otp/routers/default/plan`). The adapter adds
only the missing path segments.

An unset or blank value means no remote provider is configured, and the engine uses
`StaticNetworkPlanner` directly. That is a supported configuration, not an error —
the app is fully functional offline this way. It is also the privacy-preserving
default: origin and destination never leave the device.

Use HTTPS for any host that is not on a trusted local network. Android's default
network security policy blocks cleartext HTTP, so an `http://` endpoint will fail at
runtime even though the property validator accepts it for local experiments.

## Graph build

OTP builds its graph from OSM data and GTFS feeds. For a Metro Transit deployment:

```
java -Xmx8G -jar otp.jar --build /opt/otp/graphs \
  --router default \
  /opt/otp/graphs/metro-transit.osm.pbf \
  https://svc.metrotransit.org/mtgtfs/gtfs.zip
```

Metro Transit's GTFS is public and needs no auth. The OSM extract can come from
Geofabrik (metro area extracts are a few hundred MB).

The built graph directory is what the service serves; rebuilding it after a GTFS
update is a recurring operational task. `StaticFeedRefresher` handles the app-side
feed, but the OTP graph is separate — they do not share state.

## Wiring in the app

`AtlasHost.engineForRouting` builds the local planner and, when `OTP_ENDPOINT` is
non-blank, wraps it in `HybridRoutingProvider` with
`OpenTripPlannerRoutingProvider`. The static network is passed to the adapter so
route, stop and trip ids returned by OTP are mapped back to local keys with
`OtpIdentityResolver`. Without that bridge a remote journey could be displayed but
never replay its route shape, stop or live vehicle.

Resolution is strict: exact ids (including `feed:stop` and `feed_stop` forms) first,
then unique names, then a unique name within 750 m of the OTP-supplied point. An
ambiguous name or a trip whose local route disagrees is left unresolved rather than
guessed. The raw OTP id remains feed-scoped, so the UI can still tell that the
journey is remote-only.

`HybridRoutingProvider` calls local first and only contacts the remote when local
returns no journeys, so with no remote configured the behavior is exactly the
default. Set `remoteFallback = false` for a strict offline mode that never touches
the network even if an endpoint is configured.

## Privacy

Only the plan request is sent: origin, destination, date/time, transfer limit and
the fixed `TRANSIT,WALK` mode. There is no device id, account, API key or analytics
payload. The request goes to the operator-configured endpoint, not to a public demo
instance. When the endpoint is blank — the default — no routing request is made at
all.

## Honest status

The adapter, response mapper, identity bridge and hybrid policy are implemented and
tested against structurally faithful OTP 2.x payloads. What is **not** verified in
this repository is a live end-to-end call against a running OTP instance: that needs
a deployment this repository does not have. The opt-in live test is gated on
`INTELLITRIP_OTP_ENDPOINT` and is skipped locally; field-level drift between OTP
versions remains a deployment-time check.