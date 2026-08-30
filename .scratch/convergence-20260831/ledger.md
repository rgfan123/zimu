# 分支 / worktree 收敛台账 — 2026-08-31 凌晨

- 集成分支：`jry/convergence-20260831`（worktree `/Users/jerry/zimu-work/convergence-final`）
- 基线：`jry/integration-20260828` @ `6994cf2f`（收敛开始时已推送 origin 且此后未再移动）
- 与 `codex/workspace-convergence-20260830`（Codex 隔离收敛会话产物）的关系：
  该分支封口 `cbf35d32` + 尾巴 `3a8f8e3b` 已经由 `ecb62f4b`、`7c91eabf` 两个 merge
  提交全部收入本分支。其自身的来源判定台账见 `.scratch/workspace-convergence-20260830/`。

## 一、已合入能力

| 能力 | 来源 | 进入方式 |
|---|---|---|
| MCP 双工具面拆分（Agent 面 / 外部协议面，协议面强制只读） | tk198 未提交产出 → codex 收敛票 05 | merge `ecb62f4b` |
| MCP 静态礼包只读工具（默认仅 Agent 面） | tk185 → codex 收敛票 06 | merge `ecb62f4b` |
| 治理版 SKU 检索过滤（未知参数 fail-closed） | jry/product-ops-mcp → codex 收敛票 07 | merge `ecb62f4b` |
| 来源礼包解析统一（文件/API/人工同一解析器，V88/V89 只追加） | worktree-agent-aa4c1e → codex 收敛票 08 | merge `ecb62f4b` |
| 收敛工具链（来源台账门禁、pre-work 防重复、verify 脚本） | codex 收敛票 01/09 | merge `ecb62f4b` |
| wecom 媒体下载 JDK 26 成因链修复 + 回归测试 | integration 工作区孤儿未提交产出（原会话已收工） | 按文件收养 `82fcb48e` |
| 前端组件测试 30s 超时预算（假红消除） | 同上 | 收养 `537fd4d7` |
| 四个并行会话的 spec/票据/调研文档落库 | integration 工作区未跟踪文件 | 收养 `427f0860` |
| AGENTS.md 两条假红判据（JDK26 / vitest 超时） | handoff 承诺未兑现项 | `3348f822` |
| 牛羊礼包成本核算调研（13 票地图 + 真实账单反推运费公式） | 镜像线 origin/claude/beef-lamb-gift-costing-a05bdc | 按文件拷贝去 vendored 前缀 `c4198df8` |
| compose 本地密钥 env_file 改可选（部署阻塞修复） | 本轮部署验收发现 | `01e1cc49` |

合并冲突 3 处（.env.example / McpToolRegistry / McpModulesEnvExampleTest）均按
「保留双面新设计 + 前向移植 integration 侧增量」解决；配套前向移植：
核对视图改读协议面 `protocolTools()` 并嫁接 `knownModules()`、
`SeedAgentToolModuleGateTest` 对账对象重指向 `AGENT_TOOL_MODULES`。
细节见 merge 提交 `ecb62f4b` 的提交信息。

## 二、评估后未合入（原因均已核实，非机械跳过）

| 来源 | 判定 | 依据 |
|---|---|---|
| `jry/wecom-card-closed-loop` @ 70ffc9d6（票 09 阻断卡 UI） | **被替代** | 基线上 `0cbd7ada`、`8947f4f8` 是更新的就地处置实现 |
| 同分支 ba2e6d8a（票 13 编码治理 2/3） | **弃用** | 直接修改已应用迁移 V80（生产 schema v86，Flyway 校验和必炸）；能力留待 V90+ 追加式重做 |
| 同分支 46ea5e1c（issue 181 stdio 写门禁脚手架） | **延后** | 提交信息自述「另一会话在制品」，无调用方 |
| 镜像线 19 个 claude/* 分支 | **不合** | 与开发线无共同祖先，合并会带入 vendored 目录结构；其中 14 个只有镜像自身 5 提交、无真实工作 |
| `dsh/h06-gateway-auth`（fail-closed 认证） | **重复劳动** | 开发线已有等价实现（`ProductionRequestAuthenticationPolicy` 一族，冒烟实测 403 fail-closed 生效） |
| snapshot/live-wip-20260825、codex/root-*-wip-2026082x | **归档** | 工作区快照，非特性分支 |
| claude/wayfinder-* / prototype-design（08-10~08-19，共 8 独有提交） | **归档** | 早期设计/原型存档，后续实现已落地 |
| 旧 Feixiang V33–V35 / Jufubao 局部实现 | **被替代** | 已由 jry/feixiang-json-pull、jry/caishixian-json-pull 等后续架构覆盖（均已并入基线） |

## 三、延后的活跃开发（不收编动态状态）

1. **`codex/sku-masterdata-repair`** — 收敛期间持续移动（45088b4b → bb805b02
   「SKU 条码质量阻断」）。⚠️ 它的 V67–V72 迁移号与本线 V67–V72 **硬冲突**，
   且基于 159 个提交前的旧基线。迁移时必须：取新快照 → 按 V90+ 重编号 →
   跑 `scripts/check-baseline.sh --pre-work` 门禁 → 逐票前移，禁止整分支 merge。
2. Codex 收敛会话（thread 01a05264）已确认收工：最终 HEAD 3a8f8e3b、工作树 clean、
   门禁输出 CONVERGENCE_VERIFIED（Python 32/32、Backend 2244/0/0、迁移 81→V89、
   前端全绿、双轴评审无 P0/P1/P2）。本分支已含其全部 9 个提交，无遗留尾巴。

## 四、遗留风险与技术债（记录，不在本轮范围）

- `zimu-admin` 弱密码 + 明文 HTTP 公网入口（handoff 反复提及，仍未换）
- 全仓 72 个 controller 无 Spring Security（既有姿态；网关层有 Basic Auth fail-closed）
- 生产事项：飞象 9 单结构指纹待取、自动回传 SOURCE_SYNC_CHECK_BLOCKED、定时拉取 last_pull_at 全 NULL
- 「一单一卡」（.scratch/manual-shipping/issues/02）仍未开工——用户最惦记的欠账
- 京东编码检索不 JOIN provider_skus（69 码已解决，京东编码未做）
- 前端组件测试 collect ≈ 15s 的真慢问题（超时只是止血）
- 12 个空分支被其他 worktree 占用删不掉；49+ worktree 待用户决策清理
- Docker Hub 本机不可达：前端镜像走「本地 build + 旧镜像 COPY」变通（node:22-alpine 无本地缓存）

## 五、验证证据（全部实测，命令级证据在会话记录）

| 项 | 结果 | 证据 |
|---|---|---|
| 合并前基线 后端全量（JDK24） | PASS | Tests run: 2217, F0 E0 S10, BUILD SUCCESS |
| 合并后 后端全量（JDK24） | PASS | Tests run: 2260, F0 E0 S10, BUILD SUCCESS |
| 合并后 前端 typecheck / unit / component / build | PASS | tsc 干净；761/761；18/18；vite build ✓ |
| MCP 聚焦群（合并后先行） | PASS | 127/127（含重指向门禁、双面过滤、bundle、HTTP 传输） |
| 迁移链（全新 PostgreSQL 16 真库） | PASS | Flyway「Successfully applied 81 migrations … now at version v89」 |
| 后端健康 | PASS | GET /actuator/health → {"status":"UP"} |
| MCP initialize / tools/list | PASS | 协议面恰好 11 个只读工具，无写形工具 |
| MCP read tool 真实调用 | PASS | tools/call search_skus → 真库 SKU 数据（含商品/履约方联查） |
| MCP write 工具面隔离 | PASS | tools/call submit_jd_outbound → -32602 Unknown tool |
| MCP HTTP 鉴权 | PASS | 无 token 401 / 错 token 401 |
| 双工具面运行期分离 | PASS | 启动日志：Agent 面 10 模块 46 工具 / 协议面 3 模块 11 工具 |
| Agent 工具绑定门禁 | PASS | SeedAgentToolModuleGateTest 2/2（7 种子 Agent 白名单 ⊆ Agent 面） |
| MCP 空模块 fail-safe | PASS | 实进程 exit 1 + 精确诊断（列已知模块全集） |
| 管理员核对视图（嫁接链路） | PASS | Basic+X-Operator → /api/v1/mcp-exposure 返回协议面模块/工具 JSON |
| docker compose config | PASS | 填必填项后 VALID；渲染值与设计一致（修复 env_file 可选性后） |
| 受控 write 调用 | 以测试替代 | McpProtocolAcceptanceTest 写场景族（幂等重放/版本冲突/无身份拒绝）在全量中通过；协议面无写工具，真实写面仅 Agent 进程内 |

前端 unit 曾在双后端套件并发压机时出现 2 个失败，重跑 761/761——按 AGENTS.md
判据归类为负载抖动，非回归。
