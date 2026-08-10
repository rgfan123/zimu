# 订单状态机：五维转移矩阵 + 事件清单

> 来源：`wayfinder/tickets/order-state-machine.md` grilling 结论（2026-08-10，zed-main）。
> 固定输入：PRD §17/§18；领域词汇见 `CONTEXT.md`。

## 0. 设计原则

1. **五维状态分离**：OrderStatus / FulfillmentStatus / ShipmentStatus / SyncStatus / ProcurementStatus 各归各表各列，不合并进单一 `order.status`。
2. **创建即跑完全程**：新订单在创建请求内同步执行整条流水线到最终态；无定时器、无手动推进按钮（地图已定）。
3. **Timeline 只记语义事件**：中间态由状态列 + `order_version` 快照承载，不为每个转移硬造事件。
4. **外部回执原则**：采购结果必须从外部接口进入，业务系统内部零 mock 捷径（未来接真实采购系统只换发送方）。
5. **多行订单**：行级独立推进 + 订单级最差聚合。

## 1. 五维状态集

### 1.1 OrderStatus（订单级聚合态）

主线：`RECEIVED → VALIDATED → SKU_MAPPED → FULFILLING → SHIPPED → SYNCED → CLOSED`
异常分支：`NEED_REVIEW / OUT_OF_STOCK / PROCUREMENT_PENDING / FULFILLMENT_EXCEPTION / SYNC_FAILED / CANCELLED`

- 异常不占主线位：处理完回主线继续；
- **demo 自动最终态 = SYNCED**；CLOSED 不自动进入（无 CLOSED 事件），种子数据历史单使用；
- 多行聚合：取所有行最差/最晚进度（任一待采购 → PROCUREMENT_PENDING；任一异常 → FULFILLMENT_EXCEPTION；**全部** SHIPPED → SHIPPED；**全部**回传成功 → SYNCED）。

```mermaid
flowchart LR
    R[RECEIVED] --> V[VALIDATED] --> S[SKU_MAPPED] --> F[FULFILLING] --> SH[SHIPPED] --> SY[SYNCED] --> C[CLOSED]
    R -- 校验失败/需人工 --> NR[NEED_REVIEW]
    NR -- 修正重试 --> V
    NR -- 取消 --> CA[CANCELLED]
    F -- 缺货 --> OOS[OUT_OF_STOCK]
    OOS -- 建采购工单 --> PP[PROCUREMENT_PENDING]
    PP -- 回执 SUCCESS --> F
    PP -- 回执 FAILED --> FE[FULFILLMENT_EXCEPTION]
    F -- 京东拒收/出库失败 --> FE
    FE -- 重试 --> F
    FE -- 取消 --> CA
    SH -- 回传失败 --> SF[SYNC_FAILED]
    SF -- 重试回传 --> SY
    CA -- 终态
```

### 1.2 FulfillmentStatus（履约单元，type ∈ JD_WAREHOUSE / PROCUREMENT）

```
PENDING → STOCK_CHECKED → [JD] JD_SUBMITTED → JD_ACCEPTED → SHIPPED（终）
              │ 缺货
              ▼
        OUT_OF_STOCK → PROCUREMENT_PENDING →（回执 SUCCESS）ARRIVED → SHIPPED（终）
              │ 回执 FAILED                          （回执 FAILED）
              ▼
        EXCEPTION ←──────────────────────────────────┘
              │ H 重试 → STOCK_CHECKED ；H 取消 → CANCELLED
```

### 1.3 ShipmentStatus

`CREATED → SHIPPED → DELIVERED`（终态 DELIVERED；新单 mock 直达 SHIPPED，DELIVERED 种子历史单）。

### 1.4 SyncStatus

`PENDING → SYNCED`；失败分支 `SYNC_FAILED →（H 重试）→ SYNCED`。按 shipment 独立回传。

### 1.5 ProcurementStatus

`PENDING → SUCCESS / PARTIAL / FAILED`；订单取消时 PENDING 工单 → `CANCELLED`。

## 2. 主线路径（创建即跑完全程）

| 步骤 | OrderEvent（§18） | OrderStatus |
|---|---|---|
| 创建 + 四层校验通过（Schema / Business / SKU / Duplicate-Version） | `ORDER_RECEIVED` | RECEIVED |
| 业务校验完成 | —（中间态） | VALIDATED |
| SKU 映射完成 | `SKU_MAPPED` | SKU_MAPPED |
| Fulfillment 拆行 + 京东库存查询 | `JD_STOCK_CHECKED` | FULFILLING |
| 京东出库提交 → 受理 | `JD_OUTBOUND_SUBMITTED` → `JD_OUTBOUND_ACCEPTED` | FULFILLING |
| 发货完成 | `JD_SHIPPED` | SHIPPED |
| Shipment + Tracking 落库 | `SHIPMENT_CREATED` + `TRACKING_RECEIVED` | SHIPPED |
| 回传来源渠道成功 | `SOURCE_SYNCED` | **SYNCED（最终态）** |

## 3. 异常路径

### 3.1 缺货 / 采购（demo 唯一 live 可交互路径）

`FULFILLING` → 库存不足 → `OUT_OF_STOCK` → 创建采购工单（`PROCUREMENT_REQUESTED`）→ `PROCUREMENT_PENDING`（订单等待外部回执）。

- **外部回执接口**：`POST /internal/v1/procurement/tickets/{id}/receipt`，body 对齐 PRD §13：`result`（SUCCESS/PARTIAL/FAILED）/ `available_quantity` / `expected_ship_time` / `remark` / `idempotency_key`；
- **校验**：工单必须 PENDING；PARTIAL 需 `available_quantity < required_quantity`；幂等（`idempotency_key` 重复拒绝，返回原结果）；
- SUCCESS → `PROCUREMENT_COMPLETED` → 履约继续（ARRIVED → 发货 → 回传）；
- PARTIAL → 按 `available_quantity` 部分发货，剩余数量回 `OUT_OF_STOCK`（可再次发起采购工单）；
- FAILED → `FULFILLMENT_EXCEPTION`（可再发起采购或取消）。

### 3.2 业务校验失败

→ `NEED_REVIEW`；人工修正重试 → 重新跑 Business Validation → `VALIDATED`；或取消 → `CANCELLED`。

### 3.3 京东异常

出库提交被拒 / 出库失败 → `FULFILLMENT_EXCEPTION`；H 重试 → 重新走 `STOCK_CHECKED`；或取消。

### 3.4 回传失败

回传失败 → `SYNC_FAILED`；H 重试回传 → `SYNCED`。

### 3.5 取消

任意未终态订单可 H 取消 → `CANCELLED`；若已提交京东出库，取消前先调 `cancelOutboundOrder()`（mock 成功）；不回传；该单 PENDING 采购工单同步 → `CANCELLED`。

## 4. 事件清单（§18 + 使用点）

| 事件 | 使用路径 |
|---|---|
| `ORDER_RECEIVED` | 主线 1 |
| `ORDER_UPDATED` | 保留备用（demo 无编辑订单入口） |
| `SKU_MAPPED` | 主线 3 |
| `JD_STOCK_CHECKED` | 主线 4 |
| `JD_OUTBOUND_SUBMITTED` | 主线 5 |
| `JD_OUTBOUND_ACCEPTED` | 主线 5 |
| `JD_SHIPPED` | 主线 6 |
| `PROCUREMENT_REQUESTED` | 3.1 缺货 |
| `PROCUREMENT_COMPLETED` | 3.1 回执 SUCCESS |
| `SHIPMENT_CREATED` | 主线 7 |
| `TRACKING_RECEIVED` | 主线 7 |
| `SOURCE_SYNCED` | 主线 8 |

事件记录带可选关联 id：`order_line_id` / `fulfillment_id` / `shipment_id` / `procurement_ticket_id`（Timeline 展示按订单聚合、可下钻到行/履约）。

## 5. 持久化模型（双轨）

- `order_event`：语义事件流（Timeline 数据源）——事件类型 + payload(JSONB) + operator + created_at + 可选关联 id；
- `order_version`：每次订单状态/数据变更追加**完整快照**（五维状态 + 订单头 + 行摘要 + 变更原因 + 触发者），支撑 §7 Version Validation 与 §19 数据修改追责；
- 关键写操作（Order+Lines+Event、Shipment+Tracking+Event 等）单事务（PRD §25）。

## 6. 人工干预规则

- demo 无权限体系，统一演示账号 `demo-ops`（Audit Log 记录）；
- H 动作只作用于对应维度的可操作态：**回执**只对 PENDING 工单；**重试**只对 SYNC_FAILED / EXCEPTION / NEED_REVIEW；**取消**只对未终态订单；
- 每个人工动作产生 Audit Log + OrderEvent；
- demo 只实现**采购回执**一个 H 动作（前端「采购操作台」扮演外部发送方），其余 H 规则留口（未来人工操作台/真实系统接入），异常态由种子数据展示。
