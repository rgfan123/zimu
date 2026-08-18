-- ---------------------------------------------------------------------------
-- 媒体证据链路：扩展 V8 已建立的 app.message_media
--
-- V8 已建 message_media（下载状态 / 受控文件引用 / 哈希 / 内容类型 / 大小 /
-- 解密信息 / 失败原因 / 幂等键 UNIQUE (channel_message_id, channel_media_id)），
-- 本迁移只补齐长连接媒体证据链需要的两个字段：
--   source_url —— 原始下载地址（5 分钟有效的临时 URL，留作审计证据；对外不投影）
--   attempts  —— 下载/解密尝试次数（重试上限由调用方任务框架负责）
-- ---------------------------------------------------------------------------

ALTER TABLE app.message_media
    ADD COLUMN source_url VARCHAR(1024),
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0);
