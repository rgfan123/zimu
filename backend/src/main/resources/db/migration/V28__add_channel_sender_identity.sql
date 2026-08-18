-- 05 票：渠道消息发送者身份分类与接入类型声明。
--
-- 消息入口（Adapter）仅在提供真实客户渠道身份时显式标记 sender_identity_type='CUSTOMER'
-- 并声明接入类型 sender_access_type（如未来"客户联系"/"微信客服"入口）；默认 'EMPLOYEE'
-- 覆盖普通微信群转发员工等仅传输身份场景，此类消息绝不建立到 Customer 的渠道身份绑定。
ALTER TABLE app.channel_messages
    ADD COLUMN sender_identity_type VARCHAR(16) NOT NULL DEFAULT 'EMPLOYEE'
        CHECK (sender_identity_type IN ('EMPLOYEE', 'CUSTOMER')),
    ADD COLUMN sender_access_type VARCHAR(64);
