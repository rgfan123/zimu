-- Ticket 09: runtime-maintained, versioned carrier-prefix authority.
CREATE TABLE app.carrier_prefix_mapping_sets (
    singleton_id       SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    lock_version       BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    updated_by         VARCHAR(128) NOT NULL CHECK (btrim(updated_by) <> ''),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app.carrier_prefix_mappings (
    prefix              VARCHAR(16) PRIMARY KEY,
    carrier_code        VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (prefix ~ '^[A-Z]{1,16}$'),
    CHECK (carrier_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

INSERT INTO app.carrier_prefix_mapping_sets (singleton_id, lock_version, updated_by)
VALUES (1, 0, 'migration-v21');

-- These are the two formerly effective application.yml defaults. From V21 onward the
-- database rows are authoritative; environment mappings are deliberately not consulted.
INSERT INTO app.carrier_prefix_mappings (prefix, carrier_code) VALUES
    ('JD', 'JD'),
    ('SF', 'SF_EXPRESS');
