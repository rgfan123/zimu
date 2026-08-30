# 子牧履约中台 · 部署手册

> 2026-08-27 补写。此前这套流程只存在于会话记录里,没有任何committed文档——
> 而部署机**无法自己构建镜像**,照着 compose 的常规做法(`docker compose build`)必然失败。
> 这份手册记录实际可行的那条路。

## 环境事实(决定了流程为什么长这样)

| 事实 | 后果 |
|---|---|
| zimupc(Windows + Docker Desktop)**连不上 Docker Hub**,本地也没有构建用基础镜像缓存 | 镜像必须在别处构建后整体搬运过去 |
| 开发机是 Mac(arm64),zimupc 是 amd64 | 必须 `--platform linux/amd64` 交叉构建 |
| backend 的 Dockerfile 上下文是**仓库根目录**,不是 `backend/` | 用 `-f backend/Dockerfile .` |
| 发布目录里的三个 override 文件**不在版本控制里** | 只能 `ssh` 上去读改;别在仓库里找 |
| `C:\Deploy\zimu\current` 这个 junction **已经漂移**,指向的不是实际在跑的发布 | **认目录名,别认 current** |
| 仓库在 iCloud 同步盘时 git 操作会挂死 | 在 `~/zimu-work/main`(非 iCloud)干活 |

现役发布目录:`C:\Deploy\zimu\releases\agent-platform-e50bb3e-20260825-0759`

## 部署流程

### 0. 前提

改动已提交(**必须**——下一步从 git 导出,工作区的在制品不会进镜像,这正是我们要的:
多个 agent 并行时不会把别人的半成品打进生产)。

### 1. 干净导出 + 交叉构建

```bash
cd /Users/jerry/zimu-work/main
TAG=real-$(git rev-parse --short HEAD)
EXPORT=/tmp/zimu-train && rm -rf $EXPORT && mkdir -p $EXPORT
git archive HEAD | tar -x -C $EXPORT
cd $EXPORT
docker build --platform linux/amd64 -f backend/Dockerfile  -t zimu-fulfillment-backend:$TAG  .
docker build --platform linux/amd64 -f frontend/Dockerfile -t zimu-fulfillment-frontend:$TAG .
docker build --platform linux/amd64 -f docker/nginx/Dockerfile -t zimu-fulfillment-nginx:$TAG .
```

只改了某一层就只构建那一层;其余用 `docker tag 旧镜像 新TAG` 对齐标签即可。

### 2. 搬运

```bash
docker save zimu-fulfillment-backend:$TAG zimu-fulfillment-frontend:$TAG zimu-fulfillment-nginx:$TAG \
  | gzip -1 | ssh zimupc "docker load"
```

### 3. 改镜像标签(读-改-写,**不要凭记忆重写整个文件**)

override 文件里除了镜像标签还有别的配置(如 `GATEWAY_BASIC_AUTH_ENABLED`),
凭记忆重写会把它们丢掉——**必须先读回来再 sed**:

```bash
R='C:/Deploy/zimu/releases/agent-platform-e50bb3e-20260825-0759'
ssh zimupc "type C:\\Deploy\\zimu\\releases\\agent-platform-e50bb3e-20260825-0759\\offline.runtime.override.yml" \
  | tr -d '\r' \
  | sed -e "s/backend:real-[a-z0-9]*/backend:$TAG/" \
        -e "s/frontend:real-[a-z0-9]*/frontend:$TAG/" \
        -e "s/nginx:real-[a-z0-9]*/nginx:$TAG/" > /tmp/off.yml
# 三项都必须 >=1 才允许 scp:
grep -c GATEWAY_BASIC_AUTH_ENABLED /tmp/off.yml   # 抹掉 = 公网入口失去认证
grep -c AGENT_TOOL_MODULES         /tmp/off.yml   # 抹掉 = 内部 Agent 工具面为空或漂移
grep -c MCP_MODULES                /tmp/off.yml   # 抹掉 = 公共协议面无工具并在传输开启时启动失败
scp -q /tmp/off.yml "zimupc:$R/offline.runtime.override.yml"
```

### 4. 起新栈(四个 -f 缺一不可)

```bash
ssh zimupc "cd /d C:\\Deploy\\zimu\\releases\\agent-platform-e50bb3e-20260825-0759 && \
  docker compose -f docker-compose.yml -f real-business.override.yml \
                 -f release.runtime.override.yml -f offline.runtime.override.yml \
                 up -d backend frontend nginx"
```

### 5. 验收(全过才算完)

```bash
# 容器健康 + 镜像标签正确
ssh zimupc "docker ps --filter name=zimu-fulfillment --format \"{{.Names}}|{{.Status}}|{{.Image}}\""
# 迁移到位(有新迁移时)
ssh zimupc "docker logs zimu-fulfillment-backend-1 --since 5m 2>&1" | grep "Successfully applied"
# 企微长连接重新订阅(否则卡片全哑)
ssh zimupc "docker logs zimu-fulfillment-backend-1 --since 5m 2>&1" | grep -c "订阅成功"
# 公网认证仍在守(必须 401)
curl -sS -o /dev/null -w "%{http_code}\n" http://114.244.13.53:28443/
```

## 回滚

override 里把镜像标签改回上一个 `real-<sha>`(旧镜像还在 zimupc 上,`docker images` 可查),
重跑第 4 步。

> **三个标签可能不同步,回滚不能一把梭。**
> 只改了 backend 就单独发过一次时,frontend/nginx 还停在更早的版本。
> 例:2026-08-28 生产是 backend `real-88cc262` + frontend/nginx `real-6638925`。
> **回滚前先 `docker ps --format "{{.Names}}|{{.Image}}"` 把三个当前标签抄下来**,
> 各自回各自的;用一个标签统一改回去会把没出问题的那层也一起拖旧。

数据库迁移**不会**随之回滚——若新版本带了迁移,回滚前先确认旧代码能在新 schema 上跑,
否则用 `C:\Deploy\zimu\backups\` 里的 pg_dump 恢复。

## 踩过的坑

- **迁移撞号**:多分支并行时各自占用迁移号,合并前先
  `git ls-tree origin/master:backend/src/main/resources/db/migration` 对比号段。
  生产已执行的号位不可动,未执行方整体顺延。详见 [[zimu-repo-dual-lineage]] 记忆与
  `docs/research/` 下当日报告。
- **Flyway 失败残留**:迁移失败会在 `flyway_schema_history` 留 `success=false` 行,挡住重试,
  需手工 `DELETE FROM public.flyway_schema_history WHERE version='NN' AND success=false`。
- **Docker Hub 抖动**:基础镜像拉取偶发 EOF,构建会失败;重试即可(必要时循环重试)。
- **前端安全上下文**:生产以**明文 HTTP** 提供服务,`crypto.randomUUID()` 等
  `[SecureContext]` API 在此环境下是 undefined,新增调用必须带回退,否则整站后端请求全灭。
