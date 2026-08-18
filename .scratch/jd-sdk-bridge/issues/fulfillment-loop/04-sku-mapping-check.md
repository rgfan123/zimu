# 04 — SKU 映射核对与差异告警

**What to build:** 系统在京东云仓履约链路中核对 SKU：调 `queryGoodsInfo` 按京东商品编码（goodsNo/erpGoodsNo）核对系统 SKU 与京东商品信息的映射（provider_sku_code 映射键），发现映射缺失或商品信息不一致（下架/失效/名称不符）时写入运营告警，业务侧可见并可处理，避免建单时才发现商品不可用。

**Blocked by:** 00 — 履约记录京东同步字段扩展（无直接依赖，可并行；若需要履约记录的映射落点则受其约束）

**Status:** wontfix

- [ ] provider_sku_code 与京东 goodsNo/erpGoodsNo 的映射键确定（配置来源明确），核对逻辑可重复执行。
- [ ] 核对结果：映射缺失 / 商品失效 / 名称不一致分类输出，差异写入运营告警（当前告警服务只有 list/acknowledge，需补 create 或等效写入通道）。
- [ ] 告警不泄漏 PII，审计留痕；核对任务可手动触发且有明确触发入口。
- [ ] Mock 模式可演示（SKU 一致 → 无告警；SKU 缺失 → 告警产生）。

## Comments

- 2026-08-13：此票已被 `jd-fulfillment-loop/03 — 建立京东 SKU 映射门禁` 取代。新票把阻断性映射问题归入 ReviewCase，而不是非阻断运营提醒；保留文件仅用于追溯，不得继续领取或实现。
