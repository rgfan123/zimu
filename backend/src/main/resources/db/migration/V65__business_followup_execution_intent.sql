-- Explicit, human-authored intent for sample/formal Kehuzx writes (Issues #132/#133).
-- CUSTOMER remains the default so all pre-existing and legacy create calls retain semantics.

ALTER TABLE app.business_followups
    ADD COLUMN business_kind VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN execution_plan JSONB,
    ADD CONSTRAINT business_followups_execution_intent_check CHECK (
        (business_kind = 'CUSTOMER' AND execution_plan IS NULL)
        OR (
            business_kind IN ('SAMPLE', 'FORMAL')
            AND execution_plan IS NOT NULL
            AND jsonb_typeof(execution_plan) = 'object'
        )
    );
