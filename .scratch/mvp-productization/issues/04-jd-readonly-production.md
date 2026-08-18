# 04 — 京东生产 SDK 只读权限收口

Type: development-test
Status: resolved
Blocked by: None — can start immediately

**What to build:** 用明确的生产/UAT 配置和事业部语义，安全验证京东仓库、单个销售出库单和轨迹查询权限。

- [x] 环境、Token、PIN、事业部编码和青龙业主号概念不混用。
- [x] 探针仅读、不持久化/打印密钥，且能输出可行动的业务失败信息。
- [x] 权限证明与发货事实证明分开；无真实出库单号时不声称已查到发货信息。

## Validation

- 2026-08-12 使用官方 SDK 和不落盘凭据执行只读调用。UAT 网关返回 `19 / 无效 access_token`；同一 Token 在生产网关通过 Token 层并进入业务校验，证明凭据属于生产环境。
- 新增只读 `queryOwnerInfo` 发现接缝；仅传 PIN 时京东返回 `2001 / 没有事业部操作权限`。账号鉴权已到达业务系统，但当前 PIN 无事业部授权。
- 本次未创建/取消出库单，未查询任何未知真实单号，未打印或写入凭据。
- `ConnectorApiTest#jdWarehouseReadOnlyQueriesAreAvailableAtTheHttpSeam` 与 `JdWarehouseControllerTest` 通过；事业部响应会移除负责人、电话、邮箱和地址。
- 2026-08-12 20:36 再次通过官方 SDK 只读实测：UAT 地址与生产 Token 组合在 SDK 传输层失败；切换生产网关后 `queryOwnerInfo` 明确返回 `2001 / 没有事业部操作权限`，确认 Token 属于生产环境且已到达京东业务服务。
- 实测发现全局 `SNAKE_CASE` ObjectMapper 会丢失京东 SDK Java DTO 的 `ownerNo`/`warehouseNo` 等驼峰字段；已为 SDK 请求建立独立 `LOWER_CAMEL_CASE` 映射并增加 `JdWarehouseClientRequestMappingTest`。修复前仓库查询返回 `2000 / 入参事业部编码不能为空`，修复后使用已配置 EBU 编码返回 `2001 / 没有事业部操作权限`，证明请求字段已正确送达但授权仍未开通。
- 相关回归 `ConnectorApiTest,JdWarehouseControllerTest,JdWarehouseClientRequestMappingTest` 全部通过；所有外部调用仍仅限事业部与仓库查询，没有创建、取消或猜测查询任何出库单。
- 双轴复审后将仓库查询改为 `JD_PROBE_WAREHOUSES=true` 显式第二阶段，默认探针仍只执行事业部发现；SDK 响应也使用隔离的驼峰映射器，并由回归测试断言京东 `requestId` 不会因外部契约的 snake_case 策略丢失。

## External gate

- 2026-08-14 京东物流开放平台已为当前 PIN 开通事业部操作权限，外部阻塞解除。
- 验证路径：先重跑 `queryOwnerInfo` 确认不再返回 `2001 / 没有事业部操作权限`，再以返回的 `ownerNo` 查仓库；最后还需一个已知 ERP 出库单号才能证明真实发货信息查询。
- 执行验证时保持既有约束：探针仅读、凭据不落盘、不创建/取消出库单、不猜测真实单号。

## Answer

2026-08-14 京东开放平台开通事业部权限后，全量重跑只读探针：事业部发现 `queryOwners`、仓库查询 `queryWarehouses`（配置 `JD_LOP_OWNER_NO` 后第二阶段 `JD_PROBE_WAREHOUSES=true`）与商品查询 `queryProducts` 均返回 `business_code=1000`，仓库域权限闭环——此前 `2001 / 没有事业部操作权限` 已消失。全表 37 个接口中 15 个已开通、22 个因探针固定传空参而返回缺参数类错误（`2000/2002/2003`，请求已进入京东业务参数校验层，非权限拒绝）。出库单/轨迹查询的真实数据验证仍需要一个已知 ERP 出库单号（外部数据依赖，不属于权限问题）。详见 `jd-sdk-bridge/gate.md` 验证记录。
