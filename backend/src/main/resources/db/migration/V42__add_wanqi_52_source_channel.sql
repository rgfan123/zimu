-- 真实万齐订单管理导出使用独立 52 列来源渠道。
-- 既有 WANGQI 技术值承载的是大者 15 列历史事实，禁止覆盖或改写。

ALTER TABLE app.customer_source_refs DROP CONSTRAINT customer_source_refs_source_channel_check;
ALTER TABLE app.customer_source_refs ADD CONSTRAINT customer_source_refs_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM'));

ALTER TABLE app.source_channel_skus DROP CONSTRAINT source_channel_skus_source_channel_check;
ALTER TABLE app.source_channel_skus ADD CONSTRAINT source_channel_skus_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM'));

ALTER TABLE app.import_batches DROP CONSTRAINT import_batches_source_channel_check;
ALTER TABLE app.import_batches ADD CONSTRAINT import_batches_source_channel_check
    CHECK (source_channel IS NULL OR source_channel IN
           ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM'));

ALTER TABLE app.orders DROP CONSTRAINT orders_source_channel_check;
ALTER TABLE app.orders ADD CONSTRAINT orders_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM'));

ALTER TABLE app.shipment_syncs DROP CONSTRAINT shipment_syncs_source_channel_check;
ALTER TABLE app.shipment_syncs ADD CONSTRAINT shipment_syncs_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM'));

ALTER TABLE app.connector_configs DROP CONSTRAINT connector_configs_source_channel_check;
ALTER TABLE app.connector_configs ADD CONSTRAINT connector_configs_source_channel_check
    CHECK (source_channel IN ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM'));

ALTER TABLE app.source_channel_bundles DROP CONSTRAINT source_channel_bundles_source_channel_check;
ALTER TABLE app.source_channel_bundles ADD CONSTRAINT source_channel_bundles_source_channel_check
    CHECK (source_channel IN
           ('CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM'));

-- 来源回填契约尚未确认，不配置承运商映射；回填用例另行显式失败关闭。
INSERT INTO app.connector_configs (source_channel, config) VALUES
    ('WANQI', '{"carrier_mappings":{}}'::JSONB);

-- 万齐 52 列源文件没有结账方式/时间。缺失事实只允许该文件来源使用；
-- 其他入口仍由 DTO @NotNull 校验，并由本约束要求非空时间。
ALTER TABLE app.orders DROP CONSTRAINT orders_settlement_method_check;
ALTER TABLE app.orders ALTER COLUMN settlement_time DROP NOT NULL;
ALTER TABLE app.orders ADD CONSTRAINT orders_settlement_method_check
    CHECK (settlement_method IN
           ('UNSPECIFIED', 'MONTHLY', 'IMMEDIATE', 'CREDIT_TERM', 'PREPAID', 'COD', 'OTHER'));
ALTER TABLE app.orders ADD CONSTRAINT orders_settlement_consistency CHECK (
    (source_channel = 'WANQI' AND settlement_method = 'UNSPECIFIED' AND settlement_time IS NULL)
    OR
    (settlement_method <> 'UNSPECIFIED' AND settlement_time IS NOT NULL)
);

ALTER TABLE app.import_batches
    ADD COLUMN settlement_missing BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app.import_batches
    ADD CONSTRAINT import_batches_settlement_missing_source_check CHECK (
        NOT settlement_missing OR (batch_type = 'SOURCE_ORDER' AND source_channel = 'WANQI')
    );
