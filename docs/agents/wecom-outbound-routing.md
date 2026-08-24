# 履约方 → 企微群映射与出站路由（Issue #83）

> 本地 Resolution：按仓库约定（docs/agents/issue-tracker.md），不改动 GitHub Issue 正文，
> 交付说明记录在本文件。本文件是履约导出后企微通知（#84 发送）的**路由事实源**：
> 群 chatid 存哪、怎么改、何时生效、未登记怎么办、#84 消费哪个 seam。

## 1. 存储：`fulfillment_providers.config` JSONB 的 `wecomGroupChatId` 键

- **键名**：`wecomGroupChatId`（命名明确群聊语义；常量唯一归属
  `backend/.../sku/FulfillmentProviderWecomConfig.GROUP_CHAT_ID_KEY`，代码中不得再出现
  该字符串字面量，避免重复 config 解析）。
- **载体**：既有 `fulfillment_providers.config` JSONB 列，与京东键（`outboundMode`、
  `townRequired`、`customerCode`、`sourceNo` 等）共存；无 schema 变更。
- **为什么不是环境变量/密钥表**：群会换、会加，改配置要重启；进 DB 天然有审计与版本并发，
  且能在既有履约方配置页管理。chatid 是**标识符不是凭据**，不触碰「密钥绝不进 DB、日志、
  DTO」红线：响应与审计按既有 Provider 更新投影回显值（`pin` 仍只标记存在性）。

## 2. 管理界面与写入规则

- 入口：系统管理 → 履约方配置（`FulfillmentProvidersPage`），编辑弹窗内「企微群 chatid」
  字段对所有履约方类型（JD 云仓与第三方）开放；列表页「企微群」列展示已登记值或「未登记」。
- PATCH `/api/v1/fulfillment-providers/{id}` 的 `config` 合并写入：
  - **新增/修改**：`{"wecomGroupChatId": "<chatid>"}`，仅合并该键，不覆盖其他 config 键；
  - **清除**：`{"wecomGroupChatId": null}`（显式 null 即清除；空串不是清除，会被拒绝）；
  - 其他 config 键（`outboundMode`/`townRequired`/`customerCode` 等）不受影响；
  - 版本并发沿用既有 `VERSION_CONFLICT`（expected_version 乐观锁），变更进审计流水
    （`fulfillment_provider.update`，沿用既有投影，不额外打印整个 config）。
- **写入校验**（`FulfillmentProviderWecomConfig.validate`，422
  `FULFILLMENT_PROVIDER_WECOM_GROUP_CHAT_ID_INVALID`，字段级错误
  `config.wecomGroupChatId`）：
  1. `null` = 清除；
  2. 非 null 先 `trim`，trim 后为空串 → 拒绝；
  3. 最长 128 字符；
  4. 只接受**可见 ASCII（0x21–0x7E）**，禁止空白与控制字符。官方公开索引没有可靠前缀
     规范，**不要求任何前缀**（保守规则，后续有官方规范可再收紧）。
- 前端（`validateGroupChatId`）与 OpenAPI（`docs/openapi.yaml` 的
  `FulfillmentProviderPatch.config`）与上述规则同步；错误提示按入口适配：页面提示
  「清除登记请留空保存」，API 提示「清除登记请提交 null」，规则语义一致。
- 读取侧（`normalizeStored`）与写入侧共用同一套规则：存量值非法（如历史脏数据）视为
  未登记，解析 seam 抛明确错误、响应投影为 null，不向消费侧输出非法值。

## 3. 即时生效

- 写入即落库；消费侧**每次调用实时读 DB（无缓存）**，登记/修改/清除后下一次解析立即生效，
  无需重启（`WecomGroupChatResolver` 不持有任何本地缓存或静态状态）。

## 4. #84 消费 seam：`WecomGroupChatResolver`

```java
// backend/.../connector/wecom/WecomGroupChatResolver.java
@Autowired WecomGroupChatResolver resolver;
String chatId = resolver.resolve(providerId); // 按履约方 ID 解析企微群 chatid
```

- **未登记/已清除**：抛 422 `FULFILLMENT_PROVIDER_WECOM_GROUP_CHAT_MISSING`，消息含
  「请在履约方配置登记企微群」，**不静默返回空**；错误信息只含履约方编码与可操作指引，
  不输出其他 config/密钥内容。
- **履约方不存在**：404 `NOT_FOUND`。
- 深模块职责：隐藏 DB 读取、config 键、读取规则；#84 发送侧只依赖该 seam，不直接触碰
  `fulfillment_providers` 表或 config map。

## 5. 测试门禁

- 后端：`FulfillmentProviderWecomGroupChatApiTest`（HTTP 写入 seam：保留其他 config、
  即时读写、trim、非法值拒绝、版本并发、审计投影、未知键拒绝）、
  `WecomGroupChatResolverTest`（解析 seam：未登记明确错误、登记/修改/清除即时生效、
  未知履约方 404、不泄漏其他 config）。
- 前端：`frontend/test/fulfillmentProvidersPage.test.ts`（生产路由：企微群列渲染、
  编辑弹窗 PATCH 载荷携带 `wecomGroupChatId`、清除提交显式 null）。
