# Stabilize and launch the local MVP

Type: task
Status: resolved
Blocked by:

Implement `.scratch/mvp-stabilization/spec.md` through public HTTP and data-transformation seams using red-green TDD slices.

## Comments

- 2026-08-12: Claimed after the evidence-backed three-agent review against baseline `a056878`.
- 2026-08-12: Resolved through red-green slices at the public HTTP and analytics transformation seams. Fixed startup re-entry, audit/query binding and pagination, SKU/multiplier/bundle review gating, idempotency ownership fencing and atomic success recording, BUSINESS/DEMO isolation, analytics aggregation/funnel correctness, ReviewCase paging/types, and the runnable Demo scenario timeline.
- 2026-08-12: Verification passed: backend `mvn test` (14 tests), frontend `npm test` (2 tests), frontend `npm run build`, OpenAPI YAML parse, and live HTTP/browser smoke through Vite for orders, analytics, review cases, Demo scenario creation/detail/idempotent replay/isolation and its 9-event SYNCED Timeline, plus customer-assistant health.
- 2026-08-12: Local MVP remains running at `http://127.0.0.1:5173`; the customer assistant is running in demo mode until an OpenAI-compatible model endpoint is configured.
- 2026-08-12: Final adversarial review closed the Demo traceability gap: each run now records one DEMO OrderVersion and AuditLog in the same transaction, defaults to operator `demo-ops`, and uses a scope-safe per-run demo customer code. Live database verification confirmed all four facts.
