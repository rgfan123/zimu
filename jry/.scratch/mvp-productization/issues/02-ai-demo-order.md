# 02 — AI 草稿确认后创建隔离 DemoRun

Type: development
Status: resolved
Blocked by: None — can start immediately

**What to build:** 将现有订单助手多轮提取接入模拟下单页，只有内部用户确认完整草稿后才创建 DEMO 订单。

- [x] AI 对话、缺失项和结构化草稿在浏览器可用。
- [x] 未确认草稿不创建订单；确认后创建带客户/收货人/多行快照的 DEMO DemoRun。
- [x] AI 不可用时固定场景仍可用，并诚实标识能力边界。
- [x] BUSINESS 查询、分析、复核和默认审计均不混入 AI DEMO 数据。

## Answer

订单助手的显式确认命令现在是唯一添加 `confirmed=true` 的位置；Spring `/demo/v1/extracted-orders` 同时校验该确认事实和完整字段，未确认请求返回 `VALIDATION_ERROR`。确认后创建的 `DEMO` DemoRun 从数据库回读客户、完整收货信息和多行商品快照，订单为 `SYNCED`，Timeline 最后一项为 `SOURCE_SYNCED`。

验证：

- `mvn -q '-Dtest=InternalOrderApiTest#demoScenarioRunsToACompletedIsolatedTimeline+confirmedAiDraftCreatesAnIsolatedDemoRunWithItsExtractedOrder+completeButUnconfirmedAiDraftCannotCreateDemoOrder' test`：3/3 通过；覆盖确认门禁、落库快照、幂等、BUSINESS 列表/分析/复核/默认审计隔离。
- `python3 -m unittest -v test_app.py`：2/2 通过；覆盖会话完整性门禁、未确认不调用订单 API、确认时注入确认事实。
- `npm test`：7/7 通过；`npm run build` 通过。
- 公共 Nginx `http://127.0.0.1:18093/demo/order` 浏览器 smoke：确定性假模型边界下完成对话、两行预览、人工确认、`DEMO / SYNCED / 2/2 / SOURCE_SYNCED`；BUSINESS 列表 0 条、详情 404、默认审计 0 条；直接刷新 `/demo/order` 可达且控制台 0 error。假模型仅用于测试，未配置或提交真实模型密钥。
- 另以无模型配置实测默认 AI 门禁，并从同页固定场景跑完 9 步，证明 AI 不可用不阻断固定演示。
