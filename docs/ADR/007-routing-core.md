# ADR-007: Routing is core, and its topology is hybrid
Status: Accepted (topology resolved)
Context: Trip planning is central to Daily Driver/core product.
Decision: Routing is a core capability; OpenTripPlanner is a candidate provider, not a domain dependency.

## Topology (resolved)

**Hybrid.** The in-process `StaticNetworkPlanner` answers first; an OTP service
covers what it cannot. Concretely: `HybridRoutingProvider(local, remote)`.

Rationale:

- **Local first is a privacy property, not just latency.** When the local planner
  answers, the user's origin and destination never leave the device. That matters
  for transit data specifically, which describes individual movements.
- **Local is offline-capable.** The GTFS snapshot is already stored, so planning
  works with no network at all. A remote-only design would break that.
- **Remote covers a real gap.** Cross-agency trips and fare-aware itineraries are
  not reachable from a single feed, and OTP's RAPTOR algorithm is better than the
  in-process planner at dense networks.
- **Degradation stays honest.** If both fail, the result is `PartialResult` with
  the gaps recorded, never an empty `Success` that reads as "no trips exist".

## Hosting

Self-hosted OTP on a durable free tier. Oracle Cloud Always Free (4 OCPU / 24 GB,
no expiry) is the only option that is genuinely free long-term; every other free
tier either expires or scales to zero, and OTP's graph rebuild on wake takes
minutes. See `docs/OTP_DEPLOYMENT.md` for the deployment recipe.

No remote endpoint is committed and none is required. With no URL configured the
app runs local-only, which is a supported mode, not a degraded one.

## Consequences

- Two code paths and two failure modes, accepted knowingly.
- The OTP graph and the app's GTFS snapshot are separate operational concerns and
  must be refreshed independently.
- The response mapper is verified against a hand-authored OTP 2.x payload, not a
  live capture; field drift between OTP versions remains possible.

## Supersedes

The research gate recorded in earlier revisions of this ADR is closed. The gate
existed to protect the domain contract from premature commitment; the contract did
not change as a result, which was the actual risk it guarded against.