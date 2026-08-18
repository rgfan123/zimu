ALTER TABLE app.source_return_exports
    ADD COLUMN IF NOT EXISTS generated_from_tracking_batch_id BIGINT
        REFERENCES app.import_batches(id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_source_return_exports_tracking_batch
    ON app.source_return_exports(generated_from_tracking_batch_id, id);
