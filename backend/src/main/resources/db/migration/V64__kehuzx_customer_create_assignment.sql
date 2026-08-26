-- Issue #131: distinguish read-only customer links from Approval-attributed customer creates.

ALTER TABLE app.business_followup_assignments
    DROP CONSTRAINT business_followup_assignments_task_type_check,
    DROP CONSTRAINT business_followup_assignment_outcome_check,
    DROP CONSTRAINT business_followup_assignment_success_result_check;

ALTER TABLE app.business_followup_assignments
    ADD CONSTRAINT business_followup_assignments_task_type_check CHECK (
        task_type IN ('KEHUZX_CUSTOMER_LINK', 'KEHUZX_CUSTOMER_CREATE')
    ),
    ADD CONSTRAINT business_followup_assignment_outcome_check CHECK (
        (status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL
            AND result_code IS NULL AND external_entity_type IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL
            AND result_code IS NULL
            AND (task_type = 'KEHUZX_CUSTOMER_LINK' OR request_id IS NOT NULL))
        OR (status = 'WAITING_HUMAN' AND started_at IS NOT NULL AND completed_at IS NULL
            AND result_code IS NOT NULL)
        OR (status = 'SUCCEEDED'
            AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND result_code IS NOT NULL
            AND (task_type = 'KEHUZX_CUSTOMER_LINK' OR request_id IS NOT NULL))
        OR (status = 'RECONCILIATION_REQUIRED'
            AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND result_code IS NOT NULL AND request_id IS NOT NULL)
        OR (status = 'FAILED'
            AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND result_code IS NOT NULL)
    ),
    ADD CONSTRAINT business_followup_assignment_success_result_check CHECK (
        status <> 'SUCCEEDED'
        OR (task_type = 'KEHUZX_CUSTOMER_LINK'
            AND result_code = 'KEHUZX_CUSTOMER_LINKED'
            AND external_entity_type = 'KEHUZX_CUSTOMER'
            AND external_entity_id IS NOT NULL)
        OR (task_type = 'KEHUZX_CUSTOMER_CREATE'
            AND result_code = 'KEHUZX_CUSTOMER_CREATED'
            AND external_entity_type = 'KEHUZX_CUSTOMER'
            AND external_entity_id IS NOT NULL)
    );
