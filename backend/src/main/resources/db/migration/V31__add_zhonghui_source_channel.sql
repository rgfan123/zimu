-- 新增来源渠道 ZHONGHUI（中汇），扩展全部 source_channel CHECK 约束并登记渠道配置。
-- 中汇来源回填模板尚未取得平台实样，承运商映射留空，命中回填前由 CARRIER_MAPPING 复核。

ALTER TABLE app.customer_source_refs DROP CONSTRAINT customer_source_refs_source_channel_check;
ALTER TABLE app.customer_source_refs ADD CONSTRAINT customer_source_refs_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WECOM'));

ALTER TABLE app.source_channel_skus DROP CONSTRAINT source_channel_skus_source_channel_check;
ALTER TABLE app.source_channel_skus ADD CONSTRAINT source_channel_skus_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WECOM'));

ALTER TABLE app.import_batches DROP CONSTRAINT import_batches_source_channel_check;
ALTER TABLE app.import_batches ADD CONSTRAINT import_batches_source_channel_check
    CHECK (source_channel IS NULL OR source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WECOM'));

ALTER TABLE app.orders DROP CONSTRAINT orders_source_channel_check;
ALTER TABLE app.orders ADD CONSTRAINT orders_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WECOM'));

ALTER TABLE app.shipment_syncs DROP CONSTRAINT shipment_syncs_source_channel_check;
ALTER TABLE app.shipment_syncs ADD CONSTRAINT shipment_syncs_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WECOM'));

ALTER TABLE app.connector_configs DROP CONSTRAINT connector_configs_source_channel_check;
ALTER TABLE app.connector_configs ADD CONSTRAINT connector_configs_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WECOM'));

INSERT INTO app.connector_configs (source_channel, config) VALUES
    ('ZHONGHUI', '{"carrier_mappings":{}}'::JSONB);
