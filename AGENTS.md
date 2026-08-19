# Repository agent guidance

## Agent skills

### Issue tracker

Issues are tracked as **GitHub Issues** in `rgfan123/zimu` (new issues are filed there via `gh`). `.scratch/` is the historical archive for past tickets; migrated tickets carry a `**GitHub:** <url>` link. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default Matt Pocock triage role names. See `docs/agents/triage-labels.md`.

### Domain docs

This repository uses a single-context domain layout rooted at `CONTEXT.md`. See `docs/agents/domain.md`.

## CI gate: local reproduction

The CI gate at `.github/workflows/ci.yml` runs these commands on every push and pull request.
Reproduce it locally with:

```bash
# Backend: full test suite (~15-18 min; requires Docker for Testcontainers)
cd backend && mvn test

# Frontend: install + typecheck + unit tests + build
cd frontend && npm ci && npm run typecheck && npm test && npm run build
```

Green bar:

- Backend: `Tests run: ~771, Failures: 0, Errors: 0`（约 9 例环境依赖 skip 属正常：两个真实样表
  解析用例与部分 JD 用例用 `assumeTrue` 守卫，CI 上无样表/无凭据时会正常跳过）。
- Frontend: typecheck、`npm test` 和 build 全部通过（既有 chunk size 警告不算失败）。

If the gate is red, look at the surefire report of the failing test class
（`backend/target/surefire-reports/*.txt`，CI 上会作为 artifact 上传），or the failing
`node --test` subtest name in the frontend log. Prefer fixing the workflow config or the test —
never delete tests or add skips to make CI green.
