# 演示 Runbook — 2026-08-24 例会

> 演示地址：**http://127.0.0.1:8088**（网关 Basic Auth，凭据在 `.env` 的 `APP_ADMIN_USER/PASSWORD`）
> 本文是操作手册。汇报口径见 `docs/DEMO-BRIEF-20260824.md`。

---

## ⚠️ 演示前 10 分钟必做（三条，缺一条就可能翻车）

### 1. 清空机器负载（**最重要**）

Docker VM 只有 10 CPU / 8.2 GB。昨晚实测：两套栈 + 两个 Metabase 同时跑 → 后端被饿死、
API 45 秒超时、健康检查失败、容器反复重启 20 次。

```bash
# 停掉 3 天前遗留的 preview 栈（08-20 converge 测试，与本演示无关）
docker compose -f /Users/jerry/Documents/Codex/2026-08-20/zimu-converge-active-branches-01a01e70/docker-compose.yml stop
# 停掉 Metabase（BI 看板，演示不用；两个实例合计吃 333% CPU）
docker stop zimu-fulfillment-metabase-1
# 关掉其它 AI 工具的 dev server（Codex 的 vite 等），确认负载
uptime   # load average 应 < 10；> 20 就别开演示
```

### 2. 确认后端健康

```bash
cd /Users/jerry/Documents/子牧 && docker compose ps
# backend 必须是 "healthy"。若是 unhealthy，回到第 1 步降负载，等 2 分钟再看。
```

已修复的坑：`docker-compose.yml` 里加了 `SPRING_DATA_REDIS_TIMEOUT/CONNECT_TIMEOUT=3s`
（`application.yml` 硬编码的 250ms 在 Docker Desktop 网络下会触发重连风暴，
后端空转 248% CPU）。**不要回退这两行**。

### 3. 冒烟测试（30 秒）

```bash
cd /Users/jerry/Documents/子牧
CFG=$(mktemp); chmod 600 "$CFG"
python3 -c "
import sys
env={}
for l in open('.env'):
    l=l.strip()
    if '=' in l and not l.startswith('#'):
        k,v=l.split('=',1); env[k]=v.strip().strip('\"').strip(\"'\")
open(sys.argv[1],'w').write(f'user = \"{env[\"APP_ADMIN_USER\"]}:{env[\"APP_ADMIN_PASSWORD\"]}\"\n')
" "$CFG"
for ep in dashboard/summary "review-cases?status=OPEN&size=1" "orders?size=1"; do
  curl -s --config "$CFG" --max-time 20 -o /dev/null -w "$ep → %{http_code} %{time_total}s\n" \
    "http://127.0.0.1:8088/api/v1/$ep"
done
rm -f "$CFG"
# 三条都应 HTTP 200 且 < 2s。有 000/超时 → 回第 1 步。
```

---

## 🔴 演示中的安全红线（务必知情）

**当前 8088 的京东写门闩是打开的，且指向生产环境**：

| 开关 | 当前值 | 含义 |
|---|---|---|
| `JD_LOP_CLIENT_MODE` | `REAL` | 真实京东客户端 |
| `JD_LOP_ENV` / `server-url` | `prod` / `https://api.jdl.com` | **生产环境**（注意文件名写着 uat 但内容是 prod） |
| `JD_LOP_WRITE_MODE` | `ON` | **写门闩打开** |
| `JD_OUTBOUND_AUTHORIZED_OPERATORS` | `zimu-admin` | 该账号可建单 |
| `JD_TRACKING_BACKFILL_ENABLED` | `true` | 运单轮询开启（每 60s 打真实京东） |

**含义**：在发货台点「整批确认」，若该批次的履约方 `outboundMode=SDK`，
会**向京东生产环境真实建单**。演示时若不想真建单，两种做法：

- **A. 演示只读部分**（推荐）：拉取 → 复核 → 链路 → 对账，不点「整批确认」。
- **B. 临时关写门闩**：`.env` 改 `JD_LOP_WRITE_MODE=OFF` → `docker compose up -d --no-deps backend`
  （约 90 秒重启）。演示后改回。

**平台拉取没有频控**（#115 按用户口径修订）：平台拉单本来就没有每日次数与最小间隔限制。
原 `APP_PLATFORM_PULL_MIN_INTERVAL=PT12H` 已整个移除，改为 PostgreSQL advisory lock **单飞**——
同一渠道同一时刻只允许一个请求触达外部，第二个立即返回 `PLATFORM_PULL_IN_PROGRESS`（不等待、不触网）；
失败不占用次数，可立即重试。**演示前可以放心试拉。**

---

## ⚠️ 已知能力边界（演示时别踩）

**部署的后端落后 master 73 个提交**（当前分支 `codex/root-wip-live-20260822`）。
Codex 的企微主动推送成果（#81 发送+ack / #82 分片上传 / #83 群路由 / #84 导出发送 / #89 人员映射，
共 11 个新文件含 `WecomOutboundGateway`）**都在 master，不在当前 8088 后端里**。

- ✅ **不影响本次演示**：四个工作台用的 API 全部是既有接口，已逐个实测 200。
- ✅ **企微主动推送已在本次合并中具备**：#81 发送+ack、#82 分片上传、#84 导出发送、
  #87/#88 交互卡片、#90 业务通知全部合入（需部署合并后的后端镜像才生效）。
- 若要合入 master：涉及 V46–V49 四个数据库迁移，**演示前一晚不要做**，风险不对等。

## 演示动线（建议 8–10 分钟）

### 第 1 幕：岗位切换（30 秒，展示"一人一屏"）
1. 打开 http://127.0.0.1:8088 → 侧栏顶部点岗位选择器
2. 选「履约运营」→ 自动落到今日发货工作台；侧栏顺序按该岗位动线重排
3. 一句话：**岗位只换视图不换权限，全部菜单对所有人可见**（Phase 1 无登录，说清楚不留误解）

### 第 2 幕：发货台一屏看全（2 分钟，主戏）
1. 七指标：待复核/待发货/发货中/已回填 都是真数，点卡片跳对应区
2. 八段链路：卡在哪一段一眼看到；未接入的段位诚实写「暂无汇总」而非编数
3. 复核区：按原因分组，**0 项的类别也保留可见**（"0 不等于不存在"）
4. 点「开始今日订单同步」→ 逐渠道 OK/FAILED/SKIPPED 如实显示；
   **聚福宝会显示「仅报告未入库」**——它 JSON 直连缺收货人字段，只报数不建批次，
   这是既有行为不是故障，界面把它说出来了

### 第 3 幕：复核收件箱（1 分钟）
1. 侧栏点「复核收件箱」→ 自动按当前岗位团队预筛，顶部显示「已按岗位预筛：履约运营 · 看全部」
2. 点「看全部」→ 立刻切回全量；URL 同步变化（分享链接如实反映所见）

### 第 4 幕：采购台 + 比价 Agent（2 分钟）
⚠️ **前置**：库里需要至少 1 张采购工单，否则按钮禁用（见下方"演示数据准备"）
1. 点「为缺货工单比价」→ Agent 只读运行
2. 建议卡：可比候选、**被剔除候选连理由一起显示**（价格离群/缺失/映射失效）、
   推荐高亮、模型与提示词版本留痕
3. 一句话：**Agent 只给依据不做决定**——它不创建工单、不改价格；
   工单由履约缺货产生，成交价电话确认后由人手填

### 第 5 幕：对账台（1.5 分钟）
1. 分平台数量对账表：平台下单/来源份数/已发/实际件数（双口径）
2. **金额列是 `¥ ——`**，旁边写着「这不是加载失败」——数据库确实没有金额字段，
   宁可显示空也不编数。这是**诚实工程**的最好例子，值得专门讲一句
3. 下方单笔点查：输入出库单号，内外事实并排、七态逐字段判定

### 第 6 幕：收尾（30 秒）
一句话总结：**这套界面的原则是"能做的做到底，做不到的说清楚"**——
所有数字来自真实接口，接不上的位置保留结构并写明原因，绝不编数。

---

## 演示数据准备（采购台需要）

当前库里 `procurement_tickets` 为 0 张，采购台比价按钮会禁用。三个选项：

- **A. 跳过第 4 幕**，用「采购比价工具」页（`/procurement/price-compare`）手工输入 SKU 演示 Agent
- **B. 演示前造一张真实工单**：走履约缺货流程（需要有订单+库存不足场景）
- **C. 用 DEMO 域造数**：`DEMO_SEED_ENABLED=true` 重启后端（数据带 `data_scope=DEMO`，
  与业务数据严格隔离，不进分析/看板）

推荐 A（零风险）或 C（数据丰满且隔离）。

---

## 出事了怎么办

| 症状 | 处置 |
|---|---|
| 页面白屏 / 接口 502 | `docker compose ps` 看 backend；unhealthy → 降负载等 2 分钟 |
| 拉取返回 `PLATFORM_PULL_IN_PROGRESS` | 同渠道已有请求在途（单飞保护），等它跑完即可，不是错误 |
| 前端要回滚旧版 | `docker tag zimu-fulfillment-frontend:rollback-20260823 zimu-fulfillment-frontend:real-current && docker compose up -d --no-deps frontend` |
| 某区块显示加载失败 | 区块级独立降级，其余照常可演示——直接说「这块接口没起来，其它不受影响」，这本身也是设计 |
