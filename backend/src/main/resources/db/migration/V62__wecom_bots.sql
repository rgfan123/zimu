-- 企微机器人管理台账（管理界面先行）：登记会出现在企业微信通讯录里的 aibot 实例
-- （bot_id / 名称 / 密钥 / 启用 / 备注）。运行时长连接凭据仍取自 app.wecom.* 部署配置
-- （WecomProperties 单机器人假设，WecomConnectionManager 仍只持有一个
-- WecomLongConnectionClient）；本表登记的实例暂不影响任何在跑连接，接线随多机器人
-- 能力推进，此处只做存储与管理界面。
-- secret 明文列，与 fulfillment_providers.config 里京东 pin 同一存法（全库无自研加密
-- 方案）：读侧与审计只投影是否已配置，永不回显明文。
CREATE TABLE app.wecom_bots (
    bot_id      VARCHAR(128) PRIMARY KEY CHECK (btrim(bot_id) <> ''),
    name        VARCHAR(128) NOT NULL CHECK (btrim(name) <> ''),
    secret      VARCHAR(255),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    note        VARCHAR(500),
    updated_by  VARCHAR(128) NOT NULL CHECK (btrim(updated_by) <> ''),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE app.wecom_bots IS
    '企微机器人实例登记（管理界面先行，运行时多机器人接线未启用）：secret 明文列，读侧/审计只投影存在性';
COMMENT ON COLUMN app.wecom_bots.secret IS
    '机器人密钥明文；与京东 pin（fulfillment_providers.config）同一存法，读侧与审计永不回显明文';
