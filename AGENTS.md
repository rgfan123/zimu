# Repository agent guidance

## Agent skills

### Issue tracker

Issues are tracked as **GitHub Issues** in `rgfan123/zimu` (new issues are filed there via `gh`). `.scratch/` is the historical archive for past tickets; migrated tickets carry a `**GitHub:** <url>` link. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default Matt Pocock triage role names. See `docs/agents/triage-labels.md`.

### Domain docs

This repository uses a single-context domain layout rooted at `CONTEXT.md`. See `docs/agents/domain.md`.

## CI gate: local reproduction

> **先确认 JDK**：项目 target 是 Java 21，`mvn` 若默认落到 JDK 26（如 Homebrew 的
> `openjdk@26`）会让 `OrderDraftComplexityApiTest` 出现与改动无关的假失败——同一用例在
> JDK 24 下通过。若本机没有 JDK 21，跑测试前先
> `export JAVA_HOME=$(/usr/libexec/java_home -v 24)`，或用 `mvn -version` 确认实际使用的版本。
> CI 用 Temurin 21，不受此影响。
>
> 已知机制之一：JDK 26 的 `HttpClientImpl#translateSendAsyncExecFailure` 按 `sendAsync`
> 规范把异常多包一层 IOException，只认第一层 `getCause()` 的代码会认不出精确失败类型
> （wecom 媒体下载一族 3 个红曾全是这个成因，已在 `WecomMediaDownloader` 改为遍历成因链）。
> **判据：本地红而 CI 绿时，先 `export JAVA_HOME=$(/usr/libexec/java_home -v 24)` 复跑再下结论。**
>
> **前端组件测试（`npx vitest run`）的超时红先当假红查**：单个用例渲染整页 antd 组件，
> 空载 1.5~4.5s，机器负载高（如并行 `mvn test`）时会稳定越过预算报
> `Test timed out`，而断言从未失败。**判据：先 `npx vitest run --testTimeout=60000`
> 复跑一遍；仍红才是真 bug。**（默认预算已放宽到 30s，见 `frontend/vitest.config.ts`。）
>
> **再确认 JD 客户端模式**：`application.yml` 把 `app.jd.client-mode` 映射到环境变量
> `JD_LOP_CLIENT_MODE`（默认 MOCK）。本地 shell 若导出 `JD_LOP_CLIENT_MODE=REAL`
> （如为了连真实京东沙箱），会让 `OutboundReconApiTest` 断言 `client_mode=MOCK` 假失败——
> 跑测试前 `unset JD_LOP_CLIENT_MODE` 或 `export JD_LOP_CLIENT_MODE=MOCK`。CI 无此变量，不受影响。

The CI gate at `.github/workflows/ci.yml` runs these commands on every push and pull request.
Reproduce it locally with:

```bash
# Backend: full test suite (~15-18 min; requires Docker for Testcontainers)
cd backend && mvn test

# Frontend: install + typecheck + unit tests + build
cd frontend && npm ci && npm run typecheck && npm test && npm run build
```

Green bar:

- Backend: `Tests run: ~2437, Failures: 0, Errors: 0`（约 10 例环境依赖 skip 属正常：两个真实样表
  解析用例与部分 JD 用例用 `assumeTrue` 守卫，CI 上无样表/无凭据时会正常跳过）。
- Frontend: typecheck、`npm test` 和 build 全部通过（既有 chunk size 警告不算失败）。

If the gate is red, look at the surefire report of the failing test class
（`backend/target/surefire-reports/*.txt`，CI 上会作为 artifact 上传），or the failing
`node --test` subtest name in the frontend log. Prefer fixing the workflow config or the test —
never delete tests or add skips to make CI green.
