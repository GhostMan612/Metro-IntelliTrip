# ADR-007: Routing is core
Status: Accepted
Context: Trip planning is central to Daily Driver/core product.
Decision: Routing is a core capability; OpenTripPlanner is a candidate provider, not a domain dependency.
Topology (research gate before implementation): Android app -> `RoutingProvider` -> self-hosted OTP service, trusted public service, local offline engine, or hybrid. The choice affects privacy, data sovereignty, network dependency, hosting cost, offline capability, latency, and reliability; it remains unresolved until a research gate closes.
