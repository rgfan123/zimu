ALTER TABLE app.shipment_jd_outbounds
    DROP CONSTRAINT shipment_jd_outbounds_tracking_query_status_check;

ALTER TABLE app.shipment_jd_outbounds
    ADD CONSTRAINT shipment_jd_outbounds_tracking_query_status_check CHECK (
        tracking_query_status IN (
            'NOT_QUERIED', 'PENDING', 'PARTIAL', 'TRACKED', 'CONFLICT',
            'QUERY_FAILED', 'TERMINAL_REVIEWED'));

COMMENT ON COLUMN app.shipment_jd_outbounds.tracking_query_status IS
    'JD tracking query state; TERMINAL_REVIEWED is a durable human decision and is not polled again';

DROP INDEX app.idx_shipment_jd_outbounds_tracking_poll;

CREATE INDEX idx_shipment_jd_outbounds_tracking_poll
    ON app.shipment_jd_outbounds (client_mode, tracking_last_query_at, shipment_id)
    WHERE sync_status = 'SUBMITTED'
      AND tracking_query_status NOT IN ('TRACKED', 'TERMINAL_REVIEWED');
