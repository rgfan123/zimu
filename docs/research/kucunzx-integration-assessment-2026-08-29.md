# kucunzx / yuanliaokc 接入子牧履约中台 — 设计评估

调研日期：2026-08-29
调研者：Claude（只读调研，未改动任何仓库、未部署、未做任何 GitHub 写操作）
分析用克隆：`<scratchpad>/yuanliaokc`（只读，HEAD = `e11d661`）
上一版参考文档：`docs/research/yuanliaokc-deploy-mcp-plan-2026-08-27.md`

---

## 0. 先纠正提问前提：`kucunzx` 这个项目不存在

在写任何方案之前，必须先纠正三个事实前提，否则后面的结论会建立在错误的对象上。

### 0.1 没有 `kucunzx` 这个仓库

| 检查 | 命令 | 结果 |
|---|---|---|
| 指定仓库 | `gh repo view rgfan123/kucunzx` | `Could not resolve to a Repository` |
| rgfan123 全部仓库 | `gh repo list rgfan123 --limit 100` | 8 个：`yuanliaokc` / `zimu` / `kehuzx` / `car-agent` / `new_RAG` / `old_rag` / `minicode` / `turbo-disco` —— **无 kucunzx** |
| 全 GitHub 搜索 | `gh search repos kucunzx` | 0 结果 |
| 本机 | `ls /Users/jerry/Documents/kucunzx` | 不存在 |

**结论：`kucunzx` 与 `yuanliaokc` 不是「两个项目」，也不是「新旧版本」——前者根本不存在。** 本报告的对象是 `rgfan123/yuanliaokc`（公开仓库，Python，master 单分支，17 个 commit）。

命名上容易混淆的是同事的另一个仓库 `kehuzx`（客户中心），它确实存在且已被子牧接入（见 §4.1）。

### 0.2 inventory-v1 不是「没做」，是「做在了另一个仓库里」

任务背景里说「刚审计确认 InvenTree 在所有分支的所有历史里出现 0 次，一行没做」。**这个审计本身没错，但结论下反了。**

我复核了审计事实（`/Users/jerry/zimu-work/main`，remote = `rgfan123/zimu`）：

```
git log --all --oneline -S "inventree" -i | wc -l   →  0
grep -ril "inventree" (java/yml/sql/md)             →  0 处
```

子牧仓确实一行都没有。**但 inventory-v1 的 9 张票已经在 `yuanliaokc` 里实现了**，就在 2026-08-27 ~ 08-28 两天内：

| yuanliaokc commit | 日期 | 对应子牧 issue |
|---|---|---|
| `9d91c1ab0` | 08-27 13:56 | #162 InvenTree Adapter/Auth 与 SKU-Part 映射 |
| `3b4159c5b` | 08-27 14:25 | #163 表格 + 原图上传与 OCR 库存草稿 |
| `1e6707ec2` | 08-27 14:56 | #164 人工确认与幂等写入 StockItem |
| `14be1d6a6` | 08-28 02:19 | #165 盘点导入、差异确认与库存流水 |
| `6d7de17ca` | 08-28 02:12 | #166 临期/过期看板与提醒 |
| `d97349296` | 08-28 02:28 | #167 子牧订单 Allocation 与 FEFO 预占/释放 |
| `94ad611a2` | 08-28 02:32 | #168 京东/供应商外部库存快照边界 |
| `f0698de24` | 08-28 03:08 | #169 受控端到端验收 |
| `e11d661c` | 08-28 03:44 | #170 真实验收脚手架 |

规模：**相对已部署的 `2e05d24`，新增 13,510 行、46 个文件**（`git diff --stat 2e05d24e9..e11d661`）。

同时，这 11 张 issue（#160–#170）在 `rgfan123/zimu` 里**全部仍是 OPEN**，其中 #161（InvenTree 1.5.2 部署基线）**没有任何对应实现**，#170 被标 `needs-info`。没有人关闭它们，也没有人在票上留下「已在 yuanliaokc 实现」的记录。

> **这是本次调研最重要的发现：一个 13,510 行的实现处于「代码在 A 仓、票在 B 仓、两边都不知道对方状态」的悬空状态。** 最大的现实风险不是技术风险，是**有人照着 #160–#170 再实现一遍**。

### 0.3 「采用 Eventual 架构」——不成立

核实方式：

```
git grep -in "eventual|最终一致|异步一致|事件驱动" -- ':!.e2e'   →  0 命中
git grep -lni "outbox|saga|event_bus|event sourcing"            →  0 命中（仅 .e2e/node_modules 第三方文件）
```

全仓自有源码里 **"eventual" 出现 0 次**。唯一的命中在 `.e2e/node_modules/playwright-core/types/protocol.d.ts`，是 Chrome DevTools 协议文档里的英文副词 "eventually"，与架构无关（顺带：`.e2e/node_modules` 有 115 个文件被误提交进 git）。

**实际架构是同步 REST 调用**：`allocation.py` 直接同步调 `InventreeClient` 的 HTTP 方法，没有队列、没有事件、没有 outbox。

不过——**代码里确实有一个容易被误传成「eventual」的、真实且设计良好的东西**：一套显式的「结果未知 → 对账」状态机。写操作绝不重试（`inventree_client.py:9-13`），网络错误/5xx 归一成 `status=None`，订单行落 `reconciliation_required`（`models.py:813`），外部快照落 `ExternalSyncStatus.unknown`。这是**「拒绝盲目重试、交人工对账」**，不是最终一致性架构。措辞被放大了，但底下的工程判断是对的。

---

## 1. 它是什么

### 1.1 定位

`yuanliaokc` = **原料仓储业务系统**（`scd-wms`）。三层：

| 模块 | 路径 | 规模 |
|---|---|---|
| FastAPI 后端 | `backend/app/` | 17 routers，本次关注对象 |
| React 前端 | `frontend/` | 20 页面，React 19 + AntD 5 |
| LangGraph 识别 Agent | `src/scd_mk/` | 生产单图片 → Qwen 多模态识别 |

解决的问题：牛羊肉原料的**入库 / 批次 / 效期 / 盘点 / 损耗 / 生产单**。业务规模按其设计文档是「生产单 1–2 张/天，物料约 300 条」。

### 1.2 数据模型

SQLAlchemy，**41 张表**（`backend/app/models.py`，2000+ 行）。与接入相关的核心分组：

| 分组 | 表 | 说明 |
|---|---|---|
| 主数据 | `materials` / `material_tags` / `material_aliases` / `warehouses` / `locations` | 原料档案 |
| 内部实物库存 | `batches` / `inventory_transactions` | 批次 + 流水 |
| InvenTree 映射 | `inventree_part_mappings` (`models.py:231`) | material ↔ InvenTree Part |
| 导入/OCR | `inventory_import_batches` / `_rows` / `_images` / `ocr_drafts` | 表格+原图上传 |
| 确认落库 | `stock_commits` (`models.py:409`) | 幂等写入锚点 |
| 效期 | `expiry_reminders` (`models.py:464`) | 临期提醒 |
| **子牧订单镜像** | **`zimu_orders` / `zimu_order_lines` / `order_allocations`** (`models.py:828/861/906`) | **见 §3.3** |
| 外部快照 | `external_stock_snapshots` (`models.py:1078`) | 京东/供应商观测 |

**关键点：真正的「有多少货」不在这个库里。** 新架构下，实物库存的真源是 **InvenTree**——`order_allocations` 存的是 `stockitem_pk` / `allocation_pk`（InvenTree 主键），FEFO 查询打的是 `GET /api/stock/?part=&in_stock=true&expiry_after=`（`allocation.py:20`）。yuanliaokc 自己的 SQLite 退化成**映射表 + 幂等台账 + 草稿区**。

### 1.3 API 面

**HTTP REST**，7 个新 router，全部挂在 `backend/app/main.py:48-54`。完整端点与认证矩阵（我逐个解析签名得到）：

| 端点 | 方法 | 认证 |
|---|---|---|
| `/api/inventree/mappings` | GET | `get_current_user` |
| `/api/inventree/mappings` `[/{id}/disable]` `[/sync]` | POST | `require_writer` |
| `/api/inventory-imports` | POST / GET | `require_writer` / `get_current_user` |
| `/api/inventory-imports/{b}/rows/{r}/confirm` | POST | `require_reviewer` |
| `/api/inventory-stocktakes` (+5) | POST/GET | `require_writer` / `require_reviewer` |
| `/api/expiry/summary` `/items` `/reminders` | GET | `get_current_user` |
| `/api/expiry/reminders/generate` `/{id}/resolve` | POST | `require_reviewer` |
| `/api/external-stock` | GET | `get_current_user` |
| `/api/external-stock/sync` `/observations` | POST | `require_writer` |
| `/api/orders` | POST | `require_writer` |
| **`/api/orders/{order_id}`** | **GET** | **无 —— 见 §5.2** |
| `/api/orders/{o}/lines/{l}/allocate` `/revise` `/release` | POST | `require_writer` |
| `/api/orders/{o}/cancel` | POST | `require_writer` |

### 1.4 MCP 面：仓库里仍然没有

`git grep -in "mcp" -- ':!.e2e' ':!*.md'` 只有 **2 处注释**（`schemas.py:152`、`inventory_qa.py:1`），与 2026-08-27 那份文档的结论完全一致。**新增的 13,510 行没有引入任何 MCP。**

但**部署侧有**：容器内 `/srv/mcp_server.py` 是一个 stdio MCP server，暴露 3 个只读工具（`total_inventory` / `search_inventory_by_usage` / `search_inventory_by_name`）。这是上一轮部署时**在仓库之外新写的**，没有回流到 git。

> 这构成一个独立隐患：**生产上跑的 MCP server 不在任何仓库里**，改一行都无从 review、无从回滚、无从复现。

### 1.5 认证方式

JWT HS256，12 小时有效期，4 个角色（`auth.py:76-80`）：`operator` / `reviewer` / `admin`，`require_writer` = 三者任一。

`backend/app/config.py:19`：

```python
jwt_secret: str = "change-me-in-production"
```

**没有服务账号 / 机器凭据概念。** 子牧要调 yuanliaokc，只能持有一个人类用户的账密、每 12 小时换一次 JWT。这是 §5.1 的核心问题。

---

## 2. 最近更新了什么

### 2.1 部署的仍是旧版本 —— 已用哈希硬确认

上一版调研留了一个「容器内代码对应哪个 commit」的未验证项。**本次已闭环。**

容器内 3 个文件的 SHA256（前 12 位）与仓库各 commit 比对：

| 文件 | 容器内 | `2e05d24`（部署版） | `e11d661`（HEAD） |
|---|---|---|---|
| `app/main.py` | `eb4995817965` | **`eb4995817965`** ✅ | `fd7f9b4b2e77` ✗ |
| `app/models.py` | `c794aa32cb3b` | **`c794aa32cb3b`** ✅ | `7b632962c20a` ✗ |
| `app/services/inventory_qa.py` | `2b62e497d9a8` | **`2b62e497d9a8`** ✅ | `2b62e497d9a8`（未变） |

**结论：生产容器 `yuanliaokc-mcp:real-2e05d24` 精确对应 commit `2e05d24`。13,510 行新代码一行都没有部署。**

### 2.2 部署的那个东西，其实什么也没在做

| 观测 | 值 |
|---|---|
| 容器内唯一进程 | `sleep infinity`（37 小时 CPU 累计 0 秒） |
| `docker logs` | 完全为空 |
| 健康检查 | `test -f /data/scd_wms.db` —— 文件存在即 healthy |
| DB 大小 | 268 KB |
| 有数据的表 | `materials`(29) / `material_tags`(29) / `batches`(23) / `warehouses`(3) |
| `inventory_transactions` | **0** |
| `users` | **0**（没人登录过） |
| 所有行 `created_at` | 全部 `2026-08-27 23:44:52`（容器启动后 36 秒的种子灌入） |
| MCP 客户端配置 | `~/.codex/config.toml`、`~/.claude.json`、子牧 `.mcp.json` 中**均无 yuanliaokc 条目** |

> **"Up 37 hours (healthy)" 不代表任何服务在工作。** 它是一个待命外壳：部署完成，但从未接线、从未产生一条业务流水。

### 2.3 InvenTree 在这台机器上不存在

```
docker images | grep -i inventree   →  无
docker ps -a   | grep -i inventree  →  无
```

镜像仓库名只有三类：`zimu-fulfillment-*`、`yuanliaokc-mcp`、三方基础镜像（redis / postgres / metabase）。

**这意味着 §0.2 里那 13,510 行代码，全部是对着一个不存在的 InvenTree 写的。** 作者本人在验收文档里也是这么说的（`backend/docs/acceptance-170.md`）：

> 「`inventree_part_mappings` 为 0，且 InvenTree 1.5.2 容器本环境未提供」

`backend/docs/acceptance-169.md` 的验收结果表更直白：

> 「容器业务验收 … **本环境未提供容器，实际未执行（skip，非通过）**」

我实跑了整套测试确认这一点：

```
279 passed, 11 skipped in 3.97s
```

**11 个 skip 全是 `test_inventree_integration.py`**——该文件 `pytestmark = pytest.mark.skipif(not _base_url())`，未设 `INVENTREE_BASE_URL` 就整体跳过。也就是说，**唯一会碰真实 InvenTree 的测试，一次都没跑过。** 279 个通过的测试全部对着注入的假 HTTP。

补充：FEFO 契约的依据文档 `d:/微模块搭建/inventree-spike/GO-NOGO.md`（`test_inventree_integration.py:5` 引用，`allocation.py:17` 与 `inventree_client.py:358` 都写「见 GO-NOGO #5/#8」）**不在仓库里**，是一个本机 Windows 路径。整个 InvenTree 适配层的契约依据无法审计。

### 2.4 新代码引入了一个 P0 回归（已实测复现）

`schemas.py` 里 **`OrderOut` 被定义了两次**：

- `schemas.py:216` `class OrderOut(ORMModel)` —— 原生产单用，含 `entry_type` / `image_id` / `production_date` / `recognize_error` / `items`
- `schemas.py:851` `class OrderOut(BaseModel)` —— 新增的子牧订单用，字段完全不同

后者在模块级**覆盖**前者。而 `routers/production.py:40` 导入 `OrderOut`，用在 **8 个端点**的 `response_model` 上（`:69 :129 :167 :213 :321 :427 :474 :506`），这些端点全部 `return _load_order(...)` 返回 ORM 对象。

实测验证：

```
production.OrderOut is schemas.OrderOut          → True
OrderOut 实际字段  → ['created_at','customer_name','id','lines','order_no',
                      'sales_order_pk','sales_order_reference','shipment_pk','status']
生产单需要但缺失   → ['entry_type','image_id','items','production_date','recognize_error']
from_attributes    → None      ← 新类是裸 BaseModel，不能从 ORM 对象构造

OrderOut.model_validate(<ProductionOrder 实例>)
  → ValidationError: Input should be a valid dictionary or instance of OrderOut
```

**影响：生产单的上传 / 手工建单 / 查询 / 审核 / 草稿 / 批准 / 驳回 / 冲销 8 个端点在运行时全部 500。**

引入点已定位：`git log -S "class OrderOut(BaseModel)"` → **`d973492`（inventory-v1 07，子牧订单 Allocation）**。部署版 `2e05d24` 只有 1 个 `OrderOut`（干净），HEAD 有 2 个。

讽刺的是，**引入这个回归的正是「对接子牧」那张票**。而 279 个测试无一捕获——生产单 API 的响应形状没有测试覆盖。

### 2.5 打包缺陷没修，而且扩散了

上一版文档记录：`backend/pyproject.toml` 漏声明 `langchain-openai`，导致 `import app.main` 失败。

**本次实测：缺陷仍在，且命中点变多了。**

```
pip install -e "./backend[dev]"   # 只装 backend 声明依赖
python -c "import app.main"
  → backend/app/services/imports.py:36  from .ocr import OcrResult
  → backend/app/services/ocr.py:22      from langchain_openai import ChatOpenAI
  → ModuleNotFoundError: No module named 'langchain_openai'
```

`langchain-openai` 只声明在**根** `pyproject.toml:13`，不在 `backend/pyproject.toml`。而新增的 `backend/app/services/ocr.py` 又多了一处 import。原缺陷未修，新代码在同一个坑上又踩了一脚。

### 2.6 其它工程缺口（相对上一版无改善）

| 项 | 状态 |
|---|---|
| Dockerfile / docker-compose | **仍然没有**（`git ls-files | grep -i dockerfile|compose` → 空） |
| CI（`.github/workflows`） | **仍然没有** |
| 前端 | **13,510 行后端零对应 UI** —— `git diff --stat 2e05d24..HEAD -- frontend/` 为空 |

**新功能全部是 API-only。运营无法通过现有 React 应用使用其中任何一项。**

---

## 3. 和子牧的关系

### 3.1 库存概念不重叠 —— 两边说的是不同的东西

这是评估的核心，先把口径摆清楚：

| | 子牧 `inventory` 模块 | yuanliaokc（新架构） |
|---|---|---|
| 语义 | **外部平台报告的可售量的历史观测** | **自有仓库的实物原料库存** |
| 类型名 | `InventoryDetailObservation`、`InventoryCoverage` | `Batch`、`StockItem`(InvenTree) |
| 数据源 | `app.provider_stock_snapshots`（京东 ISC 查询写入） | 人工导入 + OCR → InvenTree |
| 对象 | **成品 SKU**（`app.skus`，「公司内部唯一可履约 SKU」） | **原料**（`materials`，牛羊肉分割件） |
| 粒度 | 仓编码 `warehouse_code`，**无库位、无批次、无效期** | 库位 + 批次 + 效期（FEFO） |
| 读写 | **纯只读**（12 个文件零 INSERT/UPDATE/DELETE） | 读写 + 预占 + 释放 |
| 缺数据时 | `NOT_OBSERVED`，**绝不补零** | 四态：`zero`/`unobserved`/`stale`/`partial` |
| 真源 | 京东（子牧只是观测者） | InvenTree（若部署） |

子牧侧的自我定位写得很明确（`InventoryOverviewService.java:18-24`）：

> 「读取已落库的最新库存观测，不将无观测补成零，也不替代履约方的库存决策」

数据库层还有硬门闩：非托管履约方**只允许** `provider_type='JD_WAREHOUSE' AND source_type='JD_ISC_QUERY_STOCK'` 的观测入库，否则 `RAISE EXCEPTION 'third-party inventory is outside this system'`（`V16__allow_external_jd_stock_observations.sql:1-26`）。`app.provider_stock_snapshots` 是 append-only（`V1__baseline.sql:2158-2160` 触发器拒绝 UPDATE/DELETE）。

**结论：两边不存在「谁是真源」的冲突，因为它们根本不指向同一批物理货物。**
- 子牧管的是**成品在京东仓的可售量**，真源是京东，子牧只观测。
- yuanliaokc 管的是**原料在自有仓的实物量**，真源是 InvenTree（尚不存在）。

### 3.2 真正的障碍：两边没有任何连接键

`app.skus` 完整字段（`V1__baseline.sql:121-139` + `V13` 加的两个价格）：

```
id, sku_sequence_no, sku_code, product_id, fulfillment_provider_id,
specification, unit, barcode, active, lock_version, created_at, updated_at,
purchase_price, retail_price
```

**没有 BOM、没有配方、没有原料关联字段、没有库存数量字段。**

全仓唯一的「原料」概念是 `app.products.ingredients VARCHAR(1000)`（`V30__add_product_archive_fields.sql:4`）——**一个自由文本字段**，不是结构化关联。唯一的 BOM 概念是 `app.product_bundles` / `app.bundle_items`（`V39`），但那是**成品礼包组合**，`bundle_items.sku_id` 指向 `app.skus`，且 V39 头部明确「礼包本身不创建 internal_sku、不单独计库存」。

> **「一个子牧成品 SKU 消耗哪些原料、各多少」这个映射，在两个系统里都不存在，也没有任何一张票在做它。**

这是所有「深度接入」方案的**前置阻塞**。没有它，子牧订单无法翻译成原料需求，`/api/orders` 的 FEFO 预占对子牧就是无意义的——yuanliaokc 的 `ZimuOrderLine.material_id` 直接指向 `materials.id`，等于**假设子牧订单行本身就是原料行**，这与子牧的实际业务（卖成品礼盒）不符。

### 3.3 yuanliaokc 已经单方面假设了一种接入形态

`models.py:822-928` 的 `ZimuOrder` / `ZimuOrderLine` / `OrderAllocation` 三张表，以及 `/api/orders/*` 端点，构成一个**完整的、子牧从未参与设计的接入契约**：

- 方向：**子牧 → yuanliaokc 推订单**（yuanliaokc 不主动调子牧，全仓无子牧 HTTP 客户端）
- 幂等：客户端供给 `idempotency_key`（唯一约束）+ `payload_hash` 检测同键不同载荷 → 409
- 语义：一行订单 → FEFO 预占 N 个 StockItem，跨批次自动拆分，缺口显式化为 `shortage_kg`
- 修订：`version` 递增，按版本释放旧预占

这套设计**质量很高**（幂等语义、结果未知不盲重试、缺口不静默吞掉都做对了）。但它是**单方面的**：子牧侧没有任何代码知道它存在，`ZimuOrderLine.material_id → materials.id` 的假设也与子牧的 SKU 模型对不上（§3.2）。

### 3.4 一处「设计重复、数据互补」的对称

yuanliaokc 的 `external_stock.py`（#168）建模的东西，**正是子牧 inventory 模块已经在做的事**：京东/供应商库存快照、四态区分、不把未观测当零、不进内部 FEFO。设计得很干净。

但：

```
backend/app/routers/external_stock.py:37
  「未接入真实京东/供应商连接器前，生产环境返回 None（sync 端点据此 503）」
```

**yuanliaokc 有模型没数据；子牧有数据没这套模型的必要（它自己就是那个模型）。**

反过来，子牧侧也有缺口：`connector/` 下**只有 `jd/` 有 `stock/` 子包**，`caishixian/` / `jufubao/` / `feixiang/` 均无库存客户端——这三家的 SKU 在 overview 里恒为 `NOT_OBSERVED`。

---

## 4. 接入方案

先说结论：**现阶段唯一理性的动作是选项 A。B 和 C 都有硬前置未满足。**

### 4.0 子牧侧已有的接入基础设施

评估选项前，先确认子牧这边能用什么现成机制。

**MCP 工具注册**（`mcp/McpToolRegistry.java`）：
- 显式列表 + 构造器注入，**非注解驱动**。注册入口 `:43-64`，逐个 `tools.addAll(provider.tools())`
- 模块开关：`app.mcp.modules`（env `MCP_MODULES`，逗号分隔），读取点 `:51`，解析 `:119-138`
- **空配置 = 零模块**（fail-safe，ADR `docs/adr/0015-mcp-modules-fail-safe-default.md`）；未知模块名 → 启动期 `IllegalStateException`
- 被排除的工具在 `find()` 直接查不到，`tools/list` 与 `tools/call` 天然一致，无「列表藏起来但还能调」的假隔离
- 现有 9 模块 41 工具，其中 `inventory` 模块 2 个工具（`get_inventory_overview` / `get_inventory_detail`，`McpDomainReadTools.java:167/180`），与 REST 共用同一个 Service，无第二套 SQL

**写工具当前全线关闭**：`McpServer.java:180-182` 无条件拒绝 `!tool.readOnly()`。`McpWriteGate` 已写好但**从未接线**（`fromConfig` / `permits` 在 `src/main` 零调用，`application.yml` 里没有对应 key）。8 个 `write` 模块工具目前一个都调不到。

**最关键的现成先例——子牧已经接过一次同事的服务**：`followup/KehuzxRemoteReadTools.java` 让子牧作为 **MCP 客户端**去调远程 kehuzx MCP server，配置在 `application.yml:114-137`：

```
app.kehuzx.mcp.enabled / .endpoint / .read-token / .allowed-host / .allowed-port(9100)
                       / .connect-timeout(PT3S) / .read-timeout(PT10S)
                       / .contract-version(kehuzx-mcp-v1) / .upstream-commit
app.kehuzx.mcp-write.* （含 approval-signing-key / approval-ttl-seconds）
```

注意 `.allowed-host` / `.allowed-port` / `.contract-version` / `.upstream-commit` 这几个字段——**这是一套已经踩过坑的、带 SSRF 防护和版本钉死的远程 MCP 消费模板。** 它还用 `externallyDiscoverable()=false`（`:147-152`）把工具限制在内部 Agent 可见、不对外暴露。

---

### 选项 A：只读消费，照搬 kehuzx 模板（**推荐**）

**做什么**：给 yuanliaokc 补一个只读 HTTP MCP 面（或复用已部署的 stdio 3 工具），子牧侧新增 `YuanliaokcRemoteReadTools`，走 `app.yuanliaokc.mcp.*` 配置，注册为新模块 `inventory-internal`，默认不进 `MCP_MODULES`。

**子牧侧改动**（agent 已给出精确插入点）：
1. 新建 `inventory/YuanliaokcRemoteReadTools.java`，照 `KehuzxRemoteReadTools.java` 写
2. `McpToolRegistry.java` 三处：`:44-53` 构造参数加 provider、`:54-64` 加 `tools.addAll(...)`、`:33-41` 便捷构造补 `null`
3. schema 必须用 `McpToolRegistry` 的助手方法（`:160-197`），否则 `McpToolSchemaConverter.java:18` 转不了
4. 测试同步：`McpToolRegistryModuleFilterTest`、`AgentToolBindingFactoryTest:180,196`

**代价**：子牧侧 1 个新类 + 3 处改动 + 2 个测试；yuanliaokc 侧把 `/srv/mcp_server.py` 回流进仓库并加 token 认证。

**风险**：
- 低。只读、模块默认关闭、fail-safe 语义已验证
- 不碰 InvenTree，不依赖 BOM，不需要 yuanliaokc 的新 13,510 行
- **但**：yuanliaokc 现在 DB 里只有种子数据（§2.2），接进来展示的是 29 条假原料。**接线之前必须先有真实数据。**

**先决条件**：无（这是唯一一个不需要 InvenTree、不需要 BOM 的选项）。

---

### 选项 B：子牧推订单，yuanliaokc 做 FEFO 预占

**做什么**：用 yuanliaokc 已建好的 `/api/orders/*` 契约（§3.3），子牧确认订单后推送订单行，由 InvenTree 做 FEFO 预占。

**代价**：
- 子牧侧：新增订单推送 + 幂等键管理 + 释放/修订/取消的反向路径 + 对账处理（`reconciliation_required`）
- yuanliaokc 侧：部署 InvenTree、修 §2.4 的 P0 回归、修 §2.5 的打包、补 Dockerfile/compose/CI、补前端
- **两边共同**：先建 BOM（`成品 SKU → 原料 × 数量`），这是从零开始的一个独立项目

**风险**：**高，且有一个可能致命的物理约束。**

zimupc 的 Docker Desktop 总内存 **1.915 GiB**，当前已用约 1150 MiB（metabase 575 + backend 450 + 其余），**余量约 810 MiB**。InvenTree 官方部署是 5 个容器（Django server + worker + PostgreSQL + Redis + proxy）。

> **这台机器几乎肯定放不下 InvenTree。**（我未实测 InvenTree 1.5.2 的真实占用——标记为**未验证**——但按 Django+PG+Redis 的常规量级，810 MiB 余量风险极高。这台机器历史上已经因内存被压挂过。）

**先决条件**：BOM（不存在）+ InvenTree（不存在，且可能放不下）+ §2.4/§2.5 修复。**三个都未满足。**

---

### 选项 C：反向供数 —— 子牧把京东观测推给 yuanliaokc

**做什么**：子牧有真实京东 ISC 库存数据，yuanliaokc 的 `external_stock` 有模型没连接器（§3.4）。子牧定期 POST 到 `/api/external-stock/observations`。

**代价**：子牧侧一个定时任务 + 一个 HTTP 客户端；yuanliaokc 侧零改动（端点已就绪且有完整测试）。

**风险**：中。
- 需要 §5.1 的认证方案（`require_writer`，是**写**端点）
- `app.provider_stock_snapshots` 是成品 SKU 观测，推给一个原料系统，**业务价值存疑**——除非先有 BOM
- 会在 yuanliaokc 侧创造一份子牧数据的副本，多一个不同步风险点

**先决条件**：认证方案 + 一个能说清楚的业务理由（目前我看不出来）。

---

### 建议路径

```
现在：  选项 A（只读接入）—— 但先让 yuanliaokc 有真实数据
        并行：在 zimu 仓把 #160–#170 的现状写清楚（见 §6）
之后：  若原料库存真的要进子牧决策链 → 先立独立的 BOM 票
        BOM 落地后再评估 B，且必须先解决 InvenTree 的部署位置（大概率不能放 zimupc）
不建议：C，除非能说清业务理由
```

---

## 5. 绕不开的坑

### 5.1 认证：两边模型不兼容，且有一个默认弱密钥

| | 子牧 | yuanliaokc |
|---|---|---|
| 机制 | 网关断言（`X-Authenticated-Operator` + `X-Gateway-Assertion`，常数时间比对）或共享 Basic；MCP HTTP 面用 Bearer token（`McpHttpTokenAuthenticator.java:47-56`，`MessageDigest.isEqual`） | JWT HS256，12h |
| 主体 | operator（可复验身份） | 人类 user + 角色 |
| 服务账号 | 有（网关断言） | **无** |

**问题**：子牧要调 yuanliaokc 的写端点，必须持有一个人类账号的密码并每 12 小时刷新 JWT。没有服务账号、没有 token 轮换、没有最小权限（`require_writer` = operator/reviewer/admin 任一，粒度很粗）。

**且**：`config.py:19` `jwt_secret` 默认 `"change-me-in-production"`。生产未覆盖即为**任何人可伪造任意角色的 JWT**。当前部署因 `users` 表为 0、无人登录而未被利用，但一旦接线就是首要风险。**建议接入前先确认部署侧 `.env` 是否覆盖了 `JWT_SECRET`（我未能读取远端 `.env`，标记未验证）。**

### 5.2 未认证端点泄露订单数据

`routers/orders.py:161`：

```python
@router.get("/{order_id}", response_model=OrderOut)
def get_order(order_id: int, db: Session = Depends(get_db)):
```

**这是新增 32 个端点里唯一没有认证依赖的一个**（其余全部有 `get_current_user` / `require_writer` / `require_reviewer`）。按整数 ID 可枚举，返回：

- `customer_name`（客户名，PII）
- `idempotency_key`（幂等键 —— 泄露后可被用于制造 409 冲突）
- `sales_order_pk` / `shipment_pk` / `allocation_pk` / `stockitem_pk`（InvenTree 内部主键）
- `stockitem_batch` / `expiry_date` / `quantity_kg` / `shortage_kg`（批次、效期、缺口量）

对比同文件其它 5 个端点均为 `require_writer`，这几乎确定是**遗漏而非设计**。子牧接入前必须让对方修掉。

### 5.3 幂等：契约良好，但键的所有权要谈

yuanliaokc 侧做得对：`idempotency_key` UNIQUE + `payload_hash` 检测同键不同载荷 → 409；行已分配的不同键请求返回首次结果，不重复分配。写操作**绝不重试**，网络错误/5xx → `reconciliation_required`，等人工对账。

**需要谈的**：键由客户端（子牧）供给。子牧必须保证键的全局唯一与可重放，并实现 `reconciliation_required` 的处理路径——**这个状态没有自动出路，必须有人看**。子牧侧目前没有任何对账 UI 或告警。

### 5.4 数据一致性：跨系统双写无事务

`allocate_order_line` 的实际动作：创建 InvenTree Customer → Sales Order → Line → Shipment → Allocation，同时写本地 5 张表。**跨两个存储、无分布式事务。** 代码用「读回验证 + 结果未知不重试」缓解（`_read_back_allocations`，`allocation.py:266`），设计是对的，但意味着：

- 存在本地已写、InvenTree 未写（或反之）的窗口
- 恢复靠人工对账，无自动补偿
- **且这条路径从未对着真实 InvenTree 跑过**（§2.3）

### 5.5 PII 与商业数据：公开仓库里有真实供应商数据

`rgfan123/yuanliaokc` 是 **public** 仓库。`test_tp/` 下提交了：
- **9 张真实原料照片**（jpg）
- **`原料入库表.xlsx`** —— 真实入库表，含供应商与商品名：`百润万丰谷饲牛上脑`、`百润万丰黑安格斯极佳眼肉心`、`中敖每日鲜羔羊肉卷`、`陇盛源羊后腱`、`清月兴羊肉卷`、`俄罗斯32厂极佳西冷牛排`、`新西兰牛背肋排`，以及生产日期

这不是 PII，但是**商业敏感的供应商与采购信息，公开可见**。

好消息：`.env` 已被 gitignore，我扫描未发现任何密钥泄露（`sk-*` / `AKIA*` / private key 均 0 命中）。

另：`.e2e/node_modules`(115 文件)、`.claude`(101)、`.agents`(100) 也被提交进公开仓库。

### 5.6 谁写谁读

接入后的清晰边界（若走选项 A）：

| 数据 | 写 | 读 |
|---|---|---|
| `app.provider_stock_snapshots`（成品外部可售量） | 子牧 fulfillment 模块（京东 ISC） | 子牧 inventory 模块 + MCP |
| InvenTree StockItem（原料实物） | yuanliaokc（人工确认后） | yuanliaokc；子牧**只读** |
| `zimu_orders`（订单镜像） | —— 选项 A 下不启用 —— | —— |

**红线：子牧不应该写 yuanliaokc 的库存，yuanliaokc 不应该写子牧的快照表**（后者在 DB 层已被 `V16` 触发器强制拒绝）。

### 5.7 部署位置

yuanliaokc 现在零端口、`docker exec` stdio、10 MiB 常驻，与子牧零冲突——这个形态很好，**选项 A 应当保持它**（若加 HTTP MCP 面，走 compose 内网而非 host 端口，参考 kehuzx 的 `allowed-port 9100` 模式）。

但 InvenTree 进不来（§选项 B）。若将来真要上，需要另一台机器或先给 zimupc 扩内存。

---

## 6. 对 inventory-v1 规划的表态

任务要求我对「inventory-v1 规划是否已被现实取代」表态。**我的结论是：两个说法都不对。**

**它没有被现实取代。** 「现实」指的本该是生产上跑着的 yuanliaokc——但那是个 `sleep infinity` 的空壳，29 条种子原料、0 条业务流水、0 个用户、无客户端接线（§2.2）。#160 的问题陈述「目前靠 Excel 和图片人工维护」**在事实层面仍然成立**：没有任何真实库存数据进入过任何系统。

（说明：我未能读到 #160 的正文原文——`gh` token 在调研中途失效，`gh auth status` 报 `The token in keyring is invalid`。上述问题陈述**转述自任务背景，未经我独立验证**。issue 的标题、编号、状态与标签是在 token 失效前成功获取的，可信。）

**但它也不再是一份可以照着执行的规划。** #162–#170 这 9 张票的代码**已经写完了**，13,510 行，在 `yuanliaokc` 仓，2026-08-28 完成。票还开着，没人知道。

所以真实状态是第三种：**已实现、未落地、未追踪。** 具体地：

| 维度 | 状态 |
|---|---|
| 代码 | ✅ 存在（yuanliaokc `e11d661`），质量总体不错 |
| 测试 | ⚠️ 279 通过，但**全部对着假 InvenTree**；11 个真实集成测试从未跑过 |
| InvenTree（#161） | ❌ 从未部署，且 zimupc 大概率放不下 |
| 部署 | ❌ 生产仍是 `2e05d24`（哈希硬确认） |
| 前端 | ❌ 零 UI |
| 打包 | ❌ 无 Dockerfile/compose/CI，且 import 就挂 |
| 回归 | ❌ 引入 P0：生产单 8 个端点 500 |
| 票 | ❌ #160–#170 全部 OPEN，无一条记录指向 yuanliaokc |

**建议的收口动作**（均需人工决策，我未执行任何 GitHub 写操作）：

1. **立刻**：在 #162–#170 各留一条评论，写明「已在 rgfan123/yuanliaokc `<sha>` 实现，未部署、未对真实 InvenTree 验收」，防止重复实现。这是当前最高性价比的动作。
2. **#161 单独处理**：InvenTree 部署是所有其它票的前置，且有物理内存约束。应先做容量决策（换机器 / 扩内存 / 换引擎），再谈其余。
3. **另立两张票**：(a) `OrderOut` 重名回归修复（P0，阻塞生产单）；(b) `orders.py:161` 补认证（安全）。
4. **另立一张 BOM 票**：`成品 SKU → 原料` 映射。这是 §3.2 的根因阻塞，目前无人在做，而选项 B 完全依赖它。
5. **重新评估 #160 的架构前提**：它假设 InvenTree 做独立库存引擎。在 810 MiB 余量的现实下，这个前提值得重新论证。

---

## 附录：本报告的取证边界

**我实际执行并验证的**：
- 克隆 `yuanliaokc` HEAD `e11d661`，读代码、跑测试（279 passed / 11 skipped）
- 实测 `import app.main` 失败（`ModuleNotFoundError: langchain_openai`）
- 实测 `OrderOut` 重名与 `model_validate` 失败
- SHA256 比对确认部署版本 = `2e05d24`
- 解析全部 32 个新端点的认证依赖
- 复核子牧仓 `inventree` 全历史 0 命中
- 子牧侧代码审计（inventory 12 文件 / skus DDL / MCP 注册机制），带行号
- zimupc 容器只读探查（9 容器、镜像、DB 表行数、内存、端口）

**未验证，明确标注**：
- **#160 正文原文** —— `gh` token 中途失效，无法读取 issue body。问题陈述转述自任务背景。
- **InvenTree 1.5.2 实际内存占用** —— 未部署未实测，810 MiB 余量的判断基于 Django+PG+Redis 常规量级推断。
- **生产 `MCP_MODULES` 实际值** —— 仓内文档 `docs/research/procurement-agent-readiness-2026-08-28.md:64,240` 记载为 `masterdata,inventory,orders-read`，未读到运行时事实。
- **部署侧 `JWT_SECRET` 是否覆盖默认值** —— 未读取远端 `.env`。
- **`GO-NOGO.md`** —— 在 `d:/微模块搭建/inventree-spike/`，仓库外，无法审计。整个 FEFO 契约的依据不可复核。

**遵守的约束**：全程只读；未改动子牧仓任何文件（除本报告）；未改动 yuanliaokc；未部署、未重启任何容器；未对 GitHub 做任何写操作。
