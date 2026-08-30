# 子牧工作区收敛交接（2026-08-30）

## 交付边界

- 唯一写入位置：`/Users/jerry/zimu-work/workspace-convergence-20260830`
- 本地分支：`codex/workspace-convergence-20260830`
- 冻结基线：`af0b58c0691b84120f99e03fe4fd7a73f3963d9b`
- 最终引用：以本文件所在分支的已提交 `HEAD` 为准；交接时运行
  `git -C /Users/jerry/zimu-work/workspace-convergence-20260830 rev-parse HEAD` 取精确值。
- 本轮没有 fetch、push、PR、部署、生产写入、分支删除、worktree 删除或来源工作区提交。

开始收敛时，同一个 Git common-dir 下有 53 个已注册 worktree；新建本隔离 worktree 后为
54 个。仓库同时存在互不相关的 `origin/main` 镜像历史和开发历史，`origin/HEAD` 不能作为
新工作的权威基线。多数工作分支没有可核验 upstream，因此本轮只把本地 Git 身份和本地
引用写入证据，不把它们描述成已推送。

54 个注册 worktree 的逐项 path / branch / HEAD / dirty / 本地 upstream 证据和保留处置见
`worktree-audit.md`；不可读项明确记为 `PROTECTED_UNKNOWN`，没有被 clean 推断覆盖。

## 已纳入或适配

| 票 | 来源 | 收敛提交 | 处置 |
|---|---|---|---|
| 05 | `jry/mcp-surface-split` @ `2b817fdb` 的 21 个未提交文件 | `69e444cf` | 人工移植并适配为独立 Agent / protocol 索引；公共面继续只读失败关闭。 |
| 06 | `jry/bundle-mcp-read` @ `0e1c2a41` 的 3 个 tracked + 4 个 untracked 文件 | `88e2f9e6` | 人工移植到最终双工具面，默认只进入 Agent 面。 |
| 07 | `jry/product-ops-mcp` @ `8e2ca85c` 的 6 个 tracked + 1 个 untracked 文件 | `804594d7` | 人工移植为同一 SKU 查询接缝上的 AND 过滤。 |
| 08 | `worktree-agent-aa4c1e4557940246d` @ `1014ece4` 的 8 个 tracked + 3 个 untracked 文件 | `39e75475` 及最终审查修复提交 | 人工移植并统一文件/API/人工礼包解析；迁移只追加、不回改基线历史。 |

每个来源的 HEAD、路径、staged/unstaged/untracked 路径与内容指纹保存在
`sources.json`。门禁只读扫描来源，任一 HEAD、分支、路径或内容变化都失败关闭。

## 已逐项评估但未合入

| commit / 来源 | 结论 | 原因 |
|---|---|---|
| `70ffc9d6` | 排除 | 行为已被冻结基线中的新实现覆盖。 |
| `ba2e6d8a` | 排除 | 旧商品编码治理与 V86/当前生产决策冲突，移植会回退追加式迁移。 |
| `46ea5e1c` | 延后 | stdio 写门禁为无调用方的 issue 181 半成品；公共协议面继续保持只读，后续应按独立票完成。 |
| 旧 Feixiang V33–V35 / Jufubao 局部实现 | 排除 | 已被后续架构替代或仅有局部实现，禁止批量回灌。 |
| 混合 UI 原型、孤儿/无 Git 数据目录 | 不自动处理 | 多主题或缺少可靠 Git 身份，保留供单独数据救援；本轮不删除。 |

## 维护者随后迁移的活跃增量

1. `codex/sku-masterdata-repair`：本轮明确不读写迁移；封口 advisory 快照为
   `1fb4f5350c5d3e83f7df565efdf760289df094cc`、dirty `0/0/0`。它仍可继续移动，实际迁移前
   必须再取新快照。
2. `jry/integration-20260828`：封口 advisory 快照为
   `6994cf2f6235f47d3ca96cb79a7891984d07b084`、dirty `0/3/30`。它在收敛期间持续移动，只
   迁入冻结基线之后且尚未被本分支吸收的增量。
3. 每次只迁一个票或一个独立 commit。迁入前先运行 pre-work 门禁；若为
   `RESUME_EXISTING`、`ALREADY_INTEGRATED`、`COLLISION` 或 `PROTECTED_UNKNOWN`，停止批量
   cherry-pick，按证据逐项处理。
4. 若活跃增量也使用了 V89 或更高迁移号，以本分支迁移历史为先重新编号；不要覆盖本分支的
   已提交迁移。

不要把整个活跃分支一次性合并进来，也不要仅凭目录名、clean 状态或 patch 能反向应用就删除
原工作区。等迁移完成并另外验收后，再独立决定清理。

## 可重复门禁

来源账本门禁：

```bash
bash scripts/check-baseline.sh \
  --ledger .scratch/workspace-convergence-20260830/sources.json \
  --target /Users/jerry/zimu-work/workspace-convergence-20260830 \
  --json
```

新工作防重复门禁（参数与稳定裁决详见 `scripts/check-baseline.sh --help`）：

```bash
bash scripts/check-baseline.sh --pre-work \
  --target /path/to/new-worktree \
  --baseline af0b58c0691b84120f99e03fe4fd7a73f3963d9b \
  --work-item ISSUE-ID \
  --intent normalized-intent \
  --registry /path/to/read-only-workspace-registry.json \
  --json
```

最终收敛验收：

```bash
bash scripts/verify-convergence.sh
```

该命令要求隔离 worktree 保持单写者，在长测试前冻结目标 branch/HEAD/tree，并用 200ms
只读 watchdog 观察 tracked/untracked 状态，结束时再次核对。watchdog 观察到的中途变化即使
随后恢复 clean 也会使证据作废；它不是阻止其他进程写入的操作系统锁，因此验收期间仍禁止
其他开发者/Agent 修改该 worktree。

## 验证矩阵

| 层 | 状态 | 证据 |
|---|---|---|
| 来源账本与 pre-work CLI 单测 | PASS | 32 个 Python CLI/竞态/安全文件回归通过；最终 verifier 再独立执行。 |
| Backend 聚焦业务/迁移测试 | PASS | 聚焦 Maven 门禁通过；全量初跑暴露的两类旧测试契约修正后 7/7 真实 PostgreSQL 回归通过。 |
| Backend 完整 `mvn clean test` | PASS | 最终 verifier 在干净已提交 HEAD 独立 exit 0；不复用并发写 `target/` 的旧报告。 |
| Frontend typecheck | PASS | 最终 verifier 的 `npm run typecheck` exit 0。 |
| Frontend unit | PASS | 最终 verifier 的 `npm run test:unit` exit 0。 |
| Frontend component | PASS | 最终 verifier 的 `npm run test:component` exit 0。 |
| Frontend production build | PASS | 最终 verifier 的 `npm run build` exit 0。 |
| Docker/Testcontainers | PASS | 后端门禁真实启动 PostgreSQL 16 容器并应用 81 个迁移至 V89；仅代表测试基础设施验收。 |
| GitHub remote / push / PR | NOT_EXECUTED | 本地交付；认证不可用且未授权发布。 |
| 部署 / 生产迁移 | NOT_EXECUTED | 明确超出本轮范围。 |
| 外部业务验收 | NOT_EXECUTED | 没有把健康检查或测试环境 200 当成验收。 |

预封口第一次完整后端运行真实执行 2244 个测试，得到 4 failures / 0 errors / 10 skipped：
一类是未知 `search_skus` 参数已按票 07 失败关闭、旧观测测试仍要求 SUCCESS；另一类是彩食鲜
夹具未建立来源 SKU 映射、旧用例仍期待 accepted。两者均只修正测试契约/夹具，生产
fail-closed 与 V89 分配冻结没有放宽；聚焦复现通过后，再由最终 verifier 从干净 HEAD 重跑。

Java 专项复审与 Standards / Spec 双轴 code review 均无 P0、P1、P2。

## 独立提交序列

- `b5075269` `docs(convergence): specify isolated workspace merge`
- `5b40cff0` `feat(convergence): add read-only source ledger gate`
- `d6450f72` `docs(convergence): reject superseded tail commits`
- `69e444cf` `feat(mcp): split agent and protocol tool surfaces`
- `88e2f9e6` `feat(mcp): add static bundle read tools`
- `804594d7` `feat(mcp): add governed sku search filters`
- `39e75475` `feat(import): unify source bundle resolution`
- 最终审查修复与 verifier/handoff 提交：见本分支 `HEAD`。
