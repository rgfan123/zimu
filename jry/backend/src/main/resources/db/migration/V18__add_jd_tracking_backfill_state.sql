-- Ticket 06: preserve the exact non-PII cargo facts submitted to JD and keep
-- tracking-query diagnostics separate from the outbound-create state machine.
ALTER TABLE app.shipment_jd_outbounds
    ADD COLUMN submitted_cargo_snapshot JSONB,
    ADD COLUMN submitted_warehouse_no VARCHAR(128),
    ADD COLUMN tracking_query_status VARCHAR(32) NOT NULL DEFAULT 'NOT_QUERIED',
    ADD COLUMN tracking_query_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN tracking_last_query_at TIMESTAMPTZ,
    ADD COLUMN tracking_last_error_code VARCHAR(64),
    ADD COLUMN tracking_last_error_message TEXT,
    ADD COLUMN tracking_last_request_id VARCHAR(128),
    ADD CONSTRAINT shipment_jd_outbounds_cargo_snapshot_shape CHECK (
        submitted_cargo_snapshot IS NULL OR jsonb_typeof(submitted_cargo_snapshot) = 'array'),
    ADD CONSTRAINT shipment_jd_outbounds_tracking_query_status_check CHECK (
        tracking_query_status IN ('NOT_QUERIED', 'PENDING', 'PARTIAL', 'TRACKED', 'CONFLICT', 'QUERY_FAILED')),
    ADD CONSTRAINT shipment_jd_outbounds_tracking_attempt_count_check CHECK (
        tracking_query_attempt_count >= 0),
    ADD CONSTRAINT shipment_jd_outbounds_tracking_error_pair_check CHECK (
        (tracking_last_error_code IS NULL) = (tracking_last_error_message IS NULL));

-- History before V18 has no trustworthy submitted cargo detail.  It remains
-- NULL and therefore fails closed for automatic tracking backfill.
COMMENT ON COLUMN app.shipment_jd_outbounds.submitted_cargo_snapshot IS
    'Exact non-PII orderLine/goodsNo/planQuantity facts submitted to JD; NULL history cannot auto-backfill';
COMMENT ON COLUMN app.shipment_jd_outbounds.submitted_warehouse_no IS
    'Exact JD warehouseNo submitted with the outbound order; NULL history cannot auto-backfill';

CREATE INDEX idx_shipment_jd_outbounds_tracking_poll
    ON app.shipment_jd_outbounds (client_mode, tracking_last_query_at, shipment_id)
    WHERE sync_status = 'SUBMITTED' AND tracking_query_status <> 'TRACKED';
