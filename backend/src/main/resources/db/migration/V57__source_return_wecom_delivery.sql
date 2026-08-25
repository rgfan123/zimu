-- 来源回填文件的企微投递状态。
--
-- 为什么需要这几列而不是复用 push_status：push_status 表达的是「已回传到来源平台」
-- （SourceReturnPushService：彩食鲜 importDeliverExcl / 聚福宝 multi-send JSON）。
-- 而没有在线回传能力的渠道（飞象、大者、中汇：ConnectorCapabilities.onlinePush=false）
-- 根本走不到那条路，push_status 会永远停在 NOT_PUSHED。
-- 若把「已发企微」也写进 push_status，同一个 SUCCESS 就有两种含义——
-- 一种是平台已受理，一种只是文件发给人了，对账时无法区分。故独立成列。
--
-- 语义：NOT_SENT → SENDING → SENT / FAILED。SENDING 带 started_at 供超时回收，
-- 与 push_status 的租约思路一致（企微 ack 超时可能已送达，禁止盲目重发）。

ALTER TABLE app.source_return_exports
    ADD COLUMN wecom_delivery_status TEXT NOT NULL DEFAULT 'NOT_SENT',
    ADD COLUMN wecom_delivery_started_at TIMESTAMPTZ,
    ADD COLUMN wecom_delivered_at TIMESTAMPTZ,
    ADD COLUMN wecom_chat_id TEXT,
    ADD COLUMN wecom_media_id_sha256 TEXT,
    ADD COLUMN wecom_error TEXT;

ALTER TABLE app.source_return_exports
    ADD CONSTRAINT source_return_exports_wecom_status_check
    CHECK (wecom_delivery_status IN ('NOT_SENT', 'SENDING', 'SENT', 'FAILED'));

-- 已送达必须有时刻与目标会话：避免出现「状态说发了但不知道发到哪」的不可对账记录。
ALTER TABLE app.source_return_exports
    ADD CONSTRAINT source_return_exports_wecom_sent_complete
    CHECK (wecom_delivery_status <> 'SENT'
           OR (wecom_delivered_at IS NOT NULL AND wecom_chat_id IS NOT NULL));

-- 扫描器按状态取待发件，只有少量行处于非终态，部分索引足够且最省。
CREATE INDEX source_return_exports_wecom_pending_idx
    ON app.source_return_exports (id)
    WHERE wecom_delivery_status IN ('NOT_SENT', 'SENDING');

COMMENT ON COLUMN app.source_return_exports.wecom_delivery_status IS
    '来源回填文件的企微投递状态；仅对 onlinePush=false 的渠道有意义，其余渠道走 push_status 回传平台';
COMMENT ON COLUMN app.source_return_exports.wecom_media_id_sha256 IS
    '企微临时素材 media_id 的哈希；media_id 本身是 3 天期凭据，按既有纪律不落库明文';
