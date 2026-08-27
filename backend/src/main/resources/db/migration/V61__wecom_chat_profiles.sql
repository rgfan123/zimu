-- 会话档案化：回复策略表升级为会话档案——
-- 企微协议不下发群名（帧里只有 chatid），会话名称只能由人起（display_name 备注名）；
-- agent_slug 记录该会话由哪个 Agent 人格服务（路由分流的配置先行，接线随 Agent 平台推进）。
-- reply_mode 补缺省 FULL：仅起名/仅绑 Agent 的行不必先表态回复权限。
ALTER TABLE app.wecom_chat_reply_policies
    ADD COLUMN display_name VARCHAR(128) CHECK (display_name IS NULL OR btrim(display_name) <> ''),
    ADD COLUMN agent_slug   VARCHAR(64)  CHECK (agent_slug IS NULL OR agent_slug ~ '^[a-z][a-z0-9-]{0,63}$');

ALTER TABLE app.wecom_chat_reply_policies
    ALTER COLUMN reply_mode SET DEFAULT 'FULL';

COMMENT ON COLUMN app.wecom_chat_reply_policies.display_name IS
    '会话备注名（人起的；企微 aibot 协议不下发群名，帧里只有 chatid）';
COMMENT ON COLUMN app.wecom_chat_reply_policies.agent_slug IS
    '服务该会话的 Agent（agent_definitions.agent_slug）；分流路由的配置先行';
