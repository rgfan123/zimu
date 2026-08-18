# 07 — 接收图片并形成可复核订单草稿

**What to build:** 企业成员转发图文并 `@` 机器人后，系统立即回执并在后台可靠下载、解密和留存原图；多模态解释生成可编辑订单草稿，运营人员始终可以对照原图修正和确认。

**Blocked by:** 04 — 确认一条完整客户订单.

**Status:** resolved

**Claimed by:** zed-agent (2026-08-14 认领并行开工并收口：媒体链路由 wecom-long-connection 建成，本票承接解释器接入/异步化/复核页原图接口)

- [x] 回调只保存媒体引用和创建异步任务，不等待下载或识别即可回复“已接收”。
- [x] 媒体通过企业微信 Adapter 下载并解密，原件以内容哈希写入受控存储，保存内容类型、大小、状态和失败原因。
- [x] 相同文件内容复用内容寻址存储但保留各自消息证据关系，原件不可被识别结果覆盖。
- [x] `MessageInterpreter` 能接收文字和受控媒体输入，保存模型/提示词版本及派生输出；重新识别追加版本。
- [x] 下载、解密或识别的临时错误按任务规则重试三次，最终失败只产生一个 `NEED_REVIEW` 事项。
- [x] 复核页面通过受权接口显示原图、模型原值与人工修订，不暴露磁盘路径、临时下载凭据或秘密。
- [x] 人工可以把成功解释的图片草稿按既有订单确认事务创建真实订单。
- [x] HTTP、Worker 与浏览器验收覆盖图文成功、重复回调、内容去重、下载失败、识别失败、重新识别和人工修正。

## Answer

zed-agent 收口（2026-08-14）：图文消息接收后回调线程只落证据+回执，媒体下载解密移入解释任务（prepare 阶段幂等下载，受控引用进 `InterpretationInput.mediaContentRefs`，暂时/终态失败抛 Retryable 走 3 次重试与 NEED_REVIEW 收口；`MessageMediaStore` 落库改 REQUIRES_NEW 保证跨尝试累积 attempts）。新增：`MessageMediaContentController`（GET /api/v1/message-media/{id}/content 受权原图接口）、`WecomMediaEvidenceService.extractMediaRefs` 公共解析。验证：InterpretationMediaIntegrationTest 3/3（图文→引用、纯文字空引用、下载失败→FAILED→NEED_REVIEW）、MessageMediaContentApiTest 4/4、WecomMediaEvidenceServiceTest 5/5（幂等/去重/失败）、全量后端 443/443、前端 155/155。
