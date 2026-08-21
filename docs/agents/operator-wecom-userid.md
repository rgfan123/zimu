# 运营人员与企微 userid 映射（Issue #89）

> 本地 Resolution：按仓库约定（docs/agents/issue-tracker.md），不改动 GitHub Issue 正文，
> 交付说明记录在本文件。本文件是「运营人员 ↔ 企微 userid ↔ 责任团队」的事实源：
> 存哪、怎么改、何时生效、未绑定怎么办、解析 seam 消费哪个、真实企微门禁还缺什么证据。

## 0. 一句话

系统第一次有「人」的概念：可登记内部运营人员（姓名、企微 userid、所属责任团队），
`responsible_team` 由此可解析到具体人员列表与可推送 userid；未绑定 userid 的人员在需要
推送时得到明确结构化提示，绝不静默跳过。**本票不做登录与权限体系、不实现实际个人推送**。

## 1. 存储：V48 `app.internal_operators`（新表）

| 列 | 语义 |
|---|---|
| `id` | BIGINT IDENTITY 主键（稳定 ID，审计与前端引用用） |
| `display_name` | 姓名，VARCHAR(64) 非空（写入侧 trim） |
| `responsible_team` | 所属责任团队，VARCHAR(32) 非空；写入侧统一 trim + 大写归一（ORDER_OPS / CUSTOMER_OPS / SKU_OPS 等既有取值），DB CHECK 兜底拒绝纯空白与未大写值 |
| `wecom_userid` | 企微 userid，VARCHAR(64) 可空 = 未绑定；非空时**全局唯一**（partial unique index `uq_internal_operators_wecom_userid`：同一企微 userid 永远只映射一个人，停用后重新启用同一行，或先清除旧绑定再换绑） |
| `active` | 启用状态；**不做物理删除**，停用后解析 seam 不再返回该人员 |
| `lock_version` / `created_at` / `updated_at` | 乐观锁 + 审计时间线，沿用全库既有列风格 |

userid 字符集按企微官方规则保守校验（`^[A-Za-z0-9][A-Za-z0-9_@.\-]{0,63}$`：1..64 字符、
首字符数字或字母、可含 `_ - @ .`），DB CHECK 与写入侧同规则。真实企微实测确认前以本规则为准
（见 §5）。

边界：不建用户登录/角色/权限表；`channel_identities` 仍是客户/群的外部身份证据，**不是**内部
员工表（CONTEXT.md「渠道身份」条款继续成立）。

## 2. 管理界面与写入规则

- 入口：系统管理 → 运营人员（`/system/operators`，可见叶子；系统管理直接可见子项 5 ≤ 6，
  准入依据见 docs/agents/navigation-admission.md）。
- 列表：GET `/api/v1/operators?page&size&responsible_team&query`——团队精确筛选（服务端归一化）
  + 姓名/userid 模糊检索；行内展示责任团队 Tag、企微绑定状态（已绑定 userid / 未绑定）与
  「首次使用前先与机器人打招呼」运营提示（Alert + 行 Tooltip）。
- 写命令对齐既有 masterdata 公开 seam：POST/PATCH 需要 `Idempotency-Key`、`X-Operator`，
  PATCH 携带 `expected_version` 乐观锁（过期 → 409 VERSION_CONFLICT），全部写操作进审计
  （`operator.create` / `operator.update`，operator/请求体/响应体可追溯）。
- 创建：`display_name` / `responsible_team` 必填；`wecom_userid` 可空；`active` 缺省 true。
  非法值 → 422 字段级错误：`OPERATOR_DISPLAY_NAME_INVALID`（display_name）、
  `OPERATOR_TEAM_INVALID`（responsible_team）、`OPERATOR_WECOM_USERID_INVALID`（wecom_userid）；
  userid 重复 → 409 `WECOM_USERID_EXISTS`。
- 更新（PATCH）：null = 不改动；`wecom_userid` **空串 = 显式清除绑定**（清绑有明确语义，
  不依赖「缺省即清除」的隐式行为）；改名/换团队/换绑/停用均走同一投影。
- 停用语义：`active=false`（不物理删除）；停用人员立即不再出现在团队解析结果中。

## 3. 解析 seam：`OperatorResolver`

```java
// backend/.../operator/OperatorResolver.java
@Autowired OperatorResolver resolver;
OperatorTeamResolution resolution = resolver.resolve("ORDER_OPS");
// members=active 人员（含 display_name + 可空 wecom_userid）、pushable_user_ids、
// unbound_member_names（未绑定人员姓名，显式列出）、status、pushable
```

- **每次调用实时读库（无缓存）**：登记/修改/停用后下一次解析立即生效。
- 结构化结果，**绝不静默过滤**：任何未绑定 userid 的成员都出现在 `unbound_member_names`；
  `pushable=false`（PUSHABLE 以外状态均不可全员推送）；空团队为结构化 `NO_MEMBERS`（200），
  不是异常。
- 需要「全员可推送」的 fail-closed 消费侧用 `requirePushable(team)`：不满足时抛 422
  `OPERATOR_TEAM_NOT_PUSHABLE`，消息含团队、未绑定人员名单与运营应对（首次使用前先与
  企微机器人打招呼）。
- 团队名 trim + 大写归一（`order_ops` / ` Order_Ops ` 与 `ORDER_OPS` 同一团队）；空白团队
  → 422 `OPERATOR_TEAM_REQUIRED`。
- 只读 HTTP 投影：GET `/api/v1/operator-team-resolutions?responsible_team=ORDER_OPS`，
  供管理界面/未来推送消费侧核对「该团队能推给谁」；追加 `&require_pushable=true` 即走
  fail-closed 语义——不可全员推送时直接 422 `OPERATOR_TEAM_NOT_PUSHABLE`（消息含未绑定
  名单与运营应对），普通诊断仍 200 返回结构化状态。
- **本 seam 只保证 userid 已登记，不 mock 验收真实可达性**（见 §5）。

## 4. 测试门禁

- 后端 `OperatorCrudApiTest`（HTTP 写入 seam）：归一化、重复 userid 409、非法 userid/团队
  422 且不落库、列表检索/筛选、PATCH 换绑/清绑/停用/乐观锁/空补丁/404、审计与幂等重放、
  团队解析端点结构化诊断。
- 后端 `OperatorResolverTest`（解析 seam）：多人全绑定 PUSHABLE、部分未绑定 PARTIALLY_BOUND
  （名单显式）、全部未绑定 ALL_UNBOUND、无人员 NO_MEMBERS（非异常）、停用排除、团队归一化、
  空白 422、requirePushable 抛错/停用后恢复。
- 迁移门禁：`ProductionMigrationHistoryCompatTest` 阶段一模拟生产当前 V47、阶段二只追加 V48
  （V40–V47 checksum 按生产 `flyway_schema_history` 真实行冻结不变），并用真实结构断言新表与索引；
  `SchemaSnapshotMigrationEquivalenceTest` 保证 docs/schema.sql 与 Flyway 全链（V1..V48）等价。
- 契约门禁：`OpenApiContractConsistencyTest` 覆盖 `/api/v1/operators*` 与
  `/api/v1/operator-team-resolutions`。
- 前端：`operatorsPage.test.ts`（列表/绑定状态/运营提示、新建 POST 归一化载荷、编辑 PATCH
  清绑空串、搜索重查）；`businessObjectNavigation.test.ts` 断言系统管理可见叶子含运营人员。
- 全量门禁：`cd backend && mvn test`（JDK 24，`unset JD_LOP_CLIENT_MODE`）；
  `cd frontend && npm run typecheck && npm test && npm run build`；`git diff --check`。

## 5. 真实企微门禁：待实测结论与所需证据（本票不 mock 验收）

「必须先与机器人有过会话才拿得到 userid / 才能推送」是**待真实实测的外部门禁**。本票交付的
是登记 + 解析 + 明确提示（未绑定/不可推送时给运营可操作指引），**没有**用 mock 宣称该门禁
已验收。调度者后续企微实测需要补证的证据：

1. 真实企微环境：给从未与机器人会话的成员 userid 发起推送，观察是否失败/拿不到 userid；
   给已打招呼成员推送验证可达。结论（失败表现、是否需要在群内先 @机器人）写进本文件。
2. userid 字符集与长度上限按官方规则实现，实测确认官方 API 对特殊字符（`@`/`.`/`-`）的
   实际接受度；若官方拒绝，收紧 `OperatorRules.WECOM_USERID_PATTERN` 并同步 OpenAPI 契约。
3. 未绑定人员的「明确提示」路径在真实推送场景的运营口径确认（当前 seam 返回名单 + 指引）。

在此之前，任何个人推送功能不得引用本票结论宣称「已实测可达」；只可引用
`OperatorResolver` 的登记/绑定/解析事实。

## 6. 边界（范围红线）

- 不实现 #87 卡片、不实现个人推送；本票无任何对外发送副作用。
- 不改变 #84 发送到供应商群的路由（群 chatid 仍走 `WecomGroupChatResolver`）。
- 不做登录/角色/权限；不把 `channel_identities` 当内部员工表。
- 不新增依赖/框架；复用既有幂等、审计、乐观锁、错误模型与共享前端组件。
