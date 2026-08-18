-- Ticket JD06: preserve the non-secret owner authority used by the accepted outbound request.
-- Historical rows remain NULL and therefore fail closed before querySoOrder.
ALTER TABLE app.shipment_jd_outbounds
    ADD COLUMN submitted_owner_no VARCHAR(128),
    ADD CONSTRAINT shipment_jd_outbounds_submitted_owner_no_not_blank CHECK (
        submitted_owner_no IS NULL OR btrim(submitted_owner_no) <> '');

COMMENT ON COLUMN app.shipment_jd_outbounds.submitted_owner_no IS
    'Exact non-secret customerInfo.ownerNo submitted to JD; NULL history cannot auto-backfill';
