# 三平台在线接入现有业务评估（彩食鲜 / 聚福宝 / 飞象）

状态：评估稿（2026-08-18）
依据：`docs/research/platform-apis-overview.md`（三平台契约总览）+ 三份平台契约文档 + 脚本实测
系统侧：`docs/api-contract.md` §6.2、`docs/excel-closed-loop-spec.md`、`backend/.../connector/ExcelPlatformConnector.java`

## 1. 现状与差距

**系统现状**（`ExcelPlatformConnector`，三平台 P0 统一走文件传输）：

```java
capabilities() = new ConnectorCapabilities(true, true, false, false, false);
// fileImport=true, fileExport=true, onlinePull=false, onlinePush=false, callback=false
```

- EXCEL 模式启用：文件指纹识别、`transform`、来源回填文件生成
- 三种 `pull*` 与在线 `pushShipmentResult` 返回 `CONNECTOR_CAPABILITY_UNAVAILABLE`
- 类注释明言：**「在线 API 未获文档/凭据前明确不可用」**

**本次工作的产出恰好补上了这两个缺口**：

| 缺口 | 现状（本次工作后） |
|---|---|
| API 文档 | ✅ 三平台登录/认证/订单获取/发货回传契约全部抓包确认（三份契约 + 总览） |
| 凭据 | ✅ 三平台账号密码 + 登录接口均可用（脚本实测：彩食鲜/聚福宝登录与拉单通过） |
| 登录机制 | ✅ 三个平台均可程序化登录续期，无验证码 |

→ **`ExcelPlatformConnector` 注释中的「不可用」前提已不成立**，onlinePull 能力具备落地条件。

## 2. 三平台数据获取模式对比

| 平台 | 获取方式 | 商品明细 | 实测 | 对系统的意义 |
|---|---|---|---|---|
| 彩食鲜 | 任务导出 Excel（推荐）或 `orderList` JSON | Excel ✅ / JSON ❌（仅主订单） | ✅ 两种都验证 | JSON 可做主订单状态/统计，明细靠 Excel |
| 聚福宝 | `orders/query` JSON 直连 | ✅（product_list） | ✅ | **唯一可直接 JSON→CanonicalOrder 的平台**（含明细） |
| 飞象 | `deliveryExport` 直下 Excel | ✅（21 列 v1） | 未实跑 | 与现有 v1 解析完全兼容 |

## 3. 推荐接入方案（分三阶段）

### Phase 0 — 人工触发的自动拉表（今天可用，零系统改动）

- Python 脚本（已就绪）定时/手动拉取 → 落 `data-local/`（或系统 ingest 目录）→ **走现有文件导入接口**（`api-contract.md` §4.2 文件导入）
- 彩食鲜用 export 模式（Excel 全明细）、聚福宝 JSON 转存、飞象直下
- 优点：零 Java 改动、当天可上；缺点：仍是"文件传输"模式，聚福宝 JSON 需要一次转换（写个 JSON→CSV/Excel 适配器，或直接人工核对）

**Phase 0 具体落地**：
1. 三脚本加入统一的 `--out-dir <ingest>` 参数（已有）
2. 用系统现有文件导入端点/目录对接（确认 ingest 入口是目录监听还是 API 上传）
3. cron/LaunchAgent 每日定时（如 08:30）执行三个脚本

### Phase 1 — Java Connector 在线 Pull（正式接入，推荐主路径）

按 `api-contract.md` §6.2 已定义的接口实现，**不动领域层**：

```java
public class CaishixianConnector extends ExcelPlatformConnector {
    // onlinePull 置 true；实现 pullOrders/pullOrderChanges/pullCancellations
}
```

- **聚福宝先行**（数据最全、链路最简单）：`orders/query` 分页拉取（tab=no_delivery → pullOrders；delivered/all 差异比对 → pullOrderChanges；订单状态枚举含取消态 → pullCancellations）→ `transform` 映射 CanonicalOrderDraft（main_order_id→source order reference、sub_order_id→line reference、product_list→OrderLine）
- **彩食鲜**：Excel 任务导出链路在 Java 内重放（发起→轮询→下载→复用现有文件解析）；或 `orderList` JSON 做主订单级 pullOrders（明细缺口 → 与导出模式并行，或补抓 orderDetail 接口后升级）
- **飞象**：deliveryExport 直下 → 复用现有 v1 文件解析（无需 JSON 映射）
- 三个平台共同：登录续期（token 管理）、限流/重试、平台错误码转换、幂等（游标 + 订单号去重）

### Phase 2 — 在线回传（pushShipmentResult）

- 聚福宝 `multi-send` 契约已确认 → 可实现发货回传
- 彩食鲜/飞象回传接口未抓包，需补抓后实现（飞象页面有 deliveryImport 线索）
- 京东回传不在此范围（已有 JD SDK 线）

## 4. 各平台接入细节

### 4.1 聚福宝（推荐 Phase 1 首站）

- 认证：登录接口 Set-Cookie 3 个 JWT；请求需 `JFB-CSRF-TOKEN` 头 + `X-Jfb-Project-Id: supplier`（踩过的坑，必须带）
- 拉单：`orders/query`，page_token 游标；`tab` 映射 pullOrders（no_delivery）/pullChanges（delivered+all 差分）
- transform 映射表：

| orders/query 字段 | CanonicalOrder 概念 | 对应 Excel 闭环列 |
|---|---|---|
| `main_order_id` | source order reference | 主单号 |
| `sub_order_id` | source line reference | 拆单号 |
| `product_list[].product_id/name` | source product reference/snapshot | 商品编号/名称 |
| `product_list[].product_num` | requested quantity | 下单数量 |
| `order_status=NO_DELIVERY` | 待发货状态 | 物流状态 |
| `supplier_name` | 来源上下文 | 供货商 |
- 缺口：**收货人字段不在 list 主对象**（在 sub-order-info / multi-send-form），需补抓确认字段路径，否则收货人/地址缺失 → 大量 NEED_REVIEW

### 4.2 彩食鲜

- 认证：`login-token` + `supplier-code`（必须显式 `20075684` 主供应商，登录后默认可能是「基地」供应商）
- 建议双模式：Excel 导出（完整明细）为主，`orderList` JSON 用于状态计数/增量比对（number 字段天然是仪表盘数据）
- 任务系统轮询契约完整（taskStatus/progress/taskAttach），Java 重放成本低

### 4.3 飞象

- 最简单：登录 cookie → deliveryExport 直下 → 复用 v1 指纹解析
- 注意：文件误命名 `.csv` 实为 XLSX（魔数校验已有）；无 JSON 接口，在线 pull 意义有限——**建议维持文件模式，仅自动化拉取**

## 5. 关键决策点（需业务/架构确认）

| # | 决策点 | 选项 | 建议 |
|---|---|---|---|
| 1 | 聚福宝收货人缺口 | a) 补抓 sub-order-info 确认字段 b) 接受 JSON 缺收货人 + 人工复核 | a) 优先（一次抓包即可） |
| 2 | 彩食鲜明细缺口 | a) 保持 Excel 导出模式 b) 补抓 orderDetail 接口 | a) 短期；b) 中期（拉单体验最好） |
| 3 | Phase 1 实现范围 | a) 只做 pullOrders b) 全量 pull 三件套 | a) 先跑通主链路，变更/取消后补 |
| 4 | 调度方式 | a) Python 脚本 + cron（Phase 0） b) Java Scheduled（Phase 1） | Phase 0→a，Phase 1→b（Spring @Scheduled 或独立 worker） |
| 5 | 凭据管理 | a) 环境变量/.env b) 密钥服务 | a) 先（现有 .env 模式）；b) 后 |
| 6 | 幂等 | 订单号 + 文件指纹双保险 | 沿用现有 ImportRevision 语义 |

## 6. 风险与合规

| 风险 | 说明 | 缓解 |
|---|---|---|
| 接口稳定性 | 抓包确认的私有接口无 SLA，平台改版可能失效 | 契约文档 + 脚本/Connector 隔离；失效时快速回退人工导表 |
| 合规 | 供应商后台官方功能的接口化，非官方开放 API | 遵守实际响应、超时与受控并发，不虚构固定频率限制；不绕过平台限制、不抓取权限外数据 |
| 凭据泄露 | 三平台明文密码 + 有效会话 | 仅存本地（gitignore）、环境变量注入、定期改密 |
| 数据一致性 | JSON 与 Excel 明细可能不一致（彩食鲜） | Excel 为事实源（不可变原则），JSON 只做状态/统计 |
| 收货人缺失 | 聚福宝 JSON 缺收货人 → NEED_REVIEW 增多 | 补抓 sub-order-info 后消除 |

## 7. 建议路径与工作量（粗估）

| 步骤 | 内容 | 工作量 |
|---|---|---|
| 0a | 确认系统 ingest 入口（目录监听 vs API），Phase 0 脚本对接 + cron | 0.5d |
| 0b | 聚福宝补抓 sub-order-info（收货人字段） | 用户 10min + 分析 0.5d |
| 1a | 彩食鲜/飞象 Contract 冻结（补 getExpress 字典、失败样例） | 0.5d |
| 1b | 聚福宝 Java Connector：登录 + pullOrders + transform（+单测） | 2-3d |
| 1c | 彩食鲜 Java Connector：任务导出链路重放 + 文件解析复用 | 2d |
| 1d | 飞象 Java Connector：cookie 登录 + 下载 + v1 解析复用 | 1d |
| 2 | 回传：聚福宝 multi-send 接入 pushShipmentResult；彩食鲜/飞象补抓 | 2d |

**总评**：在线接入条件已成熟（文档+凭据+实测），推荐「Phase 0 本周启用自动拉表 → Phase 1 聚福宝 JSON 直连为试点 → 彩食鲜/飞象跟进 → Phase 2 回传」。聚福宝是 ROI 最高的试点（JSON 最全、链路最短）。
