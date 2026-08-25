---
status: accepted
---

# 每条领域规则只允许有一个家：全库按「深模块」原地收敛，不重写

用户 2026-08-25 授权系统整体设计决策（「你来决策即可 我需要系统整体的决策」）。
本 ADR 基于对 JD 出库集群的逐行评审 + 全后端设计扫描（证据见
`.scratch/design-deepening-20260825/map.md`），给出方向、优先序、不做清单与纪律。

## 事实

全库的系统性问题不是文件太大，而是**无主知识**——同一条领域规则以字符串字面量、
静态常量或复制粘贴的形式散落在多个包里，谁都能改、没人拥有：

- **Shipment 生命周期没有模块**：`app.shipments` 被 27 个文件、10 个包直接写 SQL
  （57 处），没有枚举、没有实体；Order 有 entity/enum/repository，Shipment 什么都没有。
  **票 02 实施时核实并修正**：「已发货 = SHIPPED|DELIVERED」的口径其实全库**一致**，
  真正的问题是这条规则被复制在 Java 2 处 + 内嵌 SQL 8 处 + V2/V3/V6 视图且无人拥有；
  TrackingBackfill 的 `CREATED|SHIPPED` 是**另一个问题**（可否回填运单）不是 bug，
  TrackingFileService 的 `SHIPPED/PARTIAL/FAILED` 是运单导入文件「结果」列的**第三套
  词汇表**（`PARTIAL` 不是合法 shipment_status）。扫描报告的「4 种互不一致」是误报，
  盲目统一会把导入文件语义混进发货生命周期。定性由「活跃冲突」改为「无主复制」，
  收拢必要性不变：状态增改要人肉找齐十几处。
- **connector/jd 七胞胎**：7 个 ISC client 的 execute/normalize/audit 逐字节复制
  （110 行里只差 5 行）、`SUCCESS_CODES` 复制 7 份、HTTP 边界 PII 脱敏复制 6 份
  （另有 recon/jufubao 两处变体），每份 Javadoc 都自述「与 SecretRedactor 对齐」。
- **可重试/对账政策 4 处平行实现**：`!"RECONCILIATION_REQUIRED".equals(code)` 在
  ShipmentJdOutboundService 内联两次、wecom `JdOutboundFailureCard.retryable` 一次
  （注释自称「与服务端同源」——手工同步）、`PriorSubmission.requiresReconciliation`
  又 reach 回 Preparer 的静态常量。
- **8 个异步 worker 手写同一个 lease/claim/suppress/recover 循环**，事故性分叉：
  仅 3 个会重置 claimSuppressUntil、仅 3 个有 drain+@PreDestroy、lease 下限有
  60/30/2400/无四种。AsyncTaskStore 18 个公开方法，没有调用方用到超过 4 个。
- **京东商品名比对内核复制两份、「无参照名」两种吞法**：核对
  （JdSkuMappingCheckService）静默放行，门禁（ShipmentJdSkuMappingGateService）并入
  NAME_MISMATCH 警示。评审核实：名称比对在门禁侧从来只出警示、不阻断提交
  （warnings 通道，阻断只数 issues）——所以这不是「一放一拦」，而是两条 advisory
  口径互相矛盾；normalize/token 逐字节相同的两份随时可能各自漂移。
- **刚合并的 JdShipmentSubmissionPlan 接缝选对了，但计划对象漏表示**：裸
  `Map<String,Object> request` 迫使 Service 做 unchecked cast 挖 `cargoInfos`/
  `ownerNo`/`warehouseNo`、Preview 靠键名脱敏 `pin`；Mock 与 REAL 适配器响应形状
  不同（`data.deliveryNo` vs `data.response.deliveryNo`），由调用方 `extractDeliveryNo`
  双形状兼容来补偿——同一接缝的两个 adapter 契约不一致。

## Decisions

1. **方向：原地深化，不重写**。不引入新框架、不加新分层、不做 DDD 大迁移。
   治法只有一种：给每条无主知识找一个家（一个模块），家的接口尽量小，
   调用方与测试都只走接口。现有深模块（IdempotencyService、JdCargoPlanner、
   刚合并的 Preparer 接缝）证明这条路在本库走得通。
2. **优先序**（杠杆 × 风险裁定，票见 `.scratch/design-deepening-20260825/`）：
   - **P0 票 01**：京东商品名比对内核归一（真缺陷，当天修）。
   - **P1 票 02**：`ShipmentStatus` 枚举 + 生命周期判定模块。先收 4 处分歧规则，
     27 个裸 SQL 点位挂棘轮逐步迁，不搞一票大爆炸。
   - **P1 票 03**：`JdIscGateway` 收拢 7 份传输内核 + `JdPiiProjection` 收拢脱敏；
     Mock/REAL 响应形状在 adapter 层归一（消灭 extractDeliveryNo 双形状）。
   - **P2 票 04**：提交/对账政策模块 `JdSubmissionState`（状态常量、
     UNCERTAIN_EXTERNAL_RESULTS、requiresReconciliation、retryable 的唯一家），
     Plan 增加类型化投影（cargos/ownerNo/warehouseNo/脱敏视图），wecom 卡片改为消费方。
   - **P2 票 05**：`LeasedTaskLoop` 收拢 8 个 worker 循环骨架，分叉显式化。
   - **P3 票 06**：ExportWecom 状态机收拢、MasterDataService 按 7 聚合拆分、
     PlatformOrderRefreshService 回到 PlatformConnector 接缝、MCP 参数解析收拢、
     /internal 镜像控制器、POI 读写接缝。只开票挂账，改到哪补到哪。
3. **不做**：Shipment 不上 JPA entity/repository 大改（先枚举+判定模块）；
   IdempotencyService 不动（它已经是深模块，冷门重载 1-2 个调用方是可接受的代价）；
   平台回传 UI 是产品件不是设计件，另走票 07 记录 8/24 设计结论。
4. **纪律（棘轮，评审时执法）**：
   - 新增状态/政策字符串必须住进 owning module，禁止在调用方新写字面量；
   - 「与 X 同源 / 与 X 对齐」类注释视为缺陷报告——同源的正确拼法是 import；
   - 同一接缝的两个 adapter 必须同形：Mock 响应形状 == REAL 响应形状，
     不许调用方写兼容分支；
   - 深化一个模块时，测试打在新接口上并**删除**被替代的旧碎片测试（replace,
     don't layer）。
5. **执行方式**：每票独立分支 + 门禁后并主干；本机 Docker 在演示栈 + 多会话
   testcontainers 并发下会把容器压死（2026-08-25 实测），集成测试单类串行跑，
   失败先怀疑环境（读 surefire 报告，不信管道退出码）。
