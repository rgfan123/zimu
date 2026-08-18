ALTER TABLE app.import_batches
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN confirmed_by VARCHAR(128),
    ADD CONSTRAINT import_batches_confirmation_consistency CHECK (
        (confirmed_at IS NULL AND confirmed_by IS NULL)
        OR (batch_type='SOURCE_ORDER' AND confirmed_at IS NOT NULL AND btrim(confirmed_by) <> '')
    );

CREATE UNIQUE INDEX uq_source_return_final_per_batch
    ON app.source_return_exports(import_batch_id)
    WHERE is_final;
