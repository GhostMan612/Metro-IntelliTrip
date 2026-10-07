# ADR-006: Continuous scope model
Status: Accepted
Context: Discrete MY_TRIP/ROUTE/REGION/SYSTEM modes are too rigid.
Decision: Use `ScopeState` with focus plus separate camera/data/render scopes. Scope resolves through `Focus -> ScopePolicy/ScopeResolver -> CameraScope/DataScope/RenderScope`; Phase 00/01 own the primitives and resolver contract, Phase 03 consumes them, Phase 05 expands policy/UI/advanced behavior.
