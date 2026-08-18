# MVP stabilization and local launch

Stabilize the current uncommitted backend and frontend implementation enough to run the local MVP through real HTTP interfaces.

## Scope

- Fix the confirmed P1 correctness findings that block application startup, order creation/query, audit retrieval, and accurate MVP analytics.
- Preserve BUSINESS and DEMO data-scope isolation.
- Add regression tests at public seams before each behavior fix.
- Run backend tests and frontend typecheck/build.
- Start the backend and frontend locally, then verify their HTTP entry points.

## Non-goals

- Complete the still-open P0 Excel closed-loop ticket.
- Implement external provider integrations.
- Publish, push, deploy, or rewrite unrelated worktree changes.

## Acceptance

- The Spring Boot application can start repeatedly against the same PostgreSQL database.
- The existing internal-order integration test passes, including idempotent replay and audit retrieval.
- Order-list filtering and pagination obey the OpenAPI query contract.
- Disabled or incomplete source SKU mappings never enter automatic fulfillment.
- Frontend analytics use the selected aggregate window rather than the series window.
- Backend and frontend are running locally and respond to HTTP smoke probes.
