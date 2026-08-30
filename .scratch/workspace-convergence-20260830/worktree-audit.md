# 子牧 linked worktree 全量只读审计

观察时间：2026-08-30 22:50 +08:00。Git common-dir 注册数：54（含本轮新增的隔离收敛
worktree；开工前为 53）。`dirty` 顺序为 staged / unstaged / untracked。

封口刷新：2026-08-31 00:16 +08:00。只重新观察两条 `DEFERRED_ACTIVE`（它们允许继续移动）
和收敛目标；其余 51 条仍保留全面扫描时的原始快照，不伪装成同时刻状态。收敛目标的
`FINAL_SELF` 表示本文件所在已提交 HEAD，clean 状态由 `verify-convergence.sh` 在该 HEAD 上
冻结并复核，精确 SHA 用 `git rev-parse HEAD` 获取，避免在 commit 内容中伪造自引用 hash。

远端栏只报告本地 tracking-ref 证据：`NO_TRACKING` 表示没有 upstream；
`LOCAL_REF_ONLY` 表示只看到本地缓存引用，**不代表 GitHub 当前、已 push 或已 fetch**。
`PROTECTED_UNKNOWN` 表示在限时只读观察内不可可靠读取，绝不按 clean 或可清理处理。

`RETAIN_*` 仅表示保留并等待独立票据判断，不是“无价值”或删除授权。

| Worktree | Branch | HEAD | dirty | Remote evidence | Disposition |
|---|---|---:|---:|---|---|
| `/Users/jerry/Documents/子牧` | `master` | `8e2ca85c6b29` | - | `-` | PROTECTED_UNKNOWN |
| `/Users/jerry/.codex/worktrees/0796/子牧` | `codex/bundle-archive-multiplatform-design` | `383d91e6414c` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/.codex/worktrees/187b/子牧` | `DETACHED` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/6deb/子牧` | `DETACHED` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/8667/子牧` | `DETACHED` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/8fb8/子牧` | `DETACHED` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/a510/子牧` | `DETACHED` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/agent-file-intake/子牧` | `DETACHED` | `8e2ca85c6b29` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/e342/子牧` | `DETACHED` | `b65ec8cdae7d` | 0/0/1 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/e8f8/子牧` | `codex/design-deepening-02-verify` | `cbcdb5293b39` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/.codex/worktrees/e8f8/子牧-ticket03` | `codex/design-deepening-03-jd-isc-gateway` | `03fddd76c03a` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/.codex/worktrees/f974/子牧` | `DETACHED` | `b65ec8cdae7d` | 0/0/1 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/.codex/worktrees/kehuzx-chain/子牧` | `codex/zimu-kehuzx-integration` | `64b98f6de3ac` | 0/0/0 | `LOCAL_REF_ONLY:origin/codex/zimu-kehuzx-integration` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/Codex/2026-08-30/referenced-chatgpt-conversation-this-is-an-2/zimu-sku-masterdata-repair` | `codex/sku-masterdata-repair` | `1fb4f5350c5d` | 0/0/0 | `NO_TRACKING` | DEFERRED_ACTIVE |
| `/Users/jerry/Documents/子牧/.claude/worktrees/agent-aa4c1e4557940246d` | `worktree-agent-aa4c1e4557940246d` | `1014ece4fac7` | 0/8/3 | `NO_TRACKING` | FROZEN_EVIDENCE |
| `/Users/jerry/Documents/子牧/.claude/worktrees/agent-ad3cdf374dc65f4b6` | `worktree-agent-ad3cdf374dc65f4b6` | `1014ece4fac7` | 0/0/2 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/agent-adba3982a73aecb10` | `worktree-agent-adba3982a73aecb10` | `ea39de38286a` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/beef-lamb-gift-costing-a05bdc` | `claude/zimuyewu-frontend-ia-ux-audit-ecca9d` | `b65ec8cdae7d` | 0/15/18 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/busy-bell-8e6cb8` | `claude/ops-product-management-mcp-decd43` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/codebase-design-8789ba` | `claude/amazing-chatelet-448c42` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/dsh-h06-gateway-auth` | `dsh/h06-gateway-auth` | `e3f302f983b3` | - | `-` | PROTECTED_UNKNOWN |
| `/Users/jerry/Documents/子牧/.claude/worktrees/excellent-system-frontend-3140df` | `claude/excellent-system-frontend-3140df` | `b65ec8cdae7d` | 0/1/9 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/feixiang-shipment-har-analysis-f870f7` | `claude/feixiang-shipment-har-analysis-f870f7` | `b65ec8cdae7d` | 1/9/32 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/jufubao-pull-order-issue-641416` | `claude/jufubao-pull-order-issue-641416` | `b65ec8cdae7d` | 0/23/12 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/local-service-jd-sdk-switch-7d7408` | `DETACHED` | `1014ece4fac7` | 0/0/99 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/master-cleanup-merge-324ca4` | `claude/session-1-7b07fa` | `72aa61a89b64` | 0/0/37 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/prototype-design-50af32` | `claude/eager-mccarthy-0777c8` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/vigorous-mclean-2f2206` | `claude/vigorous-mclean-2f2206` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/wayfinder-continue-a5e10b` | `claude/wayfinder-continue-a5e10b` | `effa8a215aa2` | 0/1/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/wayfinder-grilling-decision-check-5979ea` | `claude/uiux-batch-closeout-578024` | `b65ec8cdae7d` | 0/0/7 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/wecom-bot-customer-service-d79db6` | `claude/wecom-bot-customer-service-d79db6` | `b65ec8cdae7d` | 0/0/3 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/Documents/子牧/.claude/worktrees/youthful-feistel-68705d` | `claude/youthful-feistel-68705d` | `b65ec8cdae7d` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/auto-pull` | `jry/scheduled-pull-and-ship` | `3f6e89e7bb85` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/caishixian-json` | `jry/caishixian-json-pull` | `737d8a6aa22b` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/feixiang-json` | `jry/feixiang-json-pull` | `3e49e62e9c48` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/feixiang-push` | `jry/feixiang-online-push` | `4b15131887ae` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/fix-baseline-four-reds` | `jry/fix-baseline-four-reds` | `1a415cdca5f1` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/integration` | `jry/integration-20260828` | `6994cf2f6235` | 0/3/30 | `NO_TRACKING` | DEFERRED_ACTIVE |
| `/Users/jerry/zimu-work/jufubao-login` | `jry/jufubao-login-and-credentials` | `e05d5a6b07a3` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/lease-exception-sweep` | `jry/store-lease-exception-sweep` | `f46ad41ab36e` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/legacy-port` | `jry/legacy-lineage-port` | `1c2ba2ca5ba0` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/main` | `jry/wecom-card-closed-loop` | `46ea5e1c98c1` | 0/0/1 | `NO_TRACKING` | FROZEN_EVIDENCE |
| `/Users/jerry/zimu-work/partial-confirm` | `jry/partial-batch-confirm` | `baaec78d13a6` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/product-ops-mcp` | `jry/product-ops-mcp` | `8e2ca85c6b29` | 0/6/1 | `NO_TRACKING` | FROZEN_EVIDENCE |
| `/Users/jerry/zimu-work/product-ops-mcp-b` | `DETACHED` | `1014ece4fac7` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/zimu-work/review-card` | `jry/review-card-ux` | `137e1cbf2e75` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/tk182` | `jry/draft-bundle-line` | `0e1c2a417c33` | 0/0/0 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/tk185` | `jry/bundle-mcp-read` | `0e1c2a417c33` | 0/3/4 | `NO_TRACKING` | FROZEN_EVIDENCE |
| `/Users/jerry/zimu-work/tk198` | `jry/mcp-surface-split` | `2b817fdbe7fc` | 0/21/0 | `NO_TRACKING` | FROZEN_EVIDENCE |
| `/Users/jerry/zimu-work/verify-88cc262` | `DETACHED` | `88cc26223756` | 0/0/0 | `NO_TRACKING` | RETAIN_DETACHED |
| `/Users/jerry/zimu-work/workspace-convergence-20260830` | `codex/workspace-convergence-20260830` | `FINAL_SELF` | 0/0/0 | `NO_TRACKING` | CONVERGENCE_TARGET |
| `/Users/jerry/zimu-work/wt-t07-catalog` | `wip/t07-catalog` | `daba519e7229` | 0/9/1 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/wt-t09-blocker` | `wip/t09-blocker` | `daba519e7229` | 0/10/2 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |
| `/Users/jerry/zimu-work/wt-t15-shipimg` | `wip/t15-shipimg` | `daba519e7229` | 0/3/2 | `NO_TRACKING` | RETAIN_UNCLASSIFIED |

## 审计结论

- 5 个冻结证据源进入强指纹 ledger；其路径、分支、HEAD 和 staged/unstaged/untracked
  内容变化会阻断最终来源门禁。
- 2 个活跃源只做 advisory 观察，允许继续开发但会在最终门禁中报告漂移；维护者迁移前必须
  再取新快照。
- 2 个 worktree 本轮为 `PROTECTED_UNKNOWN`，没有被误判为 clean，也未清理。
- 其余 worktree 全部保留；`RETAIN_UNCLASSIFIED` 表示尚需逐票业务判断，不表示遗漏扫描。
- 本轮没有根据 age、clean、detached、upstream 缺失或 patch 相似度自动删除任何目录/分支。
