-- 中汇回填模板确认：原表最后新增一列「物流单号」回填京东物流单号。
-- 中汇回填需要承运商映射校验通过，按内部 JD → 京东物流 登记（与聚福宝/飞象一致）。

UPDATE app.connector_configs
SET config='{"carrier_mappings":{"JD":"京东物流"}}'::jsonb
WHERE source_channel='ZHONGHUI';
