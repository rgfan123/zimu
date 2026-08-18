ALTER TABLE app.provider_stock_snapshots
    ADD COLUMN quantity_unit VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN source_type VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN';

-- Historical snapshots are append-only facts. Their raw payload is not a sufficiently
-- strong unit contract, so this migration deliberately leaves every existing row UNKNOWN.
-- All current producers write quantity_unit/source_type explicitly on INSERT.

ALTER TABLE app.provider_stock_snapshots
    ADD CONSTRAINT provider_stock_snapshots_quantity_unit_nonblank
        CHECK (btrim(quantity_unit) <> ''),
    ADD CONSTRAINT provider_stock_snapshots_source_type_nonblank
        CHECK (btrim(source_type) <> '');

COMMENT ON COLUMN app.provider_stock_snapshots.quantity_unit IS
    'Quantity unit of stock_num/usable_num. JD live checks use JD_PIECE; normalized observations use INTERNAL_UNIT; unprovable legacy rows remain UNKNOWN.';
COMMENT ON COLUMN app.provider_stock_snapshots.source_type IS
    'Stable producer classification for operator-facing provenance; unprovable legacy rows remain UNKNOWN.';
