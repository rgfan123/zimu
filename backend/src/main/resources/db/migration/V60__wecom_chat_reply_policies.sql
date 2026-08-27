-- 会话级回复策略：同一个机器人在不同业务场景要有不同的嘴。
-- 客户群只许静默收单（文件回执/回填文件等白名单照发），个人助手可自由应答与追问。
-- 缺省无行 = FULL（自由回复，与既有行为一致）；策略只约束「对话性」出口，
-- 业务投递（回填文件、发货清单、业务卡）不受影响。
CREATE TABLE app.wecom_chat_reply_policies (
    chat_id     VARCHAR(128) PRIMARY KEY CHECK (btrim(chat_id) <> ''),
    reply_mode  VARCHAR(16) NOT NULL CHECK (reply_mode IN ('FULL', 'RECEIPTS_ONLY')),
    note        VARCHAR(500),
    updated_by  VARCHAR(128) NOT NULL CHECK (btrim(updated_by) <> ''),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE app.wecom_chat_reply_policies IS
    '企微会话回复策略：RECEIPTS_ONLY=静默（抑制泛回执与追问草稿卡），无行=FULL 自由回复';
