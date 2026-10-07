# ADR-004: Engine modularity
Status: Accepted
Context: Atlas engine must be reusable outside IntelliTrip.
Decision: Keep domain pure Kotlin with no Android framework imports in reusable modules; platform/app/adapters are isolated in their own modules. Providers and apps live in separate modules from the domain.
Portability gate: prototype code that violates this architecture (e.g., Android imports in core) must be audited, quarantined, or refactored before it is treated as authoritative.
