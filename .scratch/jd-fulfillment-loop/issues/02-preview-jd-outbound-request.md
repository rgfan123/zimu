# 02 — 预览并校验京东出库请求

**Type:** implementation

**What to build:** 运营人员在真实建单前查看一个 Shipment 将发送给京东的完整请求预览；任何无法安全确定的 SKU、数量、地址或履约方配置都会明确阻断，并指向需要修正的业务数据。

**Blocked by:** 01 — 统一 Shipment 级京东出库单边界

**Status:** resolved

**Claimed by:** codex-root

- [x] 请求预览以一个 Shipment 及其全部 ShipmentItems 为范围，并展示每个业务字段的来源和校验结果。
- [x] `erpDeliveryNo` 使用该 Shipment 的稳定商户侧出库引用，重复预览不会生成新编号。
- [x] `planQuantity` 只能由显式单位换算得到精确正整数；非“件”单位缺少换算、非法换算或产生非整数时阻断，不进行四舍五入或向上取整。
- [x] Receiver 的省、市、区县、乡镇（京东要求时）和详细地址必须来自已确认的结构化数据；自由文本只用于人工修正，不自动猜测拆分。
- [x] 仓库、店铺、客户和货主等 JD 标识来自当前 FulfillmentProvider 配置；缺失时显示可诊断的阻断结果。
- [x] 预览不会触发任何 JD 写操作；生成与查看行为有审计，日志和响应不泄漏密钥或不必要的 PII。

## Comments

- 2026-08-13：预览范围与验收项已经实现并通过聚焦测试；票据仍保持 `claimed`。独立 Standards review 发现提交路径仍在持有 Shipment/Order 数据库事务锁时调用 JD 写接口。该跨系统事务/幂等窗口必须由 05 的 intent → adapter → result 回写边界收口；在 05 完成前，本票不能标记 `resolved`。

## Answer

已完成预览纵切片：

- 新增公开只读应用 seam `ShipmentJdOutboundPreviewSnapshot`。HTTP preview 与 submit 复用同一份 Shipment 级请求、校验、阻断和稳定 `request_hash`；准备快照本身不写审计、不调用 JD 写接口。
- 请求一次性锁定并读取当前 ShipmentItems、相关 ProviderSku 与 BundleComponent 快照；仅显式、有效映射可用。件数换算要求精确正整数，缺失、停用、零值、非法值和非整数结果均 fail closed。
- Receiver 改为带 `expected_version` 的 Shipment `lock_version` CAS 确认；结构化省/市/区/详细地址必填，`townRequired` 必须由履约方配置显式给出，缺失策略也阻断。自由文本仅作为人工修正来源，不自动解析。
- 预览阻断创建或复用 `JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED` ReviewCase，阻断解除后关闭；SKU 门禁 reason 仍由 03 独占，不重复创建。
- 内部 typed snapshot 保留提交所需真实配置，HTTP display request 对 `pin` 脱敏，validation 只显示来源和 presence；地址确认、预览及失败审计不落原始地址或 PIN。SDK 请求的 `orderType` / `goodsLevel` 按官方 String shape 构造。
- JD adapter 抛出的运行时异常统一转为可诊断失败，并通过独立事务补写脱敏的 durable connector failure audit。
- OpenAPI、schema 与 V11 migration 已同步；废弃的自由文本地址解析器及其测试已删除。

验证证据：

- `mvn -DskipTests test-compile`：`BUILD SUCCESS`，276 个 main + 44 个 test source 编译通过。
- 2026-08-13 23:03:59–23:04:26 的五份新鲜 Surefire 报告合计 28/28 通过：`ShipmentJdOutboundSubmitTest` 11/11、`ShipmentJdOutboundPreviewApiTest` 8/8、`ShipmentJdOutboundWriteModeDisabledTest` 1/1、`JdStockUnitConverterTest` 5/5、`JdWriteOpsClientRequestMappingTest` 3/3。
- 上述五组聚合 Maven 进程在报告全部写出后被系统中断，因此没有保留下最终聚合 `BUILD SUCCESS` / exit code；不能把 28/28 报告表述为一次有完整 exit 证据的聚合命令。
- 后续获准单跑 `ShipmentJdOutboundSubmitTest` 时，测试尚未启动便被并行中的 03 测试源码缺少 `ShipmentPair` / `twoShipmentsOfOneOrder` 阻断；该编译缺口已由 03 owner 补齐，但按协调要求未再次占用 Maven。此失败不是 02 生产代码或测试断言失败。
- 2026-08-14 当前工作树全量 `mvn test` 生成 50 份新鲜 Surefire 报告，合计 286 tests、0 failure、0 error、7 个明确 skip。本票相关当前报告为 Preview 8/8、Converter 5/5、SDK mapping 3/3、Submit 24/24、WriteMode 1/1，已覆盖上述最终窄改。
- `docs/openapi.yaml` 已重新解析成功，`git diff --check` 对本票文件无错误。

原硬依赖 05 已于 2026-08-14 完成并 `resolved`：JD 写调用现已使用持久 intent、事务外 adapter、原子结果回写与未决结果先对账，且最终 Standards / Spec 双轴无 P0–P3。因此本票已无未关闭依赖，现设为 `resolved`。未执行真实 JD 写请求；这是预览安全性边界，不是生产权限验收。
