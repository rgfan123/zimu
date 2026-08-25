# 03 — JdIscGateway 传输内核 + JdPiiProjection 脱敏归一（P1）

**What to build:** 把 `connector/jd` 七个 ISC client 逐字节复制的
execute/normalize/audit 内核收进一个 `JdIscGateway`；把 6+ 份 HTTP 边界 PII 脱敏
收进一个 `JdPiiProjection`；Mock 与 REAL 的响应形状在 adapter 层归一。

**Blocked by:** 无
**Status:** ready-for-agent

## 背景

- 7 个 real client（warehouse/basicinfo/order/returns/serial/stock/write）各持一份
  `SUCCESS_CODES = Set.of("0","200","1000","10000","SUCCESS")` 与 110 行传输内核
  （相互 diff 只差 5 行）；7 字段凭据 `@Value` 块出现在 10 个构造器里。
- PII 脱敏（redactPersonalData/sanitize/personalField ≈55 行）复制在 6 个 controller，
  每份 Javadoc 都写着「与 SecretRedactor.isPersonalDataKey 对齐」；另有
  `recon/OutboundReconService.java:954`、`connector/jufubao/JufubaoOrderTransform.java:290`
  两处变体。规范实现在 `common/audit/SecretRedactor.java:55`。
- Mock 返回 `data.response.deliveryNo`、REAL 返回 `data.deliveryNo`，由
  `fulfillment/ShipmentJdOutboundExecutor.extractDeliveryNo` 双形状兼容补偿——
  同一接缝两个 adapter 契约不一致（ADR 0012 纪律 3 的反例）。

## 范围

1. `JdIscGateway`：持有凭据装配 + JdlClient 调用 + normalize + SUCCESS_CODES +
   审计挂钩；7 个 client 变薄为「接口名 + 参数装配」；
2. `JdPiiProjection`（或直接下沉 `SecretRedactor`）：6 个 controller + 2 处变体
   改为消费方，删除本地副本；
3. Mock adapter 输出与 REAL 同形（`data.deliveryNo`/`data.erpDeliveryNo`），
   `extractDeliveryNo`/`extractErpDeliveryNo` 删掉嵌套形状分支；
4. 测试：gateway 一套接口级测试替代 7 份 client 碎片测试（replace, don't layer）；
   Mock 形状归一后跑出库全链路回归（PreviewApi/Submit/TrackingBackfill 三类）。

## 验收

`git grep 'SUCCESS_CODES' backend/src/main | wc -l` == 1；
`git grep 'redactPersonalData' backend/src/main` 只剩归一实现；
`extractDeliveryNo` 无 `response` 嵌套分支；净删行 ≥1000。
