---
status: accepted
---

# Use one application shell for workbenches and existing pages

The role-workbench refactor replaces the authenticated application's shell globally: the prototype-aligned sidebar, role selector beneath the brand, and no persistent top header apply to workbenches and existing business pages alike. Existing URLs and page capabilities remain intact; the four workbenches receive prototype-level visual refinement first, while older object and administration pages initially need only remain usable inside the new shell.

A workbench-only shell and a separate `/v2` application were rejected because either would make operators cross between two navigation systems and would create lasting duplication in routing, layout, and shared components.
