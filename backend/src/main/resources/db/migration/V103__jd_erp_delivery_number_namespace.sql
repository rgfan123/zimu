-- V103: reserve a Zimu-owned external namespace for every new JD addSoOrder writer.
-- Historical submitted or uncertain erpDeliveryNo values are immutable; definite no-write failures
-- may migrate only after their persisted request/business facts are proven unchanged.
-- This prospectively supersedes V9's original equality contract with shipments.outbound_order_no.
ALTER TABLE app.shipment_jd_outbounds
    ADD COLUMN business_facts_hash CHAR(64),
    ADD CONSTRAINT shipment_jd_outbounds_business_facts_hash_format CHECK (
        business_facts_hash IS NULL OR business_facts_hash ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN app.shipment_jd_outbounds.business_facts_hash IS
    'SHA-256 of submitted JD business facts excluding replaceable erpDeliveryNo; NULL history is verified by the legacy exact request hash before reallocation';

CREATE SEQUENCE app.jd_erp_delivery_no_seq AS BIGINT
    MINVALUE 1
    MAXVALUE 999999999999
    NO CYCLE;

CREATE FUNCTION app.next_jd_erp_delivery_no(
    generated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
) RETURNS VARCHAR(64)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN 'ZIMU-SO-'
        || to_char((generated_at AT TIME ZONE 'Asia/Shanghai')::DATE, 'YYYYMMDD')
        || '-'
        || lpad(nextval('app.jd_erp_delivery_no_seq')::TEXT, 12, '0')
        || '-'
        || upper(substr(replace(gen_random_uuid()::TEXT, '-', ''), 1, 8));
END;
$$;

COMMENT ON FUNCTION app.next_jd_erp_delivery_no(TIMESTAMPTZ) IS
    'Allocates a ZIMU-SO namespaced JD erpDeliveryNo; local uniqueness is enforced by shipment_jd_outbounds.erp_delivery_no';

COMMENT ON COLUMN app.shipment_jd_outbounds.erp_delivery_no IS
    'JD merchant outbound reference; new values use ZIMU-SO namespace and are independent from shipments.outbound_order_no';

-- Rollback: drop the function, sequence, constraint and column. Existing allocated references must never be rewritten.
