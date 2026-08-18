---
label: wayfinder:task
title: P0 Excel 接入与履约回填闭环构建
status: resolved
priority: P0
claimed_by: /root/review_frontend_excel_spec
blocked_by: [后端骨架与订单域实现, 履约发货与采购模块构建, Connector 与京东 Client 构建]
parent: wayfinder:map
---

# P0 Excel 接入与履约回填闭环构建

## Question

把已关闭的 P0 Excel 契约实现为真实可验收的三平台文件闭环。

## 范围

- 彩食鲜、聚福宝、飞象文件容器/表头指纹识别与逐行容错导入；
- 原文件、Sheet、行号、原始单元格、批次和修订血缘长期保存；
- 来源行转换为 CanonicalOrder/OrderLine，并复用核心域客户/SKU/ReviewCase 用例；
- 按单一 FulfillmentProvider 分别生成京东官方模板或统一第三方模板；
- 单一履约方运单回传文件整批校验与原子接收；事务编排调用履约实现票提供的共享应用服务落 Shipment/Tracking、状态、事件、版本与审计，不复制状态机；
- 按三个来源平台各自原格式生成版本化阶段/最终回填文件；
- 多 Shipment 首批自动回填、后续人工跟进，以及下载/回传审计；
- 使用用户提供的真实 XLSX/CSV 样表完成最小闭环验收。

## 不包含

- Fulfillment / Shipment / Tracking / Procurement 通用领域模型、状态机和非文件 JSON API（归「履约发货与采购模块构建」）。

## 验收

- 三种来源格式可稳定识别并保存逐行血缘；
- 京东与不同第三方文件严格分开，格式保持契约要求；
- 运单返回后可按原 Sheet/行生成来源原格式回填文件；
- 飞象 CSV 输入/输出保持 CSV 约束，不被静默改成 XLSX；
- 多 Shipment、缺映射、回传冲突与重复上传遵循已关闭决策，不猜测、不覆盖历史。

## Blocked by

后端骨架与订单域实现、履约发货与采购模块构建、Connector 与京东 Client 构建。

## Resolution

已实现三来源文件指纹、原文件/行/单元格血缘、CanonicalOrder 转换与显式客户/SKU/乘数门禁；按履约方生成第三方24列 XLSX 或京东官方77业务列 XLSX，不复制 golden 中的客户数据；第三方 tracking 整批原子接收后生成来源回填文件。SKU 映射资料通过只读 preview HTTP seam 返回精确命中/冲突证据，默认启动与 Compose 均不自动落库；Sheet3 “易和天下”未被猜测为飞象。京东正整数门禁失败时不修改已接受的原始行血缘，而是在同一事务创建可操作的 `QUANTITY_SCALE` ReviewCase 并阻断导出。

客户/SKU 复核允许分步确认，但仅在同一订单所有阻断项关闭后才原子恢复原始行、创建缺失 Fulfillment 并按 provider 生成导出；已导出原始行与幂等重放不会重复出文件。多 Shipment 使用正式续发 HTTP 命令创建 sequence+1 Shipment 与独立第三方导出；首批部分发货期间保留真实进行中阶段，累计终局且 Tracking 齐全后才转人工复核。所有来源回填只写最小 sequence 且永久非 final，人工完成写 `MANUAL_SOURCE_FOLLOWUP_COMPLETED`。tracking 业务结果按当前批次统计，相同导出/相同内容重放返回首次完整响应和来源回填 ID。

## Validation

- 真实来源解析（外部路径显式传入）：彩食鲜 XLSX 6 数据行、聚福宝 XLSX 2 数据行、原测试飞象误命名 OOXML 1 行均可识别并留痕。另以 SHA-256 `56639b08...e244` 的真实飞象文本 CSV 验证 GB18030、LF、40列、1行；回填保持 GB18030/LF/40列，只更新实样存在的物流状态/单号，不新增物流公司列。
- 真实 SKU reference preview 定向测试：CSX `matched=4/need_review=2`，JFB `0/2`，FX `0/1`；上传该 mapping workbook 为订单文件返回 422 `TEMPLATE_FINGERPRINT_AMBIGUOUS`。默认主数据回归断言不存在任何 `EMG*` 自动落库映射；历史批量初始化器仅保留为显式 feature gate，未知名称条目即使显式启用也保持 inactive。
- 京东 golden：两份真实 `JD冷链导单*.xlsx` 均为 `导入数据`/`导入说明`，A 列空占位、B:BZ 77 业务列；内置 resource 由真实模板脱敏清空主表业务行而来。HTTP 闭环测试断言 2 sheets/77列顺序/隐藏列/注释/固定值/文本类型/正整数数量/仅1条生成行，并扫描 ZIP XML 不含 style sentinel 或旧 PII。
- 定向矩阵：`ExcelClosedLoopApiTest` 现有 14 个公共 HTTP/真实/golden/闭环用例。既有真实文件矩阵与新增复核恢复、续发门禁、多 Shipment 取消/两批闭环均曾定向通过；新增 exact tracking replay 断言后，独占重跑捕获并修复 Shipment `shipped_at` 的 PostgreSQL `Instant` 绑定 500（改为 UTC `OffsetDateTime`）。`mvn -q -DskipTests -Dmaven.compiler.useIncrementalCompilation=false test-compile` 通过；最终全类绿色矩阵由根代理在独立临时快照复跑，避免共享 `target` 的并发 Maven 污染。
- 前端续发入口验证：`npm test` 17/17、`npm run typecheck`、`npm run build` 全部通过；仅 BUSINESS + THIRD_PARTY + PARTIALLY_SHIPPED 展示，成功后显示 shipment/export/version 并刷新详情与列表。

## Remaining gates

- 京东 tracking 文件回传格式仍无官方 golden；`tracking-imports` 对 JD 明确返回 `JD_TRACKING_TEMPLATE_GATE`，可在真实 SDK 查询权限证明后走同一 tracking 应用服务，不得把 `发货清单.xlsx` 猜成回传模板。
- 真实 CSX/JFB/FX 订单仍缺客户 source ref 及未确认来源 SKU 映射，因此真实样表证明的是解析/留痕，不是全部自动出库；需通过已有主数据写 API 人工确认。`京东商品编号.xlsx` 仅作为映射参考资料，不是来源订单或官方导出模板。
