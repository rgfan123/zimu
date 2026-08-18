# AI 提取订单接入模拟下单

Type: feature
Status: resolved

## Goal

将 `prototype/customer-order-assistant` 已验证的多轮订单提取能力接入管理端“模拟下单”，经人工核对后创建隔离的 DemoRun。

## Acceptance

- “模拟下单”默认提供 AI 对话录入、缺失项提示和结构化订单预览；
- 只有草稿完整且用户点击确认后，才创建 `data_scope=DEMO` 的订单；
- 提取出的客户、收货人和多条商品快照进入 Demo 订单，完成的 Timeline 仍以 `SYNCED` 结束；
- 原固定场景保留，AI 服务未配置时给出明确门禁，不影响固定场景；
- Docker/Nginx/Vite 同源接入订单助手，密钥只通过环境变量注入；
- BUSINESS 订单、分析和业务列表不出现 AI 演示订单。

## Public test seams

1. Spring HTTP `/demo/v1/extracted-orders`；
2. Order Assistant HTTP session API；
3. Browser route `/demo/order`。

## Answer

已完成三条公共缝纵向接入：订单助手多轮会话只生成草稿，服务端按完整字段重新判定 `READY_TO_CONFIRM`；显式确认后才附加 `confirmed=true` 并调用 Spring Demo API。Spring 入口拒绝未确认或不完整草稿，成功响应包含从数据库回读的客户、收货人、两行商品快照和 9 步 Timeline，终态为 `SYNCED`。

Docker Compose、Nginx 与 Vite 均使用同源 `/customer/v1/order-assistant` 和 `/demo/v1` 路径；模型配置与可选密钥只从环境变量进入订单助手。无模型时页面明确提示能力未配置，固定场景仍可切换运行。

Validation 与公共 Nginx 浏览器证据同 `.scratch/mvp-productization/issues/02-ai-demo-order.md`；本轮未使用真实模型密钥，确定性假模型成功不代表任何真实模型服务已验证。
