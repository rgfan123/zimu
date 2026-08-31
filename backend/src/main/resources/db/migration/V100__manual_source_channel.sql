-- 手工建单渠道 MANUAL（柜台/运营直录，不经导入批次，不属企微消息线）。
-- 范围刻意收敛：只放行 MANUAL 真实出现的两张表——orders 与 customer_source_refs
-- （手工单绑定既有客户时以 customer_code 落一条 MANUAL 来源引用）。
-- 其余渠道白名单（import_batches/connector_configs/shipment_syncs/模板/礼包映射等）
-- 不加 MANUAL：手工单没有导入批次、没有拉单连接器、没有平台回传，加了就是撒谎。

-- 1) orders 渠道白名单
ALTER TABLE app.orders DROP CONSTRAINT orders_source_channel_check;
ALTER TABLE app.orders ADD CONSTRAINT orders_source_channel_check
    CHECK (source_channel IN (
        'CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI',
        'WANGQI', 'DAZHE', 'WANQI', 'WECOM', 'MANUAL'));

-- 2) 业务单必须挂导入批次的豁免名单：WECOM 之外再加 MANUAL（两者都是无批次通道）
ALTER TABLE app.orders DROP CONSTRAINT orders_check2;
ALTER TABLE app.orders ADD CONSTRAINT orders_check2
    CHECK (data_scope = 'DEMO'
        OR source_channel IN ('WECOM', 'MANUAL')
        OR source_import_batch_id IS NOT NULL);

-- 3) 反向：无批次通道禁止挂批次
ALTER TABLE app.orders DROP CONSTRAINT orders_check3;
ALTER TABLE app.orders ADD CONSTRAINT orders_check3
    CHECK (data_scope = 'DEMO'
        OR source_channel NOT IN ('WECOM', 'MANUAL')
        OR source_import_batch_id IS NULL);

-- 4) 结账信息：手工单与万齐同款——柜台录单没有渠道结算事实，允许 UNSPECIFIED+空时间；
--    其余渠道维持「必须有明确结账方式与时间」不变
ALTER TABLE app.orders DROP CONSTRAINT orders_settlement_consistency;
ALTER TABLE app.orders ADD CONSTRAINT orders_settlement_consistency
    CHECK ((source_channel IN ('WANQI', 'MANUAL')
                AND settlement_method = 'UNSPECIFIED'
                AND settlement_time IS NULL)
        OR (settlement_method <> 'UNSPECIFIED' AND settlement_time IS NOT NULL));

-- 5) customer_source_refs 渠道白名单：手工单按 customer_code 绑定既有客户时落 MANUAL 引用
ALTER TABLE app.customer_source_refs DROP CONSTRAINT customer_source_refs_source_channel_check;
ALTER TABLE app.customer_source_refs ADD CONSTRAINT customer_source_refs_source_channel_check
    CHECK (source_channel IN (
        'CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI',
        'WANGQI', 'DAZHE', 'WANQI', 'WECOM', 'MANUAL'));
