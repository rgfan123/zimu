# 01 — 分平台拉取规则：各平台各自设时间，可单独关

**What to build:** 每个来源平台有自己的两个拉取时间，能单独开关，能单独决定拉完推不推企微。运营在界面上改，不用改环境变量重启。

**Blocked by:** None

**Status:** open

**Claimed by:** —

---

## 用户原话与已定规格

> 分平台，每个平台我做一个手动设置拉取规则的一个东西，给我弹一个卡片，告诉我比如第一次拉取什么什么，给我个时间选择框，第二次拉取什么什么时候，然后再加上需不需要推送

已经跟用户确认过的取舍（**不要再扩**）：

| 项 | 定论 |
|---|---|
| 时间数量 | **固定两次**（早/晚），各自选时间，各自可单独关掉 |
| 卡片上的开关 | **只加「拉取后推企微」** |
| 拉取窗口天数 / 自动发货 / 自动回传 | **本票不做**（用户明确只选了推送这一项） |
| cron 表达式 | **不给用户填**，只给时间选择框 |

## 现状

全局两个 cron 管三个平台，写在配置里，改了要重启：

```yaml
# application.yml:366-367
morning-cron: ${SCHEDULED_PULL_MORNING_CRON:0 0 9 * * *}
evening-cron: ${SCHEDULED_PULL_EVENING_CRON:0 0 18 * * *}
```

`ScheduledPlatformPullTrigger` 的两个 `@Scheduled` 方法各自调 `service.runOnce(slot)`，
而 `runOnce` 里 `refreshService.refresh(null, context)` 的 `null` 就是「全部渠道」。

## 关键改动：run_key 要下沉到渠道

`ScheduledPullRunStore.runKey(runDate, slot)` 现在是 `日期:时段`，配 `scheduled_pull_runs.run_key`
的唯一约束用来防跨实例重复触发（类注释 `:17` 写明了这个用意）。

**但各平台时间不同之后，这个键就错了**：彩食鲜 08:00 跑完占了 `2026-08-30:MORNING`，
飞象 10:00 那次会被 `begin()` 判成「已被领取」直接跳过 —— **静默漏拉**，正是这个仓一直在防的故障模式。

所以 `run_key` 必须变成 `日期:时段:渠道`，`scheduled_pull_runs` 加 `source_channel` 列。
**迁移编号从 V85 起**（生产已到 V84），并同步 `docs/schema.sql`。

存量行没有渠道值 —— 迁移要想清楚怎么处理（回填一个哨兵值？还是允许可空并在唯一约束里用 COALESCE？）。
**把选择和理由写进迁移注释。**

## 触发方式

两个固定 cron 改成**每分钟一跳**，跳的时候问每个渠道「你现在该拉了吗」，命中的才拉。

不这么做的话没法支持任意时间点。但每分钟跳有两个坑，都要处理：

1. **漏跳**：应用重启、GC 停顿、跳的那一分钟没执行 → 那个渠道当天这一档就没了。
   需要一个补偿窗口（比如配置时间之后 N 分钟内仍可补跑），靠 run_key 唯一约束保证不会跑两次。
2. **时区**：现有 `@Scheduled` 的 `zone` 是 `Asia/Shanghai`（`ScheduledPlatformPullTrigger:57`），
   `runOnce` 里也是 `LocalDate.now(SHANGHAI)`。新逻辑必须沿用同一个时区，不要用系统默认。

## 配置存哪

`app.connector_configs.config`（jsonb）。那列本来就是渠道私有配置（凭据、承运商映射都在里面），
再开一张表反而多一处要对齐。

**空值语义是这个功能最容易埋雷的地方**：

- 没配 → **回落全局默认**（现有的 09:00 / 18:00），**绝不能变成「不拉」**
- 要停某一档 → **显式的开关字段为 false**

理由：如果用「空值 = 不拉」，那么配置读取出问题、字段被写空、jsonb 解析失败时，
系统会**安静地停止拉取**，而界面上看不出任何异常。这个仓已经因为同类问题丢过单
（`connector_configs.last_pull_at` 至今 8 个渠道全 NULL）。**读不到配置一律按全局默认走。**

## 验收标准

- [ ] 每个渠道可独立配置早/晚两个拉取时间（HH:mm），各自可单独启用/停用
- [ ] 每个渠道可独立配置「拉取后推企微」
- [ ] 未配置的渠道行为**与今天完全一致**（09:00 / 18:00 全量拉、推企微），升级不改变现状
- [ ] `run_key` 下沉到渠道粒度，两个渠道在同一时段的不同时间点各自都能跑起来，**互不挤占**
- [ ] 配置读取失败 / jsonb 结构不认识 → 回落全局默认并**记警告**，不静默停拉
- [ ] 漏跳有补偿窗口；补偿不会导致同一渠道同一档当天跑两次
- [ ] 前端：系统配置里每个平台一张卡片，两个时间选择框 + 两个启用开关 + 一个推送开关，保存走乐观锁
- [ ] `docs/openapi.yaml` 登记；契约一致性测试绿
- [ ] 用例覆盖：未配置回落、单档停用、两渠道错峰不互挤、配置损坏回落、补偿窗口不重复跑

## 硬约束

- 不碰生产数据，不部署，不 docker build
- 迁移从 **V85** 起，同步 `docs/schema.sql`，否则 `ProductionMigrationHistoryCompatTest` /
  `SchemaSnapshotMigrationEquivalenceTest` 会红
- 仓库**没有** `./mvnw`，用 `mvn`；契约在 `docs/openapi.yaml`（不在 backend/src/main/resources）
- 前端写请求用 `writeHeaders()`，浏览器不得发 `X-Operator`；请求体 snake_case
- 中文注释与文案，写清「为什么」而不只是「做了什么」
