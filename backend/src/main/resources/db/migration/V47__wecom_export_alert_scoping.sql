-- Issue #84（第二轮修复）：企微导出告警按 (shipment_id, detail.export_id) 隔离，不跨导出误关。
--
-- 背景：续发导出可与原导出共享 fulfillment/shipment。旧的全局 subject 唯一索引
-- （uq_operational_alert_active_subject，按 order/order_line/fulfillment/shipment 组合）
-- 会让两个导出对同一 shipment 的活动告警互相冲突（并发 ensure 时返回对方导出的告警，
-- 或新导出告警无法落库），且按 fulfillment 的 supersede 会误关另一个导出的活动告警。
--
-- 拆分：
--  - 非企微导出告警沿用原 (alert_type, subject) 唯一语义（行为不变）；
--  - 企微导出告警按 (alert_type, shipment_id, detail->>'export_id') 唯一——同一导出
--    同一 delivery 多次 ensure 只一条活动告警，绝不跨导出（续发共享 shipment 也互不影响）。
DROP INDEX app.uq_operational_alert_active_subject;

CREATE UNIQUE INDEX uq_operational_alert_active_subject
    ON app.operational_alerts(
        alert_type,
        (COALESCE(order_id, 0)),
        (COALESCE(order_line_id, 0)),
        (COALESCE(fulfillment_id, 0)),
        (COALESCE(shipment_id, 0)))
    WHERE status IN ('OPEN', 'ACKNOWLEDGED') AND alert_type <> 'FULFILLMENT_EXPORT_WECOM';

CREATE UNIQUE INDEX uq_operational_alert_active_wecom_export
    ON app.operational_alerts(
        alert_type,
        (COALESCE(shipment_id, 0)),
        (detail->>'export_id'))
    WHERE alert_type = 'FULFILLMENT_EXPORT_WECOM' AND status IN ('OPEN', 'ACKNOWLEDGED');
