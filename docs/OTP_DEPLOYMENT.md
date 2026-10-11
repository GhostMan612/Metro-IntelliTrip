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

The app reads its OTP base URL from configuration. Nothing here is committed: no
host, no credentials, no keys.

```
OTP_BASE_URL=https://<your-instance>/otp
```

An unset or blank value means no remote provider is configured, and
`HybridRoutingProvider` runs local-only. That is a supported configuration, not an
error — the app is fully functional offline this way.

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

`AtlasHost.routingPlanner` returns the static planner. To enable the remote half,
compose instead:

```kotlin
val local = StaticNetworkPlanner(network)
val remote = OpenTripPlannerRoutingProvider(
    baseUrl = BuildConfig.OTP_BASE_URL,
    feedId = feedId,
    transport = HttpRoutingTransport(),   // supply your own HTTP client
)
val planner = HybridRoutingProvider(local, remote)
```

`HybridRoutingProvider` calls local first and only contacts the remote when local
returns nothing, so with no remote configured the behaviour is exactly the current
one. Set `remoteFallback = false` for a strict offline mode that never touches the
network.

## Honest status

The adapter, the response mapper and the hybrid policy are implemented and tested.
What is **not** verified here is a live end-to-end call against a running OTP
instance: that needs a deployment this repository does not have. The mapper is
tested against a structurally faithful OTP 2.x plan payload captured by hand, so
field-level drift between OTP versions is still possible.