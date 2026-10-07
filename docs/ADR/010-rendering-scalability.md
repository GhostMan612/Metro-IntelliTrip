# ADR-010: Rendering scalability strategy
Status: Accepted
Context: System-wide rendering must remain possible.
Decision: Use spatial filtering, viewport culling, LOD, clustering, throttling, and interpolation rather than hard radius caps.
Implementation: pipeline is live snapshot -> spatial filtering -> viewport culling -> LOD/clustering -> bulk renderer/source update via MapLibre-native source/layer mechanisms (or other GPU-efficient bulk rendering). One Android View per vehicle or one heavyweight Compose marker per vehicle is not acceptable at system scale.
