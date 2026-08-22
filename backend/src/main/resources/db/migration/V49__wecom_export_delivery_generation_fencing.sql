-- Issue #84（第三轮修复）：delivery 代际栅栏（generation fencing）。
--
-- 背景（详见 docs/agents/wecom-outbound-send.md §10.6）：
--  1) INITIAL beginAttempt 与 REMINDER prepareReminder 必须在「活跃租约 owner 复查 + 续租」与
--     「delivery PENDING→SENDING + recipient 持久化」同一短事务内线性化，否则旧 Worker 丢失
--     租约后仍可把 delivery 拉到 SENDING，使新 owner 误判 UNKNOWN；
--  2) 旧 REMINDER 的迟到 SUCCESS/FAILED/UNKNOWN 可能覆盖更晚成功的新 INITIAL 时间线——提醒必须
--     绑定到「创建/准备时的 INITIAL 代际」（复用已有的 INITIAL delivery sequence 作为代际），
--     代际变化后旧提醒结果只落 SUPERSEDED 证据，绝不改新时间线/reminder_count/next_reminder_at/告警；
--  3) 旧 INITIAL phase-2 成功收口可能关闭更新代际 resend 失败的红告警——告警关闭必须按成功
--     delivery 的 INITIAL 代际收窄（只关 <= 该代际的告警）。
--
-- 迁移协调：组合基线已经包含 #89（V48）；本迁移以连续 V49 只追加一列
-- initial_generation 并扩展 status 允许 SUPERSEDED，只改本功能自有的 deliveries 表。
-- 尚未合入的 #90 后续必须改用下一空闲版本 V50，见 docs/agents/wecom-outbound-send.md §10.6。

ALTER TABLE app.fulfillment_export_wecom_deliveries
    ADD COLUMN initial_generation INTEGER;

-- INITIAL 行：代际 = 自身 sequence（INITIAL 自己就是代际锚点）。
UPDATE app.fulfillment_export_wecom_deliveries
SET initial_generation = sequence
WHERE kind = 'INITIAL';

-- REMINDER 行：代际 = 创建该提醒时最新的 INITIAL sequence（存量行以当前最新 INITIAL 回填；
-- 本特性尚未发布，存量提醒必然属于当时唯一/最新的 INITIAL 代际，回填语义等价）。
UPDATE app.fulfillment_export_wecom_deliveries d
SET initial_generation = (
    SELECT COALESCE(MAX(i.sequence), 1)
    FROM app.fulfillment_export_wecom_deliveries i
    WHERE i.export_id = d.export_id AND i.kind = 'INITIAL')
WHERE d.kind = 'REMINDER';

ALTER TABLE app.fulfillment_export_wecom_deliveries
    ALTER COLUMN initial_generation SET NOT NULL;

ALTER TABLE app.fulfillment_export_wecom_deliveries
    ADD CONSTRAINT fulfillment_export_wecom_deliveries_initial_generation_check
    CHECK (initial_generation > 0);

-- status 允许 SUPERSEDED：旧提醒被更新 INITIAL 代际取代的诚实终态（不触发告警、不改时间线）。
ALTER TABLE app.fulfillment_export_wecom_deliveries
    DROP CONSTRAINT fulfillment_export_wecom_deliveries_status_check;

ALTER TABLE app.fulfillment_export_wecom_deliveries
    ADD CONSTRAINT fulfillment_export_wecom_deliveries_status_check
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN', 'SUPERSEDED'));
