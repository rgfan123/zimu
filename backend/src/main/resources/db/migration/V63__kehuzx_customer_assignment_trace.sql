-- Issue #131: trace the immutable deterministic payload used by a customer Assignment.

ALTER TABLE app.business_followup_assignments
    ADD COLUMN payload_hash VARCHAR(64),
    ADD CONSTRAINT business_followup_assignment_payload_hash_check CHECK (
        payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$'
    );

CREATE INDEX idx_business_followup_assignments_request
    ON app.business_followup_assignments(request_id)
    WHERE request_id IS NOT NULL;
