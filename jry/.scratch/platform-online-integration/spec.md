# 三平台订单在线接入（合并版 spec）

状态：**合并稿 2026-08-18** —— 由两条并行线合并而成，本文是唯一权威。
- 线 A（红队线）：`docs/research/platform-api-integration-design.md` + `docs/research/platform-integration-redteam.md`，产出 12 张执行票
- 线 B（wayfinder 线）：`.claude/worktrees/api-integration-design-c1d115/wayfinder/platform-api-online/`，产出 8 条用户裁决 + 消失检测方案
- 合并后：**一套票（16 张，其中 4 张范围外）**，本目录 `issues/`

契约依据：`docs/research/platform-apis-overview.md` 及三平台契约文档
系统侧：`docs/api-contract.md` §4.2/§5.1/§6.2、`docs/excel-closed-loop-spec.md`、`CONTEXT.md`

## 目标（Destination）

三平台（彩食鲜 / 聚福宝 / 飞象）的订单接入从「人工去平台导表 + 人工上传回填」升级为**在线跑通**，可运行、可验收：

- **拉单 3/3 在线**：系统自身完成登录续期与拉取，订单进入现有 ImportBatch 闭环
- **回填 3/3 在线**：彩食鲜、飞象走「上传 Excel」型接口，聚福宝走 `multi-send` JSON 回传，均由系统投递、**人工触发**

不是再写一份设计文档——设计稿已覆盖八成，剩下的是决策、抓包和实际跑起来。

## 架构铁律（不可绕过）

- Connector 禁止直接写业务表，必须走应用层用例
- CanonicalOrder 是长期事实源；ImportBatch 语义不可绕过（`orders` 表 CHECK + trigger 强制非 WECOM 订单挂批次）
- `raw_import_rows` 血缘是 confirm / 履约导出 / 来源回填的**硬依赖**（`ProviderFileService`、`TrackingFileService`、`ShipmentJdOutboundService` 均读它）——任何入口不写 raw 行，确认能过但一张履约导出都生成不了
- 兜底只承诺**人工导表**（上传入口永在）

## 用户裁决（线 B，grilling 结论，不再重开）

1. **终点是真跑通在线接入**，不是产出设计文档
2. **验收矩阵 6 格全亮**：拉单 3/3 + 回填 3/3
3. **Phase 0 不作交付** —— 不做脚本上传对接、cron、manifest 规范。Python 脚本原样保留供人工兜底，但不工程化、**不作为承诺的降级链**（与红队 §2「不维护双实现」的推翻结论一致）
4. **导入批次人工确认闸门保留**；自动确认做成按渠道可开关配置项，本轮不打开
5. **回填推送人工触发** —— 系统备好内容 + 记审计，推送由人点（对外不可逆写）
6. **拉取范围 = pullOrders + 跨平台统一消失检测** —— 不按各平台状态枚举实现 `pullOrderChanges`/`pullCancellations`
7. **告警只做系统内可见**（D3 已裁）—— `last_error` + AuditLog + ConnectorsPage + 工作台异常卡，不建外发通道
8. **回填验收搭真实业务单** —— 三平台无沙箱，不造测试单

## 红队裁决（线 A，代码证据，已复核）

1. **「重复订单 = 整批 409 回滚」是代码事实**（`OrderCreateService.doCreate` 抛 `DUPLICATE_ORDER`，`SourceImportService` 的 `createImported` 调用外无 try/catch，全仓无 `ORDER_ALREADY_EXISTS`）→ 行级跳过必须在票 02 实现
2. **试点选型推翻**：聚福宝「协议层链路最短」但**系统侧最重**——它是唯一无法复用 byte 文件管线的平台。彩食鲜/飞象拿到文件字节即可复用 raw 行 + confirm + 导出全链路 → **彩食鲜/飞象先行，聚福宝等 02**
3. **漏票补齐**：结构化导入用例（02）是关键路径，原分票里没有
4. **并发内容哈希重放**：`upload.existing()` 先 SELECT 后 INSERT，并发相同上传撞唯一索引未捕获 → 500
5. 横切治理（凭据轮换 / golden 样本 / 失效演练）原本完全缺失

## 三平台能力全景（契约状态）

|  | 拉单 | 回填 | 缺口 |
|---|---|---|---|
| 彩食鲜 | ✅ 导出任务链 + JSON(orderList+orderDetail 覆盖 19/22 列) | ✅ `importDeliverExcl` multipart 22 列 | 成功响应形态未实测 |
| 聚福宝 | ✅ `orders/query` JSON 含明细 | ✅ `multi-send` | **收货人字段**（票 15，硬 blocker）、`company_id` 字典、状态枚举 |
| 飞象 | ✅ `deliveryExport` 直下 xlsx | ⏳ **唯一真缺口** | 回填端点（票 16）、区间口径、真实数据行列名 |

## 票与依赖图

```
独立起步：  01 接口演进    02 结构化导入用例(关键路径,最先)    12 横切治理
HITL 抓包： 15 聚福宝补抓(最急)                              16 飞象回填抓包

07 彩食鲜 Connector ← 01                    ┐
08 飞象   Connector ← 01                    ├→ 13 调度节奏 ← 任一
09 聚福宝 Connector ← 01, 02, 15            ┘  10 健康监控 ← 任一

14 消失检测 ← 02
11 回填 ← 16(飞象) / 15+09(聚福宝) / 07(彩食鲜)
```

**范围外（存档不执行）**：03 / 04 / 05 / 06（Phase 0 四张）

## 范围外

- Phase 0 全部交付物（脚本上传对接、cron、manifest、ingest 目录）；连带 D1 上传方式、D2 网关服务主体作废
- 告警外发通道（企微发消息、邮件、短信）
- 京东侧一切（已有 JD SDK 线）；企微消息接入（已有 wecom 线）
- 密钥管理服务（一期环境变量）
- 按平台状态枚举实现 `pullOrderChanges` / `pullCancellations`（由 14 替代）
- 多实例调度与分布式锁（一期单实例）
- 平台侧订单修改与取消写操作（只读平台状态，回填发货结果除外）
- 生产部署与运维

## 合规红线

供应商后台官方功能的接口化，非开放 API，无 SLA。**每平台每日 ≤2 次拉取**、不绕过限流、不抓取权限外数据。凭据只走环境变量；HAR 与 `*-credentials.txt` 勿外传，需定期改密（票 12）。
