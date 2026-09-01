-- V104: freeze the exact non-secret JD pin used by every outbound intent.  ownerNo,
-- warehouseNo and cargo were introduced earlier; ticket 203-7 moves all four facts
-- to the durable SUBMITTING boundary before addSoOrder and verifies them on readback.
-- Historical rows remain NULL and therefore fail closed for tenant-sensitive queries.
ALTER TABLE app.shipment_jd_outbounds
    ADD COLUMN submitted_pin VARCHAR(128),
    ADD CONSTRAINT shipment_jd_outbounds_submitted_pin_not_blank CHECK (
        submitted_pin IS NULL OR btrim(submitted_pin) <> '');

COMMENT ON COLUMN app.shipment_jd_outbounds.submitted_pin IS
    'Exact non-secret JD pin frozen with the outbound submit intent; NULL history cannot perform tenant-sensitive automatic queries';

-- Rollback: drop the constraint and column only before any V104 submit intent exists.
