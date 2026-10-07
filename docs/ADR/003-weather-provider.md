# ADR-003: Weather/radar provider approach
Status: PROVISIONAL / RESEARCH-GATED
Context: Need simple radar overlay before deeper NOAA/NWS integration, but RainViewer terms, request limits, suitability, and licensing are not yet confirmed.
Decision: No weather/radar provider is unconditionally accepted for production yet. NOAA/NWS/MRMS/IEM and RainViewer remain viable candidates. RainViewer may be used for an MVP/prototype adapter behind `RadarProvider`, but it is not an unquestioned production dependency until terms/limits/licensing/suitability are confirmed.
Revisit when a provider's terms and data quality are verified.
