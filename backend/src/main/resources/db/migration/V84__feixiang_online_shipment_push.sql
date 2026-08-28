-- 飞象在线回传（POST /order/ajaxSendOrderProduct）接入所需的两处最小改动。
--
-- 本迁移刻意<不>新建任何表：
--   * 写意图与状态机复用 app.shipment_syncs（V1 建表 + V54 扩展），连同 V54 已有的
--     「在线回传 vs 回填文件人工上传」双向互斥触发器一起继承；
--   * 外部写幂等复用 app.idempotency_registry（scope = 'feixiang.shipment'）。
-- 旧线那份 V33__source_shipment_syncs.sql 自建表方案作废：它会绕开上面两条既有护栏。

-- 1) platform_intent_key 对飞象同样强制。
--
-- 飞象和聚福宝一样有 Adapter 内层幂等 store，因此必须在 SYNCING / RECONCILIATION_REQUIRED
-- 两个状态登记内层原始意图键，否则人工对账判定「平台未受理」时
-- SourceShipmentSyncService.doReconcile 会跳过 connector.releaseShipmentIntent，
-- 内层 store 永远解不开，同一个键再也拿不到 PROCEED。
ALTER TABLE app.shipment_syncs
    DROP CONSTRAINT shipment_syncs_syncing_intent_check,
    ADD CONSTRAINT shipment_syncs_syncing_intent_check CHECK (
        sync_status <> 'SYNCING'
        OR (
            intent_key IS NOT NULL
            AND check_hash IS NOT NULL
            AND artifact_hash IS NOT NULL
            AND source_line_ref IS NOT NULL
            AND carrier_code IS NOT NULL
            AND tracking_number IS NOT NULL
            AND intent_started_at IS NOT NULL
            AND attempt_count > 0
            AND (source_channel NOT IN ('JUFUBAO', 'FEIXIANG') OR platform_intent_key IS NOT NULL)
        )
    ),
    DROP CONSTRAINT shipment_syncs_reconciliation_effect_check,
    ADD CONSTRAINT shipment_syncs_reconciliation_effect_check CHECK (
        sync_status <> 'RECONCILIATION_REQUIRED'
        OR (
            intent_key IS NOT NULL
            AND check_hash IS NOT NULL
            AND artifact_hash IS NOT NULL
            AND source_line_ref IS NOT NULL
            AND carrier_code IS NOT NULL
            AND tracking_number IS NOT NULL
            AND intent_started_at IS NOT NULL
            AND effect_started_at IS NOT NULL
            AND attempt_count > 0
            AND (source_channel NOT IN ('JUFUBAO', 'FEIXIANG') OR platform_intent_key IS NOT NULL)
        )
    );

-- 2) 承运商平台代码：新增平级键 carrier_api_codes，绝不就地改 carrier_mappings。
--
-- carrier_mappings.JD = '京东物流' 是<显示名>，回填 CSV 的「物流公司」列读的就是它
-- （2026-08-28 16:28 生产实测走通的那条人工上传路径）。而 ajaxSendOrderProduct 要的是
-- <代码> 'jingdong'（2026-08-28 HAR 实测）。就地把值改成代码会把还在用的 CSV 一起改坏，
-- 所以两者并存：文件回填读 carrier_mappings，在线回传读 carrier_api_codes。
-- FeixiangCarrierCodeResolver 只认后者，未覆盖时判「未映射」并阻断，绝不回落成显示名。
UPDATE app.connector_configs
SET config = jsonb_set(
        COALESCE(config, '{}'::jsonb),
        ARRAY['carrier_api_codes'],
        COALESCE(config -> 'carrier_api_codes', '{}'::jsonb) || '{"JD": "jingdong"}'::jsonb,
        true
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE source_channel = 'FEIXIANG';

ALTER TABLE app.connector_configs
    ADD CONSTRAINT connector_configs_carrier_api_codes_object CHECK (
        config -> 'carrier_api_codes' IS NULL
        OR jsonb_typeof(config -> 'carrier_api_codes') = 'object'
    );
