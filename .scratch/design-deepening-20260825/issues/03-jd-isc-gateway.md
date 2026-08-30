# 03 — JdIscGateway 传输内核 + JdPiiProjection 脱敏归一（P1）

**What to build:** 把 `connector/jd` 七个 ISC 客户端复制的传输内核收进 `JdIscGateway`；
把 6 份 HTTP 边界 PII 剔除收进 `JdPiiProjection`；Mock 与 REAL 的响应形状在 adapter 层归一。

**Blocked by:** 无
**Status:** 阶段一、二已实现（分支 `claude/ticket03-jd-isc-gateway`）；阶段三待 Docker 门禁

## 已实现

### 阶段一 · JdIscGateway（传输内核）

7 个真实客户端的凭据装配、JdlClient 调用、SUCCESS_CODES、normalize、审计收编为一处，
客户端只保留自己独有的请求装配。**实施时发现扫描所称「110 行只差 5 行」中差的正是要害，
两处真实差异必须保留而非拍平**：

1. **JdWarehouseClient 独有的审计白名单摘要**：出库单请求/响应含收件人、pin 账号与
   自由文本备注，原实现只把定长业务引用与计数写进审计。若按「逐字节相同」拍平，
   会把 PII 写进审计日志——由既有白名单断言当场抓出。已抽成 `JdWarehouseAuditProjection`，
   经 `JdAuditProjection` 接口可插拔，其余客户端沿用 `FULL`（原样记录，行为不变）。
2. **JdBasicInfoClient 独有的 `requestID`（大写 D）信封兜底**：供应商查询的
   `JdlApiListResponseBase` 用 `setRequestID`。收编后统一采用——其余信封没有该键、
   兜底不触发，是安全超集。

写模式门闩留在 `JdWriteOpsClient`（写专属政策，不属于传输内核），拒绝路径经
`gateway.refuse` 走同一审计口径；HTTP 层门闩不变。

三份默认值注入策略（反射探测 / 无条件 / 手写白名单）由
`JdIscDefaultsPolicyEquivalenceTest` 对真实 SDK DTO 实测等价后归一；同测试固化了
「退货列表与退货详情各有一个**同名不同包**的 `RtwOpenQueryRequest`」这个陷阱
（收编时若共用一个 import，编译器会报错；换成结构相同的 DTO 就会静默走错请求体）。

### 阶段二 · JdPiiProjection（HTTP 边界剔除）

6 个 controller 里 md5 实测**完全一致**的 `redactPersonalData`/`sanitize`/`personalField`
收编为一处。明确与 `common.audit.SecretRedactor` 的关系并写进 javadoc：
**不是同一条规则**——SecretRedactor 掩码（保留键置 `***`，用于审计），本单元剔除
（键不出现，用于 HTTP 响应），只有「哪些键算个人信息」同源，不可互相替代。

## 门禁

- JD 连接器测试 104/104 绿（含新增 `JdIscGatewayTest` 5 例 + `JdPiiProjectionTest` 5 例）
- 全部非 Docker 测试 809 个：808 通过，1 个失败
  （`WecomMediaDownloaderTest.redirectsAreNeverFollowed…`）——已在基线 `343233f`
  实测复现同一失败，属既有问题，与本票无关
- 主源码净减：`connector/jd` −1029 行

## 阶段三 · Mock/REAL 形状归一（未做，需 Docker 门禁）

**现状**：`MockJdWarehouseClient.success()` 把结果套成
`data = {operation, request, response:{…实际字段…}}` 三层壳，REAL 直接返回
`data = {…实际字段…}`；这就是 `ShipmentJdOutboundExecutor.extractDeliveryNo` /
`extractErpDeliveryNo` 需要双形状兼容分支的原因。该 Mock 还用 `warehouse_order_no`
（snake_case），也正因如此 `JdMockShapeContractTest` 的 camelCase 断言**没有覆盖
warehouse**——契约测试当初绕开了唯一不合规的那个。

**为什么没有顺手做**：改 Mock 形状会改变 `client-mode=MOCK` 下的运行时行为，
而演示栈（8088）正跑在 MOCK 模式；消费方至少有出库执行器、运单回填与对账。
验证必须跑 `ShipmentJdOutboundSubmitTest` / `ShipmentJdOutboundPreviewApiTest` /
`ShipmentJdTrackingBackfillApiTest` / `OutboundReconApiTest` 等 testcontainers 集成测试，
本轮按用户要求未启动本地 Docker。**不跑集成门禁就改 Mock 形状是拿演示栈冒险。**

**做法（下次带 Docker 一起做）**：
1. `MockJdWarehouseClient` 去掉 operation/request/response 三层壳，键改 camelCase；
2. 删除 `extractDeliveryNo`/`extractErpDeliveryNo` 的嵌套分支；
3. 把 warehouse 纳入 `JdMockShapeContractTest` 的覆盖清单（它当前被排除）；
4. 跑上述 4 个集成测试类 + 出库全链路回归。

## 验收

- ✅ `SUCCESS_CODES` 全库主源码只剩 1 处（`JdIscGateway`）
- ✅ `redactPersonalData` 只剩 `JdPiiProjection` 一处定义
- ✅ 净删行 ≥1000（实际 −1029）
- ⬜ `extractDeliveryNo` 无嵌套分支（阶段三）
