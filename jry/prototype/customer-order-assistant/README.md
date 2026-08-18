# Customer Order Assistant — 内部验证版

> 一次性逻辑原型，不是生产客户模块。

这个原型只回答一个问题：客户用自然语言分多轮补齐订单信息后，能否稳定得到系统认可的规范字段，并在人工确认后按统一订单接口的结构提交。只有配置真实订单后端时，才会创建正式订单。

## 运行

准备任意支持 OpenAI Chat Completions 协议的模型服务，然后执行一条命令：

```bash
LLM_BASE_URL=http://127.0.0.1:11434/v1 \
LLM_MODEL=qwen3:4b \
python3 prototype/customer-order-assistant/app.py
```

浏览器打开 <http://127.0.0.1:8765>。

单独运行时默认启用内置演示订单接口，可以跑通「对话 → 预览 → 确认 → 返回演示编号」完整链路。仓库 Docker Compose 会关闭该内置接口，并把确认后的草稿提交到 Spring Boot `/demo/v1/extracted-orders`，创建可查询 Timeline 的隔离 DemoRun。

```bash
ORDER_API_BASE_URL=http://127.0.0.1:8080 \
LLM_BASE_URL=https://your-provider.example/v1 \
LLM_MODEL=your-model \
LLM_API_KEY=your-key \
python3 prototype/customer-order-assistant/app.py
```

## 模型接口配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `LLM_BASE_URL` | `http://127.0.0.1:11434/v1` | OpenAI-compatible 服务根地址 |
| `LLM_CHAT_PATH` | `/chat/completions` | Chat Completions 路径；也可直接填完整 URL |
| `LLM_MODEL` | 空 | 模型名，发送消息前必须配置 |
| `LLM_API_KEY` | 空 | 可选；本地模型通常不需要 |
| `LLM_AUTH_HEADER` | `Authorization` | 密钥请求头名 |
| `LLM_AUTH_SCHEME` | `Bearer` | 密钥前缀；设为空可直接发送密钥 |
| `LLM_EXTRA_HEADERS_JSON` | `{}` | 额外请求头 JSON |
| `LLM_EXTRA_BODY_JSON` | `{}` | 额外请求体字段 JSON |
| `LLM_JSON_MODE` | `true` | 是否发送 `response_format: {"type":"json_object"}` |
| `LLM_TEMPERATURE` | `0` | 设为空则不发送 temperature |
| `LLM_TIMEOUT_SECONDS` | `60` | 模型请求超时 |
| `LLM_TRANSPORT` | `urllib` | HTTP 传输；Cloudflare 拒绝 Python 客户端签名时设为 `curl` |
| `LLM_SYSTEM_PROMPT` | 内置提示词 | 可完全替换系统提示词 |
| `INSIGHT_LLM_MODEL` | 与 `LLM_MODEL` 相同 | 客户洞察 Agent 使用的模型，可单独指定 |
| `INSIGHT_SYSTEM_PROMPT` | 内置洞察提示词 | 可替换画像与品类推荐 Agent 的任务提示 |

订单接口也可通过 `ORDER_API_PATH`、`ORDER_API_EXTRA_HEADERS_JSON` 和 `ORDER_API_TIMEOUT_SECONDS` 配置。API key 和额外请求头只从环境变量读取，不会返回给浏览器。

## 原型边界

- 会话只保存在内存，重启即清空。
- 不做登录、权限、客户主数据匹配、SKU 自动映射、MCP、语音、图片或 Excel。
- 模型只生成业务输入草稿；服务端重新校验必填字段，只有用户点击确认后才调用订单接口。
- `customer_code` 与 `sku_code` 可空，留给正式系统进入 `NEED_REVIEW` 后处理。
- 每轮订单提取完成后触发独立客户洞察 Agent，输出客户画像、偏好标签和最多三个推荐品类；洞察失败不阻断订单提取。
- 洞察结果是客户模块旁路数据，不进入 `POST /internal/v1/orders`。当前画像和品类枚举仅供 MVP 验证，正式版本应对接客户档案与商品品类主数据。
