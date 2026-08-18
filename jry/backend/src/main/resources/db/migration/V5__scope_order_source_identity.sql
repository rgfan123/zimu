ALTER TABLE app.orders
    DROP CONSTRAINT orders_source_channel_source_ref_key;

ALTER TABLE app.orders
    ADD CONSTRAINT uq_orders_scope_source_ref
        UNIQUE (data_scope, source_channel, source_ref);
