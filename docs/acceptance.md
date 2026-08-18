# Demo 验收记录

- 验收日期：2026-08-12（Asia/Shanghai）
- 独立 Compose 项目：`zimu-fulfillment-acceptance-v3c`
- 公共入口：`http://localhost:18091`
- 固定种子参考日：`2026-08-12`

## 自动验收

```bash
ACCEPTANCE_PROJECT=zimu-fulfillment-acceptance-v3c \
ACCEPTANCE_PORT=18091 \
ACCEPTANCE_REFERENCE_DATE=2026-08-12 \
ACCEPTANCE_SKIP_BUILD=true \
sh scripts/acceptance.sh
```

结果：`PASS: public HTTP seams, 30-day seed, Demo isolation, Metabase dashboards, restart idempotency`。验收栈保留供人工复核，未删除容器或卷。`ACCEPTANCE_SKIP_BUILD` 只用于对已构建的独立栈重复验收；默认脚本仍执行完整构建。

## 七条验收标准

1. **一键启动与导航**：PostgreSQL、Redis、backend、frontend、Nginx 和 Metabase 已启动；公共 `/actuator/health` 为 `UP`，PRD 导航路由 Nginx 返回 React 入口页，`/metabase/` 的 HTML 与其全部相对 JS/CSS/图片资源均返回 200。Nginx 通过 Docker DNS 动态解析上游，backend 重启后公共入口仍可用。
2. **Mock 演示隔离**：`/demo/v1/scenarios` 创建的订单为 `DEMO`，运行结果为 `SUCCEEDED`，订单最终状态为 `SYNCED`，Timeline 精确包含从 `ORDER_RECEIVED` 到 `SOURCE_SYNCED` 的 9 个事件；同一来源编号在 `BUSINESS` 查询中不可见。
3. **30 天数据与分析**：新业务库生成 30 天 × 4 渠道的 120 条滚动订单，加 3 条新鲜走查订单；工作台、渠道、商品与履约分析均返回非空数据。
4. **状态覆盖**：`OUT_OF_STOCK`、`PROCUREMENT_PENDING`、`FULFILLMENT_EXCEPTION` 和 `SYNC_FAILED` 筛选均有订单；开放复核事项、采购工单和回传失败指标均非空。
5. **后端模块**：订单、客户、商品/SKU、履约/发货、采购、Connector、Analytics 和 Audit 的存储/服务/API 可编译，上述公共 HTTP seam 对其中的订单、分析、复核和审计链路做了真实查询。
6. **京东封装编译**：`backend/libs/` 的两个 ISC jar 被 `backend/pom.xml` 引用，`JdWarehouseClient` 随 Maven 构建编译通过。本项只证明编译与边界存在，不代表真实京东登录或业务调用已验收。
7. **审计与幂等**：公共 Audit API 中 `seed.demo-dataset` 只有 1 条；backend 重启前后 `BUSINESS` 订单总数均为 123，没有重复播种。

## Metabase 与迁移链

全新业务库从空 schema 按 Flyway `V1 -> V2 -> V3` 成功迁移。Metabase 已连接业务库并创建「履约总览」「渠道分析」「商品分析」三个仪表板；验收脚本不只检查名称，还校验每个仪表板绑定预期问题且问题查询返回数据行。

## 未解除的外部 gate

- 京东生产账号/凭据与真实 ISC 调用；
- 彩食鲜、聚福宝、飞象的真实 API 文档和凭据；
- 真实业务样表的脱敏、格式指纹与字段映射交叉验证。

`Mock` 、确定性种子和《京东商品编号.xlsx》资料都不能替代这些真实外部验收。
