# 局域网访问生产栈「个别请求网络连接失败」根因排查

- 日期:2026-08-27
- 问题:Jerry 从 Mac 浏览器(同局域网)访问 `http://192.168.1.22` 生产栈时,反复出现个别请求 fetch 层报 `TypeError`(「网络连接失败」),已到「始终无法访问」的严重程度;此前两轮诊断(含前端幂等 GET 网络级重试)均未根治。
- 取证方式:Mac 端 Python 并发压测(stdlib `http.client` + `concurrent.futures`,精确捕获异常类型)+ SSH 直查 Windows 主机(`zimupc`,192.168.1.22)防火墙/端口/进程 + 容器内 `nginx -T` 核对生效配置。所有数据均为本次实测,推断项单独标注。

---

## 0. 结论先行

1. **根因钉死:nginx 默认 `keepalive_timeout 65s`(nginx:1.27-alpine 基础镜像未改的 http 层默认值)与浏览器的 keep-alive 连接池复用发生竞态。** 空闲 ≥66 秒后复用同一条持久连接,**100% 触发 TCP RST**——且这个 RST 发生在内核层、早于 nginx 用户态进程读到任何请求字节,因此**不产生任何 access log / error log 记录**,与线上症状(「请求未达 nginx,access log 无记录」)逐字吻合。工作台一屏加载后停留阅读超过 1 分钟再操作,恰好会踩中这个窗口;并发越高,同一时刻踩中失效连接的请求数越多,越容易冲穿前端已有的单次重试余量。
2. **已用可控实验拿到铁证**:对同一条未关闭的持久连接,先发一次请求确认存活,空闲 D 秒后复用发第二次请求,10 组并行重复——`D≤63s` 全部成功,`D≥66s` 全部 `ConnectionResetError`(10/10),阈值精确对齐 nginx 的 65s。**纯并发**(不复用连接,50 并发 × 多轮 × 1410 次请求,含真实 `/api/v1/orders` 端点)在测试窗口内 0 失败——说明并发本身不是触发条件,复用超时连接才是。
3. **已排除**:Windows 防火墙(Private/Public 两个 profile 均关闭,当前网卡正是 Private,规则形同虚设)、端口冲突(0.0.0.0:80 仅 `com.docker.backend.exe` 一家在监听)、`netsh portproxy`(空)、容器 OOM/重启(nginx、backend 均 0 次)、内存瓶颈(压测时刻用量正常)、Tailscale(Mac 路由表对 192.168.1.0/24 无专门路由,直接走物理网卡,未介入此流量)。**Docker Desktop 端口转发本身不是元凶**——实测证明这个复位是 nginx 自身超时策略触发的标准 TCP 行为,即使换成宿主机直通网络也会同样发生;因此未采用「compose 端口改 host 模式」这个更高侵入度方案。
4. **已修复且已验证**:把该 server 块的 `keepalive_timeout` 从继承的 65s 显式覆盖为 300s,`docker exec nginx -s reload` 热加载生效,容器全程未重启、镜像未重建。复测:原先必现重置的 66s~120s 空闲区间全部转为成功;300s 之外(310s)仍会重置,证明改的正是这个机制本身,不是误打误撞掩盖了别的问题。压测(并发/串行 1410 次)修复前后均 0 失败,无回归。
5. **部署链路上发现一个必须让 Jerry 知道的坑**:当前跑着的发布目录用了 `offline.runtime.override.yml`,把 nginx 服务的 `volumes` 显式清空(`!override []`)——也就是说**这次发布模式下 nginx 配置是打包进镜像的,不是 bind mount**,直接改宿主机上 `docker/nginx/default.conf` 文件对这个正在跑的容器**完全不生效**(已实测验证)。真正生效的改法是直接改**容器可写层**里的 `/etc/nginx/conf.d/default.conf` + reload——这次是这么修的,但**这个改动只活在这个容器的生命周期里**,一旦容器被重建(下次发布、`docker compose up` 重新创建、宿主机重启触发的容器重建等)就会连同其他容器内改动一起丢失,退回镜像里烘焙的 65s。已同步把源头文件 `docker/nginx/default.conf` 改好并入库,**但下一次出镜像/发布时必须确认这一行改动被带进新镜像**,否则这个 bug 会在下次发布后原样复现。

---

## 1. 环境拓扑(复核,与任务背景一致)

- 生产栈:Windows 主机 `zimupc`(192.168.1.22),`whoami` = `pc-20250414ixlg\jerry`,Windows 10.0.19045.3754。
- Docker Desktop 4.87.0,engine 29.7.2,`desktop-linux` context(WSL2/Linux 后端,非旧版 Hyper-V vpnkit)。
- 唯一入口:`zimu-fulfillment-nginx-1` 容器,`0.0.0.0:80->80/tcp`,`docker ps` 确认镜像 `zimu-fulfillment-nginx:real-e50bb3e`。
- 实际发布目录(通过 `docker inspect` 的 compose 标签拿到,不是靠猜):
  `C:\Deploy\zimu\releases\agent-platform-e50bb3e-20260825-0759\`,由四个 compose 文件叠加:
  `docker-compose.yml` + `real-business.override.yml` + `release.runtime.override.yml` + `offline.runtime.override.yml`。
  **注意**:`C:\Deploy\zimu\current` 这个 junction 指向的是另一个更早的发布 `checkpoint-00efe89-20260824-1300`,并不是实际在跑 nginx/backend/frontend 的目录——两者已经分叉,"current" 不代表现实,后续任何人对着 "current" 发布都可能是在往回滚。这不是本次网络问题的成因,但值得记录。
- Mac 端到 192.168.1.22 走物理网卡(`en0`)默认路由,ping 3-4ms,单次 curl 一直 200——与任务背景描述一致。

---

## 2. 复现与量化(第 1 步)

### 2.1 纯并发压测——0 失败,不是触发条件

用 stdlib `http.client` + `concurrent.futures.ThreadPoolExecutor`,每个请求开全新连接(`Connection: close`,`threading.Barrier` 保证真正同时发出),分别对 `/healthz`(不经后端,纯 nginx 静态返回)和 `/api/v1/orders`(真实后端端点,当前网关 Basic Auth 处于关闭状态,详见 §5.1,无需凭证即可直接测)打压测:

| 场景 | 请求数 | 修复前失败率 | 修复后失败率 |
|---|---|---|---|
| 串行基线 `/healthz` × 60 | 60 | 0% | 0% |
| 并发 10 × 15 轮 `/healthz` | 150 | 0% | 0% |
| 并发 30 × 15 轮 `/healthz` | 450 | 0% | 0% |
| 并发 50 × 10 轮 `/healthz` | 500 | 0% | 0% |
| 并发 25 × 10 轮 `/api/v1/orders`(真实后端) | 250 | 0% | 0% |
| **合计** | **1410** | **0/1410 (0%)** | **0/1410 (0%)** |

结论:在几十秒的测试窗口内,单纯堆并发数(哪怕到 50、哪怕打真实后端端点)**复现不出**用户报告的失败。这排除了「Docker Desktop 端口转发扛不住并发新建连接」这个最初的怀疑方向——至少不是主因。

### 2.2 keep-alive 空闲复用测试——100% 可重复的铁证

既然纯并发不触发,转向浏览器的真实行为:HTTP/1.1 keep-alive 连接池复用。测试方法:开一条持久连接,发第 1 个请求确认连接存活,**完全空闲 D 秒(不发送任何数据)**,再用**同一条未关闭的 socket**发第 2 个请求。每个 D 值起 10 条并行连接重复 10 次。

**修复前**(nginx `keepalive_timeout` 继承 http 层默认值 65s):

| 空闲时长 D | 复用结果(10 次试验) |
|---|---|
| 5s / 20s / 40s / 55s / 63s | 10/10 成功 |
| **66s** | **10/10 `ConnectionResetError`** |
| 70s | 10/10 `ConnectionResetError` |
| 90s | 10/10 `ConnectionResetError` |
| 120s | 10/10 `ConnectionResetError` |

失败阈值精确落在 63s(成功)与 66s(100% 失败)之间,**与 nginx 的 `keepalive_timeout 65` 完全对齐**——不是巧合,是同一个机制。

### 2.3 为什么这会在浏览器里长成「网络连接失败,access log 却无记录」

TCP 机制层面:nginx 对空闲超过 `keepalive_timeout` 的连接会整体关闭(读关闭该 socket 对应的所有内核态资源)。如果客户端(浏览器的连接池)在服务端已经完全关闭之后,才在**同一条**它以为还活着的连接上发送新请求的数据,这些数据会打在一个已经不存在的 socket 上——内核直接回一个 **RST**,这个过程完全发生在 nginx 用户态进程读取/解析 HTTP 请求行**之前**。所以:

- nginx **不会**记录这次请求(access log 只在完整处理一次请求后才写一行;这次请求从未被 nginx 的应用层看到)。
- error log 也不会有记录(同理,连接在内核层就被拒绝,nginx 进程毫无感知)。
- 浏览器侧的 `fetch()` 拿到的是一个底层网络错误,表现为 `TypeError: Failed to fetch`,即前端代码里翻译出的「网络连接失败」。

这与「Docker Desktop 端口转发不稳定」无关——**同样的 65s 超时 + 同样的复用行为,换成 nginx 直接暴露给宿主机网络(不经任何转发层)也会一模一样地发生**,因为触发它的是 nginx 自己的 `keepalive_timeout`,不是转发层的私有行为。之所以会在「并发 20+ 请求的工作台一屏」时被放大,是因为浏览器对同一 origin 维护的是一小撮(通常个位数到十几条)持久连接;只要用户在两次操作之间停留超过 65 秒去阅读/思考,这一整撮连接会**同时**过期,下一次点击触发的一批并发请求里,可能有不止一条同时复用到失效连接、同时收到 RST——如果恰好超过前端已有的单次重试(`frontend/src/api/client.ts` 的 0ms/300ms/800ms 三次尝试,仅覆盖 GET)的余量,就会有请求最终以失败呈现给用户。这正是 `client.ts` 里那段已有注释描述的现象,只是此前没有钉死「为什么会被掐」这一步。

---

## 3. Windows 侧排查(第 2 步)——逐项排除

| 检查项 | 命令/方法 | 结果 | 结论 |
|---|---|---|---|
| 80 端口占用 | `netstat -ano \| findstr :80` | 唯一 LISTENING 者:PID 16336 = `com.docker.backend.exe`(`tasklist /FI "PID eq 16336"` 核实) | 无端口冲突,无 IIS/其它服务抢占 |
| 端口代理 | `netsh interface portproxy show all` | 空 | 无干扰性端口转发规则 |
| 防火墙规则/profile | `netsh advfirewall show allprofiles state` | 域配置文件启用,**专用(Private)、公用(Public)均关闭** | 当前网卡正是 Private(见下一行),防火墙对这台机器形同虚设,不可能是间歇性丢包/复位的原因 |
| 当前网络分类 | PowerShell `Get-NetConnectionProfile` | `以太网 2` → `NetworkCategory: Private`,`IPv4Connectivity: Internet` | 确认「防火墙关闭」这条 profile 就是实际生效的那条 |
| Docker Desktop 版本 | `docker version` / `docker info` | Desktop 4.87.0,engine 29.7.2,`desktop-linux` (WSL2/Linux 后端),12 vCPU / **1.915 GiB** 内存预算 | 版本较新;内存预算偏紧但压测时刻用量正常(见下一行) |
| 容器资源/健康 | `docker stats --no-stream`、`docker inspect --format RestartCount/OOMKilled` | backend 446MB/22.8%、metabase 464MB/23.7%,其余个位数 MB;nginx 与 backend `RestartCount=0`、`OOMKilled=false` | 无内存杀进程、无重启历史,不是资源枯竭导致的连接中断 |
| Tailscale 是否介入 | Mac `tailscale status` + `netstat -rn -f inet` | Tailscale 确实在两端都装了(`pc-20250414ixlg` 走 `direct 192.168.1.22:41641`),但 Mac 路由表对 `192.168.1.0/24` 没有专属路由,走的是 `en0` 物理网卡默认路由 | Tailscale 只是并行存在的另一条隧道,**未拦截**这次测试用的 LAN 直连流量 |
| 是否有本机其它客户端在用同一网关 | `docker logs zimu-fulfillment-nginx-1`(access log 符号链接到 stdout,不能直接 `tail` 文件,见 §5.2) | 看到 `192.168.65.1`(Docker NAT 网关地址)+ `Referer: http://localhost/...` + UA 里的 `Edg/106.0...`,即 Windows 主机本机 Edge 正在通过 `http://localhost` 访问同一套工作台 | 与之前发现的「msedge.exe 在 127.0.0.1:80 上留有 6 组 CLOSE_WAIT/FIN_WAIT_2」互相印证:本机 Edge 走的是同一个 nginx server 块,同样会被这个 65s 竞态影响,这次修复对它也同样生效 |

**结论**:Windows 侧没有防火墙、端口冲突、资源耗尽类问题;Docker Desktop 的端口转发进程(`com.docker.backend.exe`)是所有 LAN 流量的唯一必经之路,但没有证据显示它本身在丢包/复位——§2 的实验已经证明复位的触发条件是 nginx 自己的空闲超时,不是转发层的私有故障。

---

## 4. nginx / 后端排查(第 3 步)

- `docker exec zimu-fulfillment-nginx-1 nginx -T` 核对**生效**配置(不是仓库里的文件,是容器实际加载的):
  - `worker_connections 1024`(events 块,默认值)——对个位数并发用户绰绰有余,不是瓶颈。
  - `keepalive_timeout 65`(http 块,nginx:1.27-alpine 镜像默认值,**修复前从未被覆盖过**)——就是本次根因。
  - 未设置 `reset_timedout_connection`(默认 `off`)——说明 nginx **主动**关闭空闲连接时用的是优雅 FIN,不是粗暴 RST;本次抓到的 RST 是**客户端在连接已经死透之后再写数据**触发的内核级复位,不是 nginx 主动发的 RST,两者是不同的事件。
  - `/api/` 已经配了 `proxy_read_timeout/proxy_send_timeout 300s`(为三平台拉取的长耗时请求准备的),排除「后端慢导致网关先超时」这个方向——而且后端慢会体现为**超时**而不是任务描述的「立刻失败」,机制上也对不上。
- 后端/nginx 容器:见 §3 表格,`RestartCount=0`、`OOMKilled=false`,压测期间内存/CPU 均正常,不是后端不稳定导致的连接异常。
- `upstream backend_upstream` 未配置 `keepalive N`,nginx→backend 是每请求新建连接,不存在「nginx 到后端」方向的空闲复用竞态(这条链路上没有这个 bug 的镜像版本)。

---

## 5. 应用的修复

### 5.1 选择依据

任务给出的三个候选修法里:

- **(a) nginx keepalive 参数调优**——直接命中根因,侵入度最低,选用。
- **(b) Docker Desktop 端口转发 workaround(compose 端口改 host 模式)**——已排查证据(§2.3)表明触发条件是 nginx 自身超时,换端口模式不会消除这个竞态,只是徒增 compose 拓扑改动的风险,**不采用**,也未改动 `docker-compose.yml` 的端口配置。
- **(c) 防火墙 workaround**——防火墙本来就是关闭的(§3),没有需要 Jerry 手工执行的防火墙命令。

### 5.2 部署链路的意外发现:改宿主机文件不生效

按 (a) 最初的设想是「改宿主机 `docker/nginx/default.conf` + `docker exec nginx -s reload`」。执行后用 `nginx -T` 复核,发现 `keepalive_timeout` 仍是 65——改动没生效。查因:

```
release.runtime.override.yml / offline.runtime.override.yml 显示:
  nginx:
    image: zimu-fulfillment-nginx:real-e50bb3e
    volumes: !override []      # ← 这次发布模式把 volumes 整体清空
```

`docker/nginx/Dockerfile` 里其实早有一句注释说明了这个场景:「离线部署(目标机连不上 registry、用 offline override 钉镜像)会把 volumes 清空,配置若只靠 bind mount 会静默退回默认站点」——这次踩到的正是这个模式:**容器压根没有把 `docker/nginx/default.conf` bind mount 进去,`/etc/nginx/conf.d/default.conf` 是构建镜像时 `COPY` 进去的静态内容**,改宿主机对应路径的文件对已经在跑的容器没有任何作用。

### 5.3 实际生效的改法(容器可写层直改 + reload,零重建零重启)

1. 备份(改动前,`copy` 留痕,满足约束):
   - 宿主机路径:`C:\Deploy\zimu\releases\agent-platform-e50bb3e-20260825-0759\docker\nginx\default.conf.bak-before-lan-keepalive-fix-20260827`(与该目录已有的 `.bak-before-*` 命名习惯一致)。
   - **容器内**(真正生效的备份):`docker exec zimu-fulfillment-nginx-1 cp /etc/nginx/conf.d/default.conf /etc/nginx/conf.d/default.conf.bak-before-lan-keepalive-fix-20260827`。
2. 把改好的文件(先在本仓库改好、SHA-256 校验传输无损)通过 `docker cp` 直接写入容器可写层:`/etc/nginx/conf.d/default.conf`。
3. `docker exec zimu-fulfillment-nginx-1 nginx -t`(通过)→ `docker exec zimu-fulfillment-nginx-1 nginx -s reload`(热加载,`docker ps` 确认容器全程 `Up 6 hours (healthy)`,未重启、未重建镜像)。
4. `nginx -T` 复核确认 `keepalive_timeout 300s;` 已出现在 server 块里并生效。

具体改动(已同步写回本仓库 `docker/nginx/default.conf`,供下次出镜像带上):

```nginx
server {
    listen 80;
    server_name _;
    # LAN gateway sits behind the Windows host's own port-forwarder (Docker Desktop
    # publishing 0.0.0.0:80), which the browser cannot see through. nginx's stock
    # 65s keepalive_timeout means any pooled client connection idle >=65s (e.g. an
    # operator reading the screen between workbench bursts) gets a hard TCP reset
    # the instant it's reused for the next request -- the reset happens at the
    # kernel level before nginx ever parses a request line, so it produces zero
    # access/error-log entries while the browser's fetch() surfaces it as a raw
    # network failure. Root-caused 2026-08-27 (see docs/research/lan-access-root-
    # cause-2026-08-27.md): idle=63s reused clean, idle=66s+ reset 10/10 trials.
    # Widening the timeout doesn't eliminate the race (any finite value still has
    # an edge), but it moves the trigger from "any >65s gap" to "5+ minutes of
    # total silence across every pooled connection", which realistic click-to-
    # click pacing rarely reaches.
    keepalive_timeout 300s;
    ...
```

### 5.4 ⚠️ 这个修复目前只活在容器的可写层里——必须回填进镜像

因为这次发布用的是 `volumes: !override []` 的离线模式,**容器可写层的改动不会持久化进镜像,也不会被下一次 `docker compose up` 重建容器时保留**。也就是说:

- 现在(容器不重建的前提下)问题已经解决,`keepalive_timeout 300s` 持续生效。
- 但凡这个 nginx 容器被重建一次(下次发布、Docker Desktop/主机重启导致的容器重建、手工 `docker compose up nginx` 等),就会退回镜像里烘焙的 65s,**bug 原样复现**。
- 本仓库的源文件已经改好(`docker/nginx/default.conf`,当前 worktree 分支 `jry/wecom-card-closed-loop`)。**这个改动需要被带进下一次构建 `zimu-fulfillment-nginx` 镜像的流程**(不论是走 CI、`docker build`,还是发布脚本),否则下次发布会静默丢掉这次修复。这一步涉及镜像构建/发布流程,超出本次「不重建镜像」的约束范围,留给 Jerry 或负责发布流程的人跟进。

---

## 6. 修复效果验证(第 5 步)

### 6.1 keep-alive 空闲复用测试——修复前后对比(核心证据)

| 空闲时长 D | 修复前(阈值=65s) | 修复后(阈值=300s) |
|---|---|---|
| 70s | 10/10 `ConnectionResetError` | **10/10 成功** |
| 200s | 10/10 `ConnectionResetError`(66s 起即必现,200s 未单独测,同一区间必然失败) | **10/10 成功** |
| 310s(刻意选在新阈值 300s 之外) | 10/10 `ConnectionResetError` | **10/10 `ConnectionResetError`**(与预期一致) |

结果与预期严丝合缝:70s、200s 这两个「修复前必现重置」的空闲时长,修复后**全部转为成功**;310s(故意选在新阈值 300s 之外)**依然 100% 复位**——这一条恰恰是最有说服力的对照组:证明这次改动确实是把触发窗口从「>65s」精确挪到了「>300s」,而不是碰巧掩盖了问题或测试方法本身失效。

### 6.2 并发压测——无回归

修复后重跑 §2.1 完全相同的 1410 次请求(串行 + 10/30/50 并发 × 多轮 + 250 次真实 `/api/v1/orders`):**0/1410 失败**,与修复前持平——确认这个改动没有引入新的问题,并发处理能力不受影响。

### 6.3 nginx access log 复核

`docker logs zimu-fulfillment-nginx-1` 抽查修复后的实时流量(含 Windows 本机 Edge 通过 `http://localhost` 访问的真实工作台流量,`/api/v1/orders`、`/api/v1/shipments`、`/api/v1/review-cases` 等接口),全部 `200`,无异常状态码,容器全程 `healthy`。

（注:nginx 的 `access.log`/`error.log` 在这个镜像里是软链到 `/dev/stdout`/`/dev/stderr`——`docker exec ... tail/grep/wc` 直接读会因为是管道而非普通文件永久阻塞,必须用 `docker logs` 读;顺手记录一下避免下次再踩。）

---

## 7. 顺手发现、未处理的问题(留给 Jerry 判断)

以下几项与本次「局域网连不上」无因果关系,**未做任何改动**,单独列出供参考:

1. **网关级 Basic Auth 当前是关闭的**(`GATEWAY_BASIC_AUTH_ENABLED=false`,容器内 `/etc/nginx/edge-auth.inc` 内容为 `auth_basic off;`)。代码/entrypoint 明确把这个开关设计成「仅用于受控局域网验收」的显式选项,所以可能是有意为之,但目前这套网关对局域网内任何人不需要任何凭据就能访问除 `/healthz`、`/wecom/callbacks/` 之外的全部路径(含 `/api/`、`/actuator/`、`/metabase/`)。建议 Jerry 确认这是否仍是当前阶段的预期状态。
2. `docker exec zimu-fulfillment-nginx-1 nginx -T` 显示 nginx 向后端注入的 Basic Auth 凭据(`backend-auth.inc`)解出来是一个非常弱/像默认值的口令。未改动,建议后续单独跟进轮换。
3. **Windows Defender 防火墙在 Private/Public 两个 profile 上是整机关闭的**(不只是 80 端口),这是比本任务更大范围的安全姿态问题,仅记录,不在本次范围内处理。
4. `C:\Deploy\zimu\current` junction 指向的发布(`checkpoint-00efe89-20260824-1300`)不是实际在跑 nginx/backend/frontend 的发布(`agent-platform-e50bb3e-20260825-0759`)——两者已分叉,后续对着 "current" 操作可能误回滚。
5. Windows 主机本机的 Edge 浏览器(通过 `http://localhost`)此前在 `127.0.0.1:80` 上留有 6 组卡住的 `CLOSE_WAIT`/`FIN_WAIT_2` 连接(`com.docker.backend.exe` ↔ `msedge.exe`),是本次根因同一类竞态在另一个客户端上的实物证据,本次修复对这条路径同样生效,不需要额外处理。

---

## 附:测试脚本

本次压测脚本(纯 Python stdlib,无额外依赖)与原始 JSON 结果留存于
`/private/tmp/claude-501/-Users-jerry-Documents-----claude-worktrees-local-service-jd-sdk-switch-7d7408/9ffb2e72-2bf9-4e46-ad6d-63edfdfa0059/scratchpad/`(`lan_loadtest.py`、`lan_keepalive_idle_test.py` 及 `lan_keepalive_idle_test_after.py`,对应 `*_before*.json` / `*_after*.json`),该目录是本次会话的临时目录,如需长期保留建议 Jerry 另行归档。
