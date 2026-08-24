# 02 — 中汇 PMS 上传前端补回主干

**What to build:** 主干后端已有完整的中汇 PMS 上传能力（7 个端点、13 个 Java 类、5 个迁移），但**前端一个调用点都没有**。把上传 UI 补回来，让这条通道从界面可达。

**Blocked by:** 无

**Status:** ready-for-agent

## 背景 —— 后端建好了，没人能用

2026-08-25 做 master 收敛盘点时发现：

后端在主干上是齐的 —— `ZhonghuiPmsController` 挂在 `/api/v1/zhonghui-pms`，7 个端点：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/status` | 会话状态 |
| GET | `/captcha` | 取验证码 |
| POST | `/login` | 登录 |
| POST | `/logout` | 登出 |
| GET | `/options` | 下拉选项 |
| POST | `/batch-uploads` | 提交批次上传 |
| GET | `/upload-batches/{batch_id}` | 查批次结果 |

配套 `ZhonghuiPmsBatchUploadService` / `ZhonghuiPmsService` / `ZhonghuiPmsSession` /
`ZhonghuiPmsHttpClient` / `ZhonghuiPmsProperties` / `MockZhonghuiPmsClient` /
`ZhonghuiPmsUploadBatch(+Item/Repository/Status)`，迁移 V31 / V32 / V35 / V37 / V50。

前端侧全仓 grep 结果：

- `frontend/src/api/endpoints.ts` —— **零** `zhonghui` / `pms` 条目；
- `frontend/src/navigation.ts` —— **零** 上传 / 中汇 菜单项；
- 整个 `frontend/src` 里只有 labels / semanticStatus / analytics 用到「中汇」作为**来源渠道枚举**，
  与上传通道无关。

也就是说：这 7 个端点从界面完全不可达，`MockZhonghuiPmsClient` 之外没有任何真实使用者。

## 实现在哪里

前端并没有丢，只是从没提交过任何分支 —— 只活在 `snapshot/live-wip-20260825`
（2026-08-25 从 live 工作区抢救出来的快照，这 4 个文件此前**任何分支都没有**）：

- `frontend/src/pages/product/PlatformUploadModal.tsx` —— 上传弹窗
- `frontend/src/pages/product/zhonghuiPmsUpload.ts` —— 上传编排
- `frontend/src/api/zhonghuiPmsIdempotency.ts` —— 幂等键
- `frontend/src/pages/upload/ZhonghuiChannelPage.tsx` —— 渠道页
- 测试：`frontend/test/zhonghuiPmsUpload.test.ts`、`frontend/test/zhonghuiPmsIdempotency.test.ts`

## 范围

- 把上述 4 个源文件 + 2 个测试补回，按主干现状适配：
  - `endpoints.ts` 补 `/api/v1/zhonghui-pms` 系列调用；
  - `navigation.ts` / `routes.tsx` 补入口，遵循 ADR 0002「全站统一外壳」与 ADR 0011「AntD 边界守门」；
  - 页面状态用主干新补的 `PageState`（本轮已合入）与 `AdminVisualComponents`，
    不要再引入一套局部 loading/empty/error。
- 登录态与验证码流程要诚实反映后端语义（`/status` 决定是否需要 `/captcha` + `/login`）。
- 批次结果轮询走 `/upload-batches/{batch_id}`，失败项要能看到逐条原因。

## 非范围

- 后端改动（后端已完备，本票不动 Java）；
- 中汇之外的平台上传通道。

## 验收标准

- [ ] 从主导航可进入中汇上传页，无需手敲 URL；
- [ ] 未登录时引导走验证码 + 登录，登录态失效有明确提示而非静默失败；
- [ ] 批次提交携带幂等键，重复点击不产生第二个批次；
- [ ] 批次结果页能展示逐条成功/失败与失败原因；
- [ ] 组件测试用本轮新补的 vitest 基建（`.test.tsx` → `npm run test:component`）；
- [ ] `npm run test`（unit + component）与 `npm run typecheck` 全绿。

## 验证原则

- 后端用 `MockZhonghuiPmsClient` 走通，不对真实中汇 PMS 发起写操作；
- 幂等（重复点击不产生第二批次）必须有自动化测试。

## Comments

- 本票与 01 票同源：都是 2026-08-25 master 收敛盘点查出的「schema/后端已落地、写路径或界面从没进主干」的遗漏。
- 4 个前端文件在补回主干前，唯一副本是 `snapshot/live-wip-20260825`，删除该分支前请先完成本票。
