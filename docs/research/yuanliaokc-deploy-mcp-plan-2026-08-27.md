# yuanliaokc（原料库存系统）最小部署 + MCP 暴露方案

调研日期：2026-08-27
目标仓库：`https://github.com/rgfan123/yuanliaokc`（master，7 个 commit，HEAD = `2e05d24`）
分析用克隆：`<scratchpad>/yuanliaokc`（只读，未做任何修改）
目标主机：zimupc（Windows + Docker Desktop，ssh 别名 `zimupc` → Tailscale `100.116.12.119`）
执行体：Codex（本文第 5 节是给它的逐步清单，不需要再做任何设计决策）

---

## 1. 结论摘要

1. **「MCP 做了差不多了」这个说法不成立。仓库里没有任何 MCP server、没有 MCP transport、连 `mcp` 这个包都没装。** 所谓「MCP 工具层」是后端里三个普通 Python 函数（`total_inventory` / `search_inventory_by_usage` / `search_inventory_by_name`），由企业微信适配器**直接函数调用**。仓库自己的 spec 白纸黑字把 `/mcp` 端点列进了 **Out of Scope**（「`/mcp` 端点的对外暴露与正式 MCP transport——可延后」）。要有 MCP，必须**新写一个 server 进程**。
2. 好消息：**这三个函数是干净的纯查询函数，包装成 MCP server 的成本极低，而且不需要任何密钥。** Qwen API key 只被「自然语言路由器」用（把中文问句翻译成 `{tool,args}`）——而 MCP 客户端自己就是 LLM，这一层天然被替代。**本方案的 MCP 不需要 `DASHSCOPE_API_KEY`，不需要任何企业微信凭证。**
3. 我已经在本机把设计好的 MCP server **实测跑通**：`initialize` / `tools/list` / `tools/call` 全部正确返回，stdout 零污染，三个工具都返回了真实数据（总库存 23 种原料 7982.07 kg）。第 5 节里的代码是实测通过的版本，不是设计稿。
4. **推荐形态：独立 compose 项目，只跑 MCP，不跑 Web，不开任何端口。** 常驻容器只是 `sleep infinity`（内存 ≈ 0），每次 MCP 会话由 `docker exec -i` 拉起一个短命 Python 进程（实测峰值 RSS **80 MB**）。
5. **这个决定不是省事，是被硬约束逼的：zimupc 的 Docker 只有 ~1.9 GiB 内存预算**（不是 16 GB —— 16 GB 是宿主机，Docker Desktop/WSL2 只拿到 1.915 GiB），其中子牧栈已占 ~950 MB（backend 446 MB + metabase 464 MB）。跑完整 Web 后端要多背 langchain/langgraph 一整套（+55 MB 依赖，进程常驻 250–400 MB），会把剩余余量吃掉大半。
6. **与现有系统零冲突**：不占端口（子牧只占 80，本方案占 0）、容器名/卷名/项目名前缀全新、不碰 Postgres/Redis（用 SQLite 单文件）、企微侧子牧用的是 **aibot（智能机器人）** 而本项目是 **自建应用（应用消息）**，是企微两条不同产品线，互不相干。
7. **未完成度诚实评估**：功能代码本身质量不错、55 个测试全绿；但**打包是坏的**——`backend/pyproject.toml` 漏声明了 `langchain-openai`，只按声明依赖安装的话后端**根本 import 不起来**（`pytest` 直接 2 个 collection error，跑 0 个测试）。仓库没有 Dockerfile、没有 compose、没有 CI。本方案的 MCP-only 形态刚好**绕开**这个坑（三工具的 import 链不碰 langchain）。

---

## 2. 仓库解剖

### 2.1 模块构成

| 模块 | 路径 | 说明 |
|---|---|---|
| LangGraph 识别 Agent | `src/scd_mk/`（7 文件，611 行） | 生产单图片 → Qwen 多模态识别 → 确定性算损耗。可独立 CLI 运行（`scd-mk <图片>`） |
| FastAPI 后端 | `backend/app/`（10 routers + 11 services，5100 行） | 仓储业务全栈：登录/物料/库存/生产单/批次/盘点/报表/企微 |
| React 前端 | `frontend/`（22 页面） | React 19 + AntD 5 + Vite 6，dev 端口 5173，`/api` 代理到 8100 |
| 企微库存问答 | `backend/app/services/inventory_qa*.py` + `routers/wecom.py` | 本次关注对象，详见第 3 节 |
| E2E 脚本 | `.e2e/` | 一次性 Playwright 排查脚本；**`node_modules` 被误提交进 git（13 MB）** |

`backend/README.md` 声称 Sprint 1–4 全部完成，`docs/使用说明.md` 标注 2026-08-21 完工。

### 2.2 技术栈与版本要求

- **Python `>=3.10`**（两个 pyproject 都这么声明）。实测 3.11.14 与 3.13.0 均全绿。建议镜像用 **3.12-slim**。
- **Node**：`frontend/package.json` 未声明 `engines`；Vite 6 + React 19 实际需要 Node 18+，建议 20 LTS。本方案不构建前端。
- **无重量级依赖**：没有 torch、没有 numpy/scipy、没有 playwright 浏览器下载（`.e2e` 里只有 `playwright-core`，不含二进制浏览器）。
  - backend 声明依赖装完 **113 MB** site-packages / 43 包，最大是 sqlalchemy 19 MB、cryptography 12.8 MB。
  - 根包（LangGraph）再加 **+55 MB / +30 包**（langgraph 1.2.11、langchain-openai 1.6.0、openai 3.5.0、tiktoken、langsmith…）。
- **数据库：SQLite 单文件**。`backend/app/config.py:17` 默认 `sqlite:///backend/scd_wms.db`，注释写明生产可改 PostgreSQL（`pg` extra 已备好 `psycopg[binary]`）。**本方案坚持 SQLite**，理由见 4.3。
- **现成 Dockerfile / compose：一个都没有。** 全仓库 `git grep` 无 Dockerfile / docker-compose / CI 配置。文档里的启动方式只有 PowerShell 的 `uvicorn --reload` + `npm run dev`。

### 2.3 配置与密钥（两套 `.env`，是个坑）

| 变量 | 读取位置 | 用途 |
|---|---|---|
| `DATABASE_URL` / `JWT_SECRET` / `UPLOAD_DIR` / `CORS_ORIGINS` | `backend/.env`（`config.py` 的 `SettingsConfigDict(env_file=BACKEND_DIR/".env")`），也吃进程环境变量 | 后端基础配置 |
| `DASHSCOPE_API_KEY` / `QWEN_MODEL` / `QWEN_BASE_URL` | **仓库根 `.env`**（`services/classification.py:17`、`services/inventory_qa_router.py:21`、`routers/wecom.py:27` 各自 `load_dotenv(_ROOT/".env")`） | Qwen 调用 |
| `WECOM_CORP_ID` / `WECOM_AGENT_ID` / `WECOM_SECRET` / `WECOM_TOKEN` / `WECOM_ENCODING_AES_KEY` | **仓库根 `.env`** | 企微自建应用 |

两套加载路径**不互通**，填错位置会静默失效。**本方案一个都不需要**（见 4.4）。

### 2.4 测试真实状态（实跑，非推测）

在干净 venv 里按仓库声明安装后实测：

| 场景 | 结果 |
|---|---|
| 只装 `backend/pyproject.toml` 声明依赖 → `import app.main` | ❌ `ModuleNotFoundError: No module named 'langchain_openai'` |
| 只装 backend 声明依赖 → `pytest` | ❌ `collected 26 items / 2 errors` → **Interrupted，实际跑 0 个测试** |
| 强制跳过 collection error 后 | 26 passed / 0 failed / **0 skipped** |
| 再装根包（LangGraph 那套）后 → backend `pytest` | ✅ **44 passed / 0 failed / 0 skipped / 0 errors** |
| 根包自己的 `tests/` | ✅ **11 passed** |
| **全装齐总计** | ✅ **55/55 passing，约 2 秒** |
| 只装 SQLAlchemy+Pydantic+pytest 跑 MCP 相关那 3 个测试文件 | ✅ **17 passed in 0.16s**（我本机实跑，证明三工具不依赖 langchain） |

**全仓库零 `skip` / 零 `xfail` / 零 `NotImplementedError` / 零 `FIXME`。** 唯一 5 处 `TODO`/`暂不` 命中全是假阳性（前端常量名 `TODO_ITEMS`、文档正文里的范围说明）。测试本身是 hermetic 的：不联网、不需要 API key，LLM 通过 `llm_call=` 参数注入假实现。

**根因判断**：仓库**没有 CI**（无 `.github/workflows`），测试只在「恰好也装了根包」的开发机上跑过，所以这个缺失的依赖一直没被发现。

### 2.5 已知遗留项（票里自己写的）

`.scratch/wecom-inventory-qa/issues/` 5 张票 **`Status:` 全部 `resolved`**，checklist 全 `[x]`。唯一被显式标注的 deferred：

> 「遗留（非本票范围）：前端「库存搜索」页链接当前用 formatter 默认 localhost 值，生产部署时再按 settings 覆盖。」
> → `backend/app/services/inventory_qa_format.py:17` `DEFAULT_FRONTEND_URL = "http://localhost:5173/inventory-search"`

**本方案不受影响**：那个链接只在「结果超长截断」时出现在企微 markdown 回复里；MCP 返回的是结构化 JSON，不走 formatter。

---

## 3. MCP 现状：不存在，需要新写

### 3.1 证据

- `git grep -i mcp` 全仓库命中 **6 处**，全是**注释和文档里的名词**，没有一行是代码：
  - `backend/app/schemas.py:152` —「工具层（MCP）与格式化器共同消费」
  - `backend/app/services/inventory_qa.py:1` —「MCP 工具（ticket 02）的公共底座」
  - `.scratch/wecom-inventory-qa/spec.md` 若干处
- **`mcp` 包不在任何 pyproject 的依赖里。**
- spec 的 Implementation Decisions 原文：

  > 「**MCP 的定位分两层**：内嵌在后端的**三个工具**（标准查询能力）+ **企业微信适配器**（回调 + 推送）。适配器**直接调用工具函数**，不走 MCP transport 往返。」

  Out of Scope 原文：

  > 「`/mcp` 端点的对外暴露与正式 MCP transport——可延后。」

**结论：仓库作者用「MCP 工具」指的是「设计上可被 MCP 复用的函数签名」，不是 MCP 协议实现。传输方式：无。注册的工具：无。能否独立启动：无进程可启动。**

### 3.2 三个函数的真实签名（这是我们要包装的东西）

`backend/app/services/inventory_qa.py`：

```python
def total_inventory(db: Session) -> InventorySummary
def search_inventory_by_usage(db: Session, usage: str) -> InventorySummary
def search_inventory_by_name(db: Session, keyword: str) -> InventorySummary
```

返回类型 `InventorySummary`（`backend/app/schemas.py:151`）：

```
InventorySummary
├── items: list[InventorySearchRow]
│     material_id / material_code / material_name / category / spec
│     tags: list[str] / current_kg: float / available_kg: float / batch_count: int
└── totals: InventoryTotals
      current_kg / available_kg / batch_count / item_count
```

语义（已读源码确认）：只统计**原料**（`category != "成品"`）、只统计**在库**（`is_active` 且 `current_kg > 0`）批次，按 `Material.code` 排序，重量 round 到 3 位。`search_inventory_by_usage` 先按 `MaterialTag.tag` 模糊匹配，**无命中时自动回退按名称/编码搜**。

### 3.3 依赖面（关键发现，决定了镜像有多小）

`inventory_qa` 的完整 import 链：

```
app.services.inventory_qa
  → app.models      （只需 SQLAlchemy）
  → app.schemas     （只需 Pydantic）
  → app.database    → app.config （只需 pydantic-settings）
```

**不碰 FastAPI、不碰 langchain、不碰 langgraph。** 已实测：只装 `SQLAlchemy + pydantic + pydantic-settings + pytest` 就能跑通这三个函数的 17 个测试。

对比之下：
- `app.main` → `routers/stock.py:31` → `services/classification.py:13` → `langchain_openai` ❌（后端整体的硬依赖）
- `app.seed` → `app.auth` → `fastapi` + `pyjwt` ❌（所以本方案不用 `app.seed`，见 5.3）
- `app.add_test_data` → 只有 SQLAlchemy + models ✅（可安全复用，29 条真实原料演示数据）
- `services/recognition.py:115` 的 `scd_mk` 导入是**函数内 try/except 惰性导入**，设计正确，失败降级为「识别失败」状态

### 3.4 密钥需求

| 组件 | 需要的密钥 | MCP 方案是否需要 |
|---|---|---|
| 三个查询工具 | **无** | — |
| 自然语言路由器 `inventory_qa_router` | `DASHSCOPE_API_KEY` | ❌ 不需要（MCP 客户端自己是 LLM，路由由它做） |
| 企微适配器 | 5 个 `WECOM_*` | ❌ 不需要（不跑 Web/回调） |
| AI 图片识别 | `DASHSCOPE_API_KEY` | ❌ 不需要 |

**本方案对用户的密钥要求：零。** 这是 MCP-only 形态最大的附带收益。

---

## 4. 推荐方案与理由

### 4.1 三个选项

| | A. 只跑 MCP（**推荐**） | B. 独立 compose 跑完整 Web + MCP | C. 并入子牧现有栈 |
|---|---|---|---|
| 镜像内容 | python-slim + SQLAlchemy/Pydantic/mcp | 再加 fastapi/uvicorn/langchain/langgraph/openai | 同 B |
| 镜像体积（未压缩 / 传输） | **≈200 MB / ≈80 MB** | ≈450 MB / ≈180 MB | 同 B |
| 常驻内存 | **≈0**（`sleep infinity`）；会话期峰值 **80 MB**（实测） | 250–400 MB 常驻 | 同 B |
| 占用主机端口 | **0 个** | 至少 1 个（8110） | 需改子牧 nginx |
| 需要密钥 | **0 个** | `DASHSCOPE_API_KEY`（否则 `import app.main` 前先得补依赖）+ 可选 5 个 `WECOM_*` | 同 B |
| 立刻会炸的坑 | 无 | `langchain-openai` 缺声明 → 容器起不来，必须在 Dockerfile 里额外补装 | 同 B + 动生产栈 |
| 对生产栈的风险 | 无（独立项目，独立卷） | 低（抢内存） | **高**（改 compose/nginx 要重建镜像并重启生产容器） |
| 能不能录入新数据 | ❌ 只能靠脚本灌 | ✅ 有 Web UI | ✅ |

### 4.2 推荐 A，理由

1. **内存是硬墙。** zimupc 的 Docker Desktop 只有 **1.915 GiB** 预算（`docs/research/lan-access-root-cause-2026-08-27.md:73`），子牧已占 ~950 MB。B 方案常驻 250–400 MB 会把余量压到 ~600 MB 以下，而这台机器**历史上被压挂过**（`memory/zimu-demo-ops.md`：「Docker VM 仅 10 CPU / 8.2 GB … 会把后端饿死：API 45s 超时、健康检查失败、容器反复重启」）。A 方案常驻 ≈0。
2. **需求就是「跑起来 + MCP 可用」。** Web UI 不在目标里。
3. **A 绕开了仓库唯一的致命打包缺陷。** B/C 都必须在 Dockerfile 里手工补 `langchain-openai`（还得连带补 `httpx`，它被 `wecom_client.py:35` 惰性 import 但只声明在 `[dev]` extra 里）——等于替别人修 bug，而且要背 55 MB 的 LLM 栈。A 的 import 链根本不经过那两个模块。
4. **零密钥 = 零凭证流转。** 不需要用户把任何 key 交给任何 agent。
5. **零端口 = 零冲突、零暴露面。** 公网入口本来就不可用（运营商疑似封 80，DMZ 已关，UPnP 探测失败），`docker exec` stdio 天然不需要公网。
6. **有现成先例**：kehuzx 就是「同一台 zimupc 上的第二个独立 compose 项目、零发布端口」，且它的 MCP 也不发布端口（`kehuzx/deploy/README.md:139`：「Do not publish port 9100.」）。A 方案与这个既定模式一致。
7. **C 直接否掉**：子牧生产发布模式下 nginx 配置是**烤进镜像的**（`release.runtime.override.yml` 里 `volumes: !override []`），加一条 location 要重建镜像 + 跨架构传输 + 重启生产 nginx。为一个只读查询 MCP 付这个代价不合理。

### 4.3 数据库选型：SQLite，不起 Postgres

- 业务规模按设计文档是「生产单 1–2 张/天，物料约 300 条」，SQLite 绰绰有余。
- 起一个 postgres:16-alpine 至少多背 30–50 MB 常驻 + 一个卷 + 一次初始化，在 1.9 GiB 预算下不值得。
- 子牧的 Postgres **不能复用**：不同 schema、不同业务、跨 compose 项目要联网络，且往生产库里塞第三方表是污染。**明确不碰子牧的 Postgres 和 Redis。**
- 数据落在 named volume `yuanliaokc-data` 的 `/data/scd_wms.db`，备份 = `docker cp` 出来一个文件。

### 4.4 端口规划

**本方案不需要任何主机端口。** compose 文件里刻意不写 `ports:`。

供参考，zimupc 上的实际占用（已核实）：

| 端口 | 占用者 |
|---|---|
| **80** | `zimu-fulfillment-nginx-1`（`0.0.0.0:80->80/tcp`，全栈唯一对外端口） |
| 8080 / 5432 / 6379 / 3000 / 8765 | 子牧容器**内部**端口，未发布到主机 |
| 8088 | 子牧 compose 的 `APP_PORT` **默认值**（Mac demo 用），zimupc 上未使用 |
| 9100 / 8000 | kehuzx 容器内部端口，未发布 |

如果将来非要开（阶段二，见 7.x），用 **8110**（backend）/ **8111**（frontend），都空闲。

### 4.5 内存占用估算（实测数据）

| 项 | 数值 | 来源 |
|---|---|---|
| 常驻容器（`sleep infinity`） | **< 2 MB** RSS | Debian `sleep` 进程 |
| 单次 MCP 会话峰值 | **80 MB**（`maximum resident set size = 80003072`） | 本机 `/usr/bin/time -l` 实测 |
| SQLite 数据文件 | 274 KB（29 物料 / 23 批次） | 本机实测 |
| compose `mem_limit` 上限 | 256 MB（保险丝，正常用不到） | 本方案设定 |
| **稳态新增占用** | **< 5 MB** | |
| **峰值新增占用** | **< 100 MB** | |

对比 B 方案：uvicorn + fastapi + langchain 常驻 **250–400 MB**。

---

## 5. Codex 执行清单

> **前置**：本清单在 Mac 上执行（arm64，Docker Desktop 28.5.1 + buildx v0.29.1 已确认可用），只在最后几步通过 `ssh zimupc` 操作远端。
> **纪律**：不修改 `rgfan123/yuanliaokc` 仓库（只做只读 clone）；不修改子牧的 `docker-compose.yml` / `docker/nginx/default.conf`；不重启任何子牧容器。
> **命名约定**（沿用子牧 `real-<short-sha>` + kehuzx 目录布局）：镜像 `yuanliaokc-mcp:real-2e05d24`，compose 项目 `yuanliaokc`，容器 `yuanliaokc-mcp`，卷 `yuanliaokc-data`。

### 步骤 0 — 前置检查（只读，不改任何东西）

```bash
# 0a. 本机工具链
docker version --format '{{.Client.Version}} / server {{.Server.Version}}' && docker buildx version && uname -m

# 0b. 远端可达 + 当前内存余量（这是 go/no-go 判据）
ssh -o BatchMode=yes zimupc "docker info" 2>/dev/null | grep -iE "Total Memory|Server Version"
ssh -o BatchMode=yes zimupc "docker stats --no-stream" 2>/dev/null

# 0c. 确认命名不撞
ssh -o BatchMode=yes zimupc "docker ps -a --format {{.Names}}" 2>/dev/null | sort
ssh -o BatchMode=yes zimupc "docker volume ls" 2>/dev/null
```

**验收标准**
- `uname -m` = `arm64`，buildx 有版本号；
- `Total Memory` ≈ `1.9GiB`；`docker stats` 里子牧各容器合计 **< 1.3 GiB**（否则先停手，向 Jerry 报「内存余量不足，需要先调 `.wslconfig`」，**不要硬上**）；
- `docker ps -a` 输出里**没有**任何以 `yuanliaokc` 开头的容器；`docker volume ls` 里**没有** `yuanliaokc-data`。

---

### 步骤 1 — 建部署目录并 clone 目标仓库

`/Users/jerry/zimu-work/` 下只有 `inbox/` 和 `main/`，**不是 git 仓库**，所以新建 `deploy/` 不会污染任何 git 历史。

```bash
mkdir -p /Users/jerry/zimu-work/deploy/yuanliaokc
cd /Users/jerry/zimu-work/deploy/yuanliaokc
git clone https://github.com/rgfan123/yuanliaokc.git repo
cd repo && git rev-parse --short HEAD && git status --short
```

**验收标准**：`git rev-parse --short HEAD` 输出 `2e05d24`（若上游已前进，记下新 sha 并在后续所有命令里替换）；`git status --short` 为空。

---

### 步骤 2 — 写四个部署文件

全部落在 `/Users/jerry/zimu-work/deploy/yuanliaokc/`，**不进 repo 子目录**。

#### 2a. `requirements-mcp.txt`

```
# 已在 macOS/py3.11 实测通过的组合（2026-08-27）。精确固定版本，保证可复现。
mcp==1.12.4
SQLAlchemy==2.0.52
pydantic==2.13.4
pydantic-settings==2.15.0
```

#### 2b. `mcp_server.py`

> ⚠️ **本文件刻意不写 `from __future__ import annotations`。** 已实测：mcp 1.12.4 的 `Tool.from_function` 对参数注解直接调 `issubclass()`，PEP 563 把注解变成字符串后会抛 `TypeError: issubclass() arg 1 must be a class`，三个工具一个都注册不上。**别加回去。**

```python
"""原料库存 MCP server（stdio 传输）——只读三工具，直连 SQLite。

设计约束：
  1. 不导入 FastAPI / langchain / langgraph。三个查询工具只依赖
     app.services.inventory_qa（-> app.models / app.schemas / app.database），
     依赖面是 SQLAlchemy + Pydantic，镜像因此可以很小、内存占用很低。
  2. stdout 被 MCP JSON-RPC 独占。任何日志、warning、print 一律走 stderr，
     否则客户端会解析失败（子牧那边踩过的坑）。
  3. 本文件**刻意不写** `from __future__ import annotations`，原因见文件头说明。
"""

# --- stdout 保护：必须排在所有其他 import 之前 ------------------------------
import os
import sys

_REAL_STDOUT_FD = os.dup(1)  # 保存真正的 stdout（留给 MCP 传输层）
os.dup2(2, 1)                # fd 1 改指向 stderr：C 库/第三方直写 fd1 也污染不了协议
sys.stdout = sys.stderr      # Python 层 print() 同样落到 stderr
# ---------------------------------------------------------------------------

import logging  # noqa: E402

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    stream=sys.stderr,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)

from app.database import SessionLocal  # noqa: E402
from app.services.inventory_qa import (  # noqa: E402
    search_inventory_by_name,
    search_inventory_by_usage,
    total_inventory,
)

try:  # mcp 1.x
    from mcp.server.fastmcp import FastMCP
except ImportError:  # mcp 2.x 把 FastMCP 改名为 MCPServer
    from mcp.server.mcpserver import MCPServer as FastMCP  # type: ignore[no-redef]

mcp = FastMCP("yuanliaokc-inventory")


@mcp.tool(name="total_inventory")
def tool_total_inventory() -> dict:
    """总库存汇总：返回全部在库原料的当前库存(current_kg)与可用库存(available_kg)，
    以及跨原料的合计。无参数。已排除成品与零库存原料。"""
    db = SessionLocal()
    try:
        return total_inventory(db).model_dump()
    finally:
        db.close()


@mcp.tool(name="search_inventory_by_usage")
def tool_search_inventory_by_usage(usage: str) -> dict:
    """按用途标签模糊搜索在库原料库存，例如 usage="牛排" / "肥牛"。
    若没有任何用途标签命中，会自动回退成按名称/编码模糊搜索。"""
    db = SessionLocal()
    try:
        return search_inventory_by_usage(db, usage).model_dump()
    finally:
        db.close()


@mcp.tool(name="search_inventory_by_name")
def tool_search_inventory_by_name(keyword: str) -> dict:
    """按原料名称或物料编码模糊搜索在库库存，例如 keyword="牛腱子" / "M001"。"""
    db = SessionLocal()
    try:
        return search_inventory_by_name(db, keyword).model_dump()
    finally:
        db.close()


def main() -> None:
    # 把真正的 stdout 交还给 MCP 传输层；此行之后本文件不得再出现任何 print()
    sys.stdout = os.fdopen(_REAL_STDOUT_FD, "w", encoding="utf-8", buffering=1)
    mcp.run()  # 默认 stdio 传输


if __name__ == "__main__":
    main()
```

#### 2c. `seed_demo.py`

刻意不用 `app.seed`（它 → `app.auth` → fastapi/pyjwt，只为建 4 个默认弱口令账号，MCP 只读用不到）。

```python
"""建表 + 灌演示数据（MCP-only 部署用）。

用法（容器内）：python /srv/seed_demo.py
幂等：app.add_test_data 会先清空业务数据再重建，可重复执行。
"""
from __future__ import annotations

import sys

from sqlalchemy import select

from app.database import Base, SessionLocal, engine
from app.models import Warehouse, WarehouseType

WAREHOUSES = [
    ("WH-RAW", "原料仓库", WarehouseType.raw),
    ("WH-LEFT", "边角料仓库", WarehouseType.leftover),
    ("WH-FIN", "成品仓库", WarehouseType.finished),
]

# 给演示数据补用途标签，否则 search_inventory_by_usage 永远走不到标签分支。
DEMO_TAGS = {
    "牛外裙": ["下酒菜", "牛横膈膜"],
    "羔羊肋排": ["法切", "小羔羊肋排"],
    "羊肋排": ["法切", "小羔羊肋排"],
    "牛胸部肋条": ["牛筋串", "下酒菜"],
    "筋头巴脑": ["炖煮"],
    "肥牛砖": ["肥牛片", "肥牛卷", "火锅"],
    "羊肉卷": ["肥羊卷", "火锅"],
    "前腱": ["卤制", "炖煮"],
    "后腱": ["卤制", "炖煮"],
    "脊骨": ["汤锅"],
    "脂肪": ["炼油"],
    "西冷": ["牛排"],
    "眼肉": ["牛排"],
    "板腱": ["牛排"],
    "腰脊": ["牛排"],
}


def _ensure_warehouses() -> None:
    db = SessionLocal()
    try:
        for code, name, wtype in WAREHOUSES:
            if not db.scalar(select(Warehouse).where(Warehouse.code == code)):
                db.add(Warehouse(code=code, name=name, type=wtype))
        db.commit()
    finally:
        db.close()


def _apply_demo_tags() -> None:
    from app.models import Material, MaterialTag

    db = SessionLocal()
    try:
        added = 0
        for material in db.scalars(select(Material)).all():
            wanted: set[str] = set()
            for needle, tags in DEMO_TAGS.items():
                if needle in material.name:
                    wanted.update(tags)
            for tag in sorted(wanted - set(material.tag_list)):
                db.add(MaterialTag(material_id=material.id, tag=tag))
                added += 1
        db.commit()
        print(f"用途标签已补：{added} 条", file=sys.stderr)
    finally:
        db.close()


def main() -> None:
    Base.metadata.create_all(bind=engine)
    _ensure_warehouses()

    from app import add_test_data

    add_test_data.main()  # 29 条原料 + 期初批次
    _apply_demo_tags()
    print("演示数据初始化完成", file=sys.stderr)


if __name__ == "__main__":
    main()
```

#### 2d. `Dockerfile`

```dockerfile
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Asia/Shanghai \
    DATABASE_URL=sqlite:////data/scd_wms.db \
    UPLOAD_DIR=/data/uploads

WORKDIR /srv

COPY requirements-mcp.txt /srv/requirements-mcp.txt
RUN pip install --no-cache-dir -r /srv/requirements-mcp.txt

# 只拷后端应用包。刻意不装 fastapi / langchain-openai / langgraph：
# MCP 三工具的导入链是 app.services.inventory_qa -> app.models/app.schemas/app.database，
# 只需要 SQLAlchemy + Pydantic。app/routers/* 里的 FastAPI 代码永远不会被 import。
COPY repo/backend/app /srv/app
COPY mcp_server.py /srv/mcp_server.py
COPY seed_demo.py /srv/seed_demo.py

RUN mkdir -p /data/uploads

# 常驻容器本身什么都不做，每次 MCP 会话由 `docker exec -i` 拉起一个短命进程。
CMD ["sleep", "infinity"]
```

#### 2e. `.dockerignore`

```
repo/.git
repo/.e2e
repo/test_tp
repo/frontend
repo/src
repo/docs
repo/tests
repo/.claude
repo/.agents
repo/.scratch
repo/*.docx
**/__pycache__
**/*.pyc
```

#### 2f. `docker-compose.yml`

```yaml
name: yuanliaokc

services:
  mcp:
    image: yuanliaokc-mcp:real-2e05d24
    container_name: yuanliaokc-mcp
    restart: unless-stopped
    command: ["sleep", "infinity"]
    environment:
      DATABASE_URL: sqlite:////data/scd_wms.db
      UPLOAD_DIR: /data/uploads
      TZ: Asia/Shanghai
      PYTHONUNBUFFERED: "1"
      PYTHONIOENCODING: utf-8
    volumes:
      - yuanliaokc-data:/data
    # 常驻进程只是 sleep；上限 256m 是保险丝，防止 MCP 会话跑飞压垮 16G 主机
    mem_limit: 256m
    healthcheck:
      test: ["CMD", "test", "-f", "/data/scd_wms.db"]
      interval: 60s
      timeout: 3s
      retries: 3
      start_period: 10s
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    # 刻意不写 ports：MCP 走 `docker exec -i` 的 stdio，不需要任何监听端口。

volumes:
  yuanliaokc-data:
    name: yuanliaokc-data
```

**步骤 2 验收标准**：`ls /Users/jerry/zimu-work/deploy/yuanliaokc/` 显示 `Dockerfile  docker-compose.yml  mcp_server.py  repo/  requirements-mcp.txt  seed_demo.py`，以及 `.dockerignore`。

---

### 步骤 3 — 交叉构建 amd64 镜像（在 Mac 上）

```bash
cd /Users/jerry/zimu-work/deploy/yuanliaokc
docker buildx build --platform linux/amd64 \
  -t yuanliaokc-mcp:real-2e05d24 \
  -f Dockerfile --load .
docker image inspect yuanliaokc-mcp:real-2e05d24 --format '{{.Architecture}} {{.Os}} {{.Size}}'
```

**验收标准**：`docker image inspect` 输出以 `amd64 linux` 开头；Size 在 **180–260 MB** 之间（明显更大说明误装了 langchain，回去检查 `requirements-mcp.txt`）。
**注意**：arm64 Mac 上跑 amd64 的 `pip install` 走 QEMU 模拟，**这一步可能要 2–5 分钟**，属正常。所有依赖都有 manylinux 预编译 wheel，不会触发源码编译。

---

### 步骤 4 — 本地冒烟（在 Mac 上先验，别把问题带到 zimupc）

```bash
cd /Users/jerry/zimu-work/deploy/yuanliaokc
docker rm -f yuanliaokc-mcp-smoke 2>/dev/null
docker run -d --platform linux/amd64 --name yuanliaokc-mcp-smoke \
  yuanliaokc-mcp:real-2e05d24 sleep infinity
docker exec yuanliaokc-mcp-smoke python /srv/seed_demo.py
{
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  printf '%s\n' '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"total_inventory","arguments":{}}}'
  sleep 4
} | docker exec -i yuanliaokc-mcp-smoke python /srv/mcp_server.py 2>/dev/null
docker rm -f yuanliaokc-mcp-smoke
```

**验收标准**（全部满足才继续）
1. `seed_demo.py` 输出含 `测试数据已重建：物料 29 个，期初批次 23 个` 与 `用途标签已补：29 条`；
2. 冒烟输出**恰好 3 行**，每行都是合法 JSON，**没有任何非 JSON 文本**；
3. `id:1` 的 `result.serverInfo.name` = `yuanliaokc-inventory`；
4. `id:2` 的 `result.tools` 恰好 3 个，名字为 `total_inventory` / `search_inventory_by_usage` / `search_inventory_by_name`，后两者 `required` 分别是 `["usage"]` / `["keyword"]`；
5. `id:3` 的返回里 `totals.item_count` = **23**，`totals.current_kg` = **7982.07**。

> 这 5 条我已在本机（非容器）用同一份代码实测通过，数值就是上面这些。对不上说明构建或数据出了问题。

---

### 步骤 5 — 传输到 zimupc

沿用子牧既有的「先 scp 落地、再远端 docker load」流程（Windows 侧输出是 GBK，需 iconv）。

```bash
# 5a. 打包
docker save yuanliaokc-mcp:real-2e05d24 | gzip -1 > /tmp/yuanliaokc-mcp-2e05d24.tar.gz
ls -lh /tmp/yuanliaokc-mcp-2e05d24.tar.gz

# 5b. 远端建目录（cmd 的 mkdir 会自动建中间层）
ssh -o BatchMode=yes zimupc "mkdir C:\Deploy\yuanliaokc\incoming" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null
ssh -o BatchMode=yes zimupc "mkdir C:\Deploy\yuanliaokc\releases\mcp-2e05d24-20260827" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null

# 5c. 传镜像 + compose 文件
scp -o BatchMode=yes /tmp/yuanliaokc-mcp-2e05d24.tar.gz zimupc:'C:/Deploy/yuanliaokc/incoming/'
scp -o BatchMode=yes /Users/jerry/zimu-work/deploy/yuanliaokc/docker-compose.yml \
    zimupc:'C:/Deploy/yuanliaokc/releases/mcp-2e05d24-20260827/'

# 5d. 远端加载镜像
ssh -o BatchMode=yes zimupc "docker load -i C:\Deploy\yuanliaokc\incoming\yuanliaokc-mcp-2e05d24.tar.gz" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null | tail -4
ssh -o BatchMode=yes zimupc "docker image inspect yuanliaokc-mcp:real-2e05d24 --format {{.Architecture}}" 2>/dev/null
```

**验收标准**
- `/tmp/*.tar.gz` 大小 **60–110 MB**；
- `docker load` 末行为 `Loaded image: yuanliaokc-mcp:real-2e05d24`；
- 远端 `docker image inspect` 返回 `amd64`。

> ⚠️ 目录名用 `mcp-2e05d24-20260827`（沿用子牧 `<label>-<sha>-<日期>` 约定）。**不要建、不要指向 `current` junction** —— 子牧那边的 `current` 已经和实际运行目录分叉了（`lan-access-root-cause-2026-08-27.md:26`），这里不引入同款陷阱。

---

### 步骤 6 — 启动 + 灌数据

```bash
# 6a. 启动（compose 项目名由文件里的 name: yuanliaokc 决定）
ssh -o BatchMode=yes zimupc "docker compose -f C:\Deploy\yuanliaokc\releases\mcp-2e05d24-20260827\docker-compose.yml up -d" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null

# 6b. 灌演示数据
ssh -o BatchMode=yes zimupc "docker exec yuanliaokc-mcp python /srv/seed_demo.py" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null

# 6c. 健康检查
ssh -o BatchMode=yes zimupc "docker ps --filter name=yuanliaokc-mcp" 2>/dev/null
ssh -o BatchMode=yes zimupc "docker exec yuanliaokc-mcp ls -la /data" 2>/dev/null
ssh -o BatchMode=yes zimupc "docker stats --no-stream" 2>/dev/null
```

**验收标准**
- `docker ps` 显示 `yuanliaokc-mcp` 状态 `Up`，**PORTS 列为空**；
- `ls -la /data` 里有 `scd_wms.db`（约 270 KB）；
- `docker stats` 里 `yuanliaokc-mcp` 内存 **< 5 MiB**，且子牧各容器内存与步骤 0 相比无明显上涨。

---

### 步骤 7 — 端到端 MCP 冒烟（从 Mac 打到 zimupc）

```bash
{
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  printf '%s\n' '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_inventory_by_usage","arguments":{"usage":"牛排"}}}'
  sleep 5
} | ssh -T -o BatchMode=yes -o LogLevel=ERROR zimupc \
    "docker exec -i -e DATABASE_URL=sqlite:////data/scd_wms.db -e PYTHONUNBUFFERED=1 -e PYTHONIOENCODING=utf-8 yuanliaokc-mcp python /srv/mcp_server.py" \
    2>/dev/null
```

**验收标准**
1. 输出**恰好 3 行**，每行合法 JSON，**第一个字符必须是 `{`**（有任何 banner / 日志 / GBK 乱码就是 stdout 被污染，必须先修）；
2. `id:2` 返回 3 个工具，名字和步骤 4 一致；
3. `id:3` 的 `totals.item_count` = **3**，`totals.current_kg` = **246.48**（命中「牛排」标签的 3 种原料：`59813 新西兰羊腰脊` 198.88 + `百润万丰西冷 3+` 19.4 + `百润万丰黑安格斯极佳眼肉心` 28.2）。

---

### 步骤 8 — 注册到 MCP 客户端

把下面这个条目**合并进** `/Users/jerry/zimu-work/main/.mcp.json`（该文件现有一个 `zimu` 条目，保留它，只新增 `yuanliaokc-inventory`）：

```json
{
  "mcpServers": {
    "zimu": {
      "command": "ssh",
      "args": [
        "zimupc",
        "docker exec -i -e MCP_ENABLED=true -e MCP_AGENT_IDENTITY=jry -e WECOM_ENABLED=false -e SCHEDULED_TASKS_ENABLED=false -e MESSAGE_WORKER_ENABLED=false -e AGENT_WORKER_ENABLED=false -e WECOM_EXPORT_WORKER_ENABLED=false -e WECOM_REMINDER_ENABLED=false -e SOURCE_ORDER_INTAKE_WORKER_ENABLED=false -e SPRING_MAIN_WEB_APPLICATION_TYPE=none -e SPRING_MAIN_BANNER_MODE=off -e LOGGING_PATTERN_CONSOLE= -e LOGGING_FILE_NAME=/tmp/mcp.log zimu-fulfillment-backend-1 java -jar /app/app.jar"
      ]
    },
    "yuanliaokc-inventory": {
      "command": "ssh",
      "args": [
        "-T",
        "-o", "LogLevel=ERROR",
        "zimupc",
        "docker exec -i -e DATABASE_URL=sqlite:////data/scd_wms.db -e PYTHONUNBUFFERED=1 -e PYTHONIOENCODING=utf-8 -e LOG_LEVEL=WARNING yuanliaokc-mcp python /srv/mcp_server.py"
      ]
    }
  }
}
```

**必需环境变量说明**（三个都不是密钥）：

| 变量 | 值 | 为什么必须 |
|---|---|---|
| `DATABASE_URL` | `sqlite:////data/scd_wms.db` | 镜像 ENV 已内置，这里显式传是为了「有人不用 compose 直接 `docker run` 也不会指错库」。**注意四个斜杠**（`sqlite:///` + 绝对路径 `/data/...`） |
| `PYTHONUNBUFFERED` / `PYTHONIOENCODING` | `1` / `utf-8` | 中文物料名经 stdio 跨 Windows 传输，不设会有编码风险；不缓冲保证响应立即刷出 |
| `LOG_LEVEL` | `WARNING` | 压掉 `mcp.server.lowlevel` 的 INFO 日志（它们走 stderr，不会破协议，但会刷屏） |

**`-T` 和 `LogLevel=ERROR` 是必须的**：前者禁止 ssh 分配 TTY（TTY 会做行结束符转换，破坏 JSON-RPC 帧），后者压掉 ssh 自身的告警。

**验收标准**：重启 MCP 客户端后，工具列表里出现 `total_inventory` / `search_inventory_by_usage` / `search_inventory_by_name` 三个工具，且实际调用能返回库存数字。

---

### 步骤 9 — 回滚

按影响面从小到大：

```bash
# R1. 只摘 MCP（保留容器和数据）：从 .mcp.json 删掉 yuanliaokc-inventory 条目，重启客户端。

# R2. 停容器，保留数据卷（数据还在，随时能起回来）
ssh -o BatchMode=yes zimupc "docker compose -f C:\Deploy\yuanliaokc\releases\mcp-2e05d24-20260827\docker-compose.yml down" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null

# R3. 连数据一起删（不可逆）
ssh -o BatchMode=yes zimupc "docker compose -f C:\Deploy\yuanliaokc\releases\mcp-2e05d24-20260827\docker-compose.yml down -v" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null

# R4. 删镜像和落地文件，彻底清干净
ssh -o BatchMode=yes zimupc "docker rmi yuanliaokc-mcp:real-2e05d24" 2>&1 | iconv -f GBK -t UTF-8 2>/dev/null
ssh -o BatchMode=yes zimupc "del C:\Deploy\yuanliaokc\incoming\yuanliaokc-mcp-2e05d24.tar.gz"

# 备份数据（回滚前建议先做）
ssh -o BatchMode=yes zimupc "docker cp yuanliaokc-mcp:/data/scd_wms.db C:\Deploy\yuanliaokc\incoming\scd_wms-backup.db"
```

**回滚验收**：R2/R3 之后 `docker ps -a --filter name=yuanliaokc` 无输出；**子牧栈的 7 个容器状态与步骤 0 完全一致**（本方案全程没碰过它们，这一条应当天然成立）。

---

## 6. 需要用户（Jerry）提供的东西

**阶段一（本方案）：什么都不需要。零密钥。**

理由见 3.4：三个查询工具不调 LLM、不调企微，只读本地 SQLite。

唯一需要 Jerry 决策的两件事（**不是密钥**）：

1. **演示数据 vs 真实数据。** 本方案默认灌 `app.add_test_data.py` 里的 29 条真实原料（牛外裙肉、法切羔羊肋排、牛板腱……，总量约 8 吨）。如果 Jerry 想要同事那台机器上的**真实库存**，需要他从同事那里拿到 `backend/scd_wms.db` 文件（那是 gitignore 的，不在仓库里），然后：
   ```bash
   scp <拿到的>scd_wms.db zimupc:'C:/Deploy/yuanliaokc/incoming/'
   ssh zimupc "docker cp C:\Deploy\yuanliaokc\incoming\scd_wms.db yuanliaokc-mcp:/data/scd_wms.db"
   ```
   （跳过步骤 6b 的 seed。）
2. **是否要做阶段二（Web + 企微机器人）。** 如果要，那时才需要下面这些，**都写进 zimupc 上的 `C:\Deploy\yuanliaokc\shared\.env`，由 Jerry 本人 ssh 上去写或用 scp 传一个他本地编辑好的文件；不需要贴给任何 agent**：

   | 变量 | 从哪拿 |
   |---|---|
   | `DASHSCOPE_API_KEY` | 阿里云百炼控制台 |
   | `WECOM_CORP_ID` | 企微后台 → 我的企业 → 企业信息 → 企业ID |
   | `WECOM_AGENT_ID` | 企微后台 → 应用管理 → 自建应用详情页 |
   | `WECOM_SECRET` | 同上，点 Secret「查看」，扫码验证后拿明文 |
   | `WECOM_TOKEN` | 自定义 3–32 位字母数字 |
   | `WECOM_ENCODING_AES_KEY` | 企微回调配置页点「随机获取」，43 位 |

   注意这些要放**仓库根 `.env`** 的位置（容器里对应 `/srv/.env`，因为 `routers/wecom.py:26` 的 `_ROOT = parents[3]`），不是 `backend/.env`。

---

## 7. 与现有系统的冲突排查

### 7.1 企业微信：aibot vs 自建应用 —— 不冲突

| | 子牧 | yuanliaokc |
|---|---|---|
| 形态 | **智能机器人 aibot** | **自建应用（应用消息）** |
| 回调协议 | JSON，`msgtype: event` | XML，`msg_signature` 验签 + AES 解密 |
| 主动发消息 | `response_url`（1 小时内一次） | `message/send` API（需 `agentid` + `secret`） |
| 凭证 | aibot 独立一套 | `corp_id`/`agent_id`/`secret`/`token`/`aes_key` 独立一套 |
| 回调 URL | `/wecom/callbacks/`（走子牧 nginx） | `/api/wecom/callback`（阶段一不启用） |

依据：`docs/research/wecom-aibot-button-disable-2026-08-27.md:23-25` 的产品线对照表，明确把「智能机器人 aibot（**本系统**）」「自建应用（应用消息）」「群机器人」列为三条独立通路，协议、能力、凭证均不共享。

**结论：两者是企微里两个独立对象，各有各的 Secret、各有各的「企业可信IP」白名单、各有各的回调地址。互不抢占。** 唯一共享的是同一个企业的 `corp_id`（那是企业标识，不是资源）。

**阶段一根本不启用回调，所以这条冲突面为零。** 阶段二若要启用，回调必须走 nginx，见 7.4。

### 7.2 端口 / 容器名 / 卷名 / 项目名 —— 全部不撞

| 资源 | 已被占用 | 本方案 | 冲突 |
|---|---|---|---|
| 主机端口 | **80**（`zimu-fulfillment-nginx-1`，`0.0.0.0:80->80/tcp`，全栈唯一发布端口） | **不发布任何端口** | ❌ 无 |
| 容器名前缀 | `zimu-fulfillment-*`（7 个）、`kehuzx-*`（8 个） | `yuanliaokc-mcp` | ❌ 无 |
| 卷名 | `zimu-fulfillment_{postgres-data,redis-data,fulfillment-files,app-media-data}`、`kehuzx_postgres-data` | `yuanliaokc-data`（显式 `name:`，不带项目前缀） | ❌ 无 |
| compose 项目名 | `zimu-fulfillment`、`kehuzx` | `yuanliaokc` | ❌ 无 |
| 网络 | `zimu-fulfillment_default`、`kehuzx_default`、external `zimu-kehuzx-private` | `yuanliaokc_default`（隐式） | ❌ 无 |
| Windows 部署目录 | `C:\Deploy\zimu\`、`C:\Deploy\kehuzx\` | `C:\Deploy\yuanliaokc\` | ❌ 无 |

### 7.3 Postgres / Redis —— 不碰

本方案用 SQLite 单文件，**不连接**子牧的 `zimu-fulfillment-postgres-1`（内部 5432）或 `zimu-fulfillment-redis-1`（内部 6379），也不加入它们的网络。这两个容器的端口本来也没发布到主机，跨 compose 项目默认互相不可见。

**唯一真正共享的资源是 Docker Desktop 的内存预算**（见 7.5）。

### 7.4 阶段二才会出现的 nginx 冲突（现在不做，先记下来）

如果将来要让企微回调打到 yuanliaokc，**必须**改子牧的 nginx，这里写清改哪儿：

- 文件：`/Users/jerry/zimu-work/main/docker/nginx/default.conf`
- **只有一个 `server` 块**（第 39–204 行，`listen 80; server_name _;`），末尾第 197 行是 catch-all `location /` → `frontend_upstream`。**新 location 必须加在第 197 行之前。**
- 照抄 `/wecom/callbacks/`（第 72–81 行）的写法——它就是「外部系统没法出示 Basic Auth，靠 `msg_signature` 自证」的既定先例：

  ```nginx
  upstream yuanliaokc_upstream {          # 加在第 8–36 行的 upstream 区
      zone yuanliaokc_upstream 64k;
      server yuanliaokc-web:8100 resolve;
  }

  location /yuanliaokc/api/wecom/ {       # 加在 location / 之前
      auth_basic off;                      # 企微出示不了 Basic Auth
      proxy_pass http://yuanliaokc_upstream/api/wecom/;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      proxy_set_header Authorization "";
  }
  ```

- **三个必须先解决的障碍**：
  1. **跨项目网络**：nginx 在 `zimu-fulfillment_default` 里，解析不到另一个 compose 项目的服务名。得像 kehuzx 那样建一个 external 共享网络并让两边都接上。
  2. **配置是烤进镜像的**：生产发布用 `release.runtime.override.yml` + `offline.runtime.override.yml`，里面 `nginx.volumes: !override []` 把 bind mount 清空了。**改宿主机上的 `default.conf` 对运行中的容器毫无作用**（`lan-access-root-cause-2026-08-27.md:127`）。要么 `docker cp` 进容器可写层 + `nginx -t` + `nginx -s reload`（下次重建即失效），要么重建 nginx 镜像并跨架构传输。
  3. **公网入口不可用**：企微回调必须是**公网 HTTPS**。运营商疑似封 80、DMZ 已关、UPnP 探测全失败（`home-port-mapping-2026-08-27.md`），要开得 Jerry 手动登录 `192.168.1.1`（华为 B866）加一条高位端口映射。**这条路现在是断的，所以阶段二暂时不具备条件。**
- 另外注意：运行中的生产栈 `GATEWAY_BASIC_AUTH_ENABLED=false`（`lan-access-root-cause-2026-08-27.md:200`），也就是说除 `/healthz` 和 `/wecom/callbacks/` 外整个 edge 在局域网内是**无凭证可达**的。往这个 nginx 上挂新路由等于继承这个姿态。

### 7.5 内存：唯一的真实竞争

- Docker Desktop on zimupc：**12 vCPU / 1.915 GiB**（不是 16 GB）。
- 现状实测：backend 446 MB（22.8%）、metabase 464 MB（23.7%）、其余个位数 MB，合计 ≈ 950 MB。
- 本方案稳态新增 **< 5 MB**，会话峰值 **< 100 MB**，`mem_limit: 256m` 兜底。
- **步骤 0b 是 go/no-go 门闩**：如果 `docker stats` 显示子牧已经吃到 1.3 GiB 以上，停手报告，先让 Jerry 调 WSL2 的 `.wslconfig` 内存预算。

---

## 8. 风险清单

按「会不会在部署时立刻炸」排序。

### P0 —— 会立刻炸（本方案已规避，但换方案就会中招）

| # | 风险 | 证据 | 本方案怎么处理 |
|---|---|---|---|
| 1 | **`backend/pyproject.toml` 漏声明 `langchain-openai`**，只按声明依赖装的话 `import app.main` 直接 `ModuleNotFoundError`，容器一起就崩 | `services/classification.py:13` 与 `services/inventory_qa_router.py:15` 顶层 `from langchain_openai import ChatOpenAI`；`app/main.py:8` → `routers/stock.py:31` → `classification.py:13`。实跑复现 | **规避**：MCP 只 import `services/inventory_qa`，导入链不经过这两个模块。若走阶段二，Dockerfile 里必须显式加 `langchain-openai` |
| 2 | **`httpx` 只声明在 `[dev]` extra 里**，但 `services/wecom_client.py:35` 在推送时惰性 import 它 → 生产装了却推不出消息，且**只有真发消息时才炸**（惰性 import） | `backend/pyproject.toml` 的 `dev = [..., "httpx>=0.27,<1.0"]` | **规避**：不跑企微。阶段二须显式装 `httpx` |
| 3 | **PEP 563 会让 FastMCP 注册不了工具**：加了 `from __future__ import annotations` 后 mcp 1.12.4 抛 `TypeError: issubclass() arg 1 must be a class` | 我本机实测复现并已修 | 已在 `mcp_server.py` 头部写死警告，代码里不带这一行 |
| 4 | **DB 未初始化时工具报 `no such table: materials`**（SQLite 会静默创建空文件，不会报「文件不存在」） | 实测：返回 `Error executing tool total_inventory: (sqlite3.OperationalError) no such table: materials`，协议本身不崩 | 步骤 6b 必须先跑 `seed_demo.py`；步骤 6c 验收要求 `/data/scd_wms.db` 存在 |

### P1 —— 高（会让方案失效或伤到生产）

| # | 风险 | 缓解 |
|---|---|---|
| 5 | **Docker 内存预算只有 1.9 GiB**，不是 16 GB。子牧已占 ~950 MB，这台机器历史上被压挂过 | 步骤 0b 是硬门闩；`mem_limit: 256m` 兜底；选 MCP-only 而非 Web |
| 6 | **stdout 污染破坏 JSON-RPC**——子牧那边靠 `SPRING_MAIN_BANNER_MODE=off` + `LOGGING_PATTERN_CONSOLE=`（置空）+ `LOGGING_FILE_NAME=/tmp/mcp.log` 三件套解决。Python 侧没有现成先例（kehuzx 的 Python MCP 走的是 HTTP transport，从没遇到这个问题） | `mcp_server.py` 用了**双层防护**并已实测：① `os.dup2(2,1)` 把 fd 1 指向 stderr，连 C 库直写 fd1 都拦得住；② `sys.stdout = sys.stderr` 拦 Python 层 `print()`；③ `logging.basicConfig(stream=sys.stderr)`；④ 只在 `main()` 里把真 stdout 交还给传输层。实测 `print` / 裸 `os.write(1,...)` / `warnings.warn` 三种输出全部落在 stderr，stdout 只有协议帧 |
| 7 | **ssh 分配 TTY 会破坏协议帧**（行结束符转换） | `.mcp.json` 里带 `-T`；步骤 7 验收要求「第一个字符必须是 `{`」 |
| 8 | **Windows 侧 docker CLI 输出是 GBK**，直接读会乱码 | 所有远端命令都 `2>&1 \| iconv -f GBK -t UTF-8`。注意协议数据本身是容器内 UTF-8，ssh 不转码，不受影响 |

### P2 —— 中（功能受限，不影响能不能跑）

| # | 风险 | 说明 |
|---|---|---|
| 9 | **MCP-only 形态没法录入新数据** | 没有 Web UI，库存靠脚本或导入 `.db` 文件。这是「最小部署」的自觉取舍，Jerry 需要知情 |
| 10 | **演示数据原本没有用途标签**，`search_inventory_by_usage` 会永远走「无标签命中 → 回退按名称」分支，三个工具里有一个显不出真本事 | `seed_demo.py` 补了 29 条标签（牛排/肥牛片/卤制/火锅…），让该分支能被真正验证 |
| 11 | **`DEFAULT_FRONTEND_URL = "http://localhost:5173/inventory-search"`**（`inventory_qa_format.py:17`），票 05 亲口标注的唯一遗留项 | 只影响企微 markdown 截断时的「查看更多」链接；MCP 返回结构化 JSON，不走 formatter，**本方案不受影响** |
| 12 | **仓库没有 CI**，缺失依赖这类问题不会被自动发现；上游继续开发后可能引入新的顶层 LLM import 打破 3.3 的依赖面 | 每次跟进上游后，重跑步骤 4 的本地冒烟即可发现 |
| 13 | **上游可能已前进**（分析基于 `2e05d24`） | 步骤 1 要求核对 sha，不一致就把所有命令里的 tag 替换掉 |

### P3 —— 低（记录备查）

| # | 风险 | 说明 |
|---|---|---|
| 14 | `.e2e/node_modules`（13 MB playwright-core）被误提交进 git | 已在 `.dockerignore` 排除，不进镜像。可提醒同事清理 |
| 15 | 默认弱口令 `admin123`/`operator123`/`reviewer123`/`manager123`（`app/seed.py`）与 `jwt_secret = "change-me-in-production"`（`config.py:18`） | 本方案不跑 `app.seed`、不建任何账号、不启用 JWT，**这些账号根本不存在**。阶段二必须先改 |
| 16 | 本机 `~/.config/pip/pip.conf` 里的 `extra-index-url = https://pypi.ngc.nvidia.com` SSL 失败，会让每个包多耗 60–90 秒重试 | 只影响在 Mac 上直接跑 pip；Dockerfile 里的 pip 在容器内跑，不读这个配置，**不受影响** |
| 17 | 子牧 `C:\Deploy\zimu\current` junction 已与实际运行目录分叉 | 本方案不建也不用 `current`，直接用带日期的 release 目录 |

---

## 附：一句话交接

给 Codex 的最短路径是第 5 节步骤 0 → 9，每步都有可直接复制的命令和可判定的验收标准；**唯一需要人工判断的是步骤 0b 的内存门闩**（余量不足就停手报告，不要硬上）。全程不需要任何密钥，不改目标仓库，不动子牧任何容器。
