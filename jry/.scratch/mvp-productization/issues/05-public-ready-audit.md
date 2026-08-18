# 05 — 企业 ERP Public-ready 文案对账

Type: public-ready-test
Status: resolved
Blocked by: None — can start immediately

**What to build:** 按企业级 ERP 产品画像扫描用户可见文案、外部错误和示例渲染路径，交付可溯源对账表，本票不自动改码。

- [x] 每条发现包含位置、原文、A/B/C/D 分类、问题、建议和优先级。
- [x] 覆盖前端、会透传的后端错误、路由/配置和 Mock/示例渲染。
- [x] 核查所有用户可见错误是否包含“发生了什么 + 用户能做什么”。

## Validation

- 完整报告：`docs/public-ready-audit.md`，共 19 项；A 8、B 16、C 5、D 3（组合分类分别计数）。
- 红项逐条复核源码渲染链；公共 Nginx seam 只读抽查 review-case、订单详情、时间线和审计详情动态值。
- `cd frontend && npm test`：7/7 通过。
- `cd frontend && npm run build`：通过（仅既有 chunk size 警告）。
- 占位/调试复查确认 `PlaceholderPage` 未被引用；测试、构建与内部日志均排除。

## Resolution

完成企业 ERP 上线前文案审计并落盘对账表。识别 9 个上线阻塞红项，前三类为：原始错误透传；SDK/JSON/PII 动态对象直出；硬编码操作人和默认 Metabase 管理凭据。本票按约束仅交付审计，不修改用户文案或生产代码。

## Answer

审计结果见 `docs/public-ready-audit.md`。后续应先处理报告中的三个上线阻塞组，再按 🟠、🟡、⚪ 顺序改进；任何改码需另行授权。
