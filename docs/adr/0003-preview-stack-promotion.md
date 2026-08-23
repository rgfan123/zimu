---
status: accepted
---

# Promote the refactor through a preview stack; 8088 stays live and untouched

During the prototype refactor, the real business stack on port 8088 keeps serving its
current converged build — neither its frontend nor its backend is rolled back or
mutated mid-flight. All refactor work happens on the `codex/issue-103-prototype-refactor`
branch and is served on a separate local preview port (18103, behind the local auth
proxy), pointing at the same backend contracts.

Promotion is per phase, and a phase replaces the 8088 frontend only after both gates
pass:

1. **Visual gate** — side-by-side comparison against the pinned prototype
   (`zimu-frontend-prototype.html`, SHA-256 `adca4a89…`) in the same viewport,
   accepted explicitly by the user. A green typecheck/test/build run cannot
   substitute for this (ADR 0001).
2. **Contract gate** — the existing route-harness suites, `identityBoundary`,
   `navigation`, typecheck, full tests and build, per the acceptance sections of
   #104–#112.

Until a phase passes both gates, 8088 continues to serve the previous accepted
version. Rollback, if ever needed, is a redeploy of the last accepted image, not a
git revert on the live branch.

## Considered options

- Roll 8088 back to an older checkpoint first: rejected — it would also regress the
  backend contract that newer work (converge-103, issue-89 operator map) depends on.
- Roll back only the 8088 frontend: rejected — creates a frontend/backend contract
  mismatch on the only stack operators actually use.
- Refactor directly on the live 8088 frontend: rejected — every intermediate state
  would be exposed to real operators, and visual acceptance would happen after the
  fact instead of as a gate.
