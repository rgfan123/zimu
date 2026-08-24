-- Issue #113: Shipment-scoped source-platform sync projection, durable intent and
-- bidirectional exclusion with the existing SourceReturnExport fallback path.

ALTER TABLE app.shipment_syncs
    ADD COLUMN intent_key VARCHAR(255),
    ADD COLUMN platform_intent_key VARCHAR(255),
    ADD COLUMN check_hash CHAR(64),
    ADD COLUMN artifact_hash CHAR(64),
    ADD COLUMN source_line_ref VARCHAR(255),
    ADD COLUMN carrier_code VARCHAR(64),
    ADD COLUMN tracking_number VARCHAR(128),
    ADD COLUMN intent_started_at TIMESTAMPTZ,
    ADD COLUMN effect_started_at TIMESTAMPTZ,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE app.shipment_syncs
    DROP CONSTRAINT shipment_syncs_sync_status_check,
    ADD CONSTRAINT shipment_syncs_sync_status_check CHECK (
        sync_status IN (
            'PENDING',
            'SYNCING',
            'SYNCED',
            'SYNC_FAILED',
            'RECONCILIATION_REQUIRED'
        )
    ),
    ADD CONSTRAINT shipment_syncs_intent_key_not_blank CHECK (
        intent_key IS NULL OR btrim(intent_key) <> ''
    ),
    ADD CONSTRAINT shipment_syncs_platform_intent_key_not_blank CHECK (
        platform_intent_key IS NULL OR btrim(platform_intent_key) <> ''
    ),
    ADD CONSTRAINT shipment_syncs_check_hash_format CHECK (
        check_hash IS NULL OR check_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT shipment_syncs_artifact_hash_format CHECK (
        artifact_hash IS NULL OR artifact_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT shipment_syncs_source_line_ref_not_blank CHECK (
        source_line_ref IS NULL OR btrim(source_line_ref) <> ''
    ),
    ADD CONSTRAINT shipment_syncs_carrier_code_not_blank CHECK (
        carrier_code IS NULL OR btrim(carrier_code) <> ''
    ),
    ADD CONSTRAINT shipment_syncs_tracking_number_not_blank CHECK (
        tracking_number IS NULL OR btrim(tracking_number) <> ''
    ),
    ADD CONSTRAINT shipment_syncs_lock_version_nonnegative CHECK (lock_version >= 0),
    ADD CONSTRAINT shipment_syncs_effect_order_check CHECK (
        effect_started_at IS NULL
        OR (intent_started_at IS NOT NULL AND effect_started_at >= intent_started_at)
    ),
    ADD CONSTRAINT shipment_syncs_syncing_intent_check CHECK (
        sync_status <> 'SYNCING'
        OR (
            intent_key IS NOT NULL
            AND check_hash IS NOT NULL
            AND artifact_hash IS NOT NULL
            AND source_line_ref IS NOT NULL
            AND carrier_code IS NOT NULL
            AND tracking_number IS NOT NULL
            AND intent_started_at IS NOT NULL
            AND attempt_count > 0
            AND (source_channel <> 'JUFUBAO' OR platform_intent_key IS NOT NULL)
        )
    ),
    ADD CONSTRAINT shipment_syncs_reconciliation_effect_check CHECK (
        sync_status <> 'RECONCILIATION_REQUIRED'
        OR (
            intent_key IS NOT NULL
            AND check_hash IS NOT NULL
            AND artifact_hash IS NOT NULL
            AND source_line_ref IS NOT NULL
            AND carrier_code IS NOT NULL
            AND tracking_number IS NOT NULL
            AND intent_started_at IS NOT NULL
            AND effect_started_at IS NOT NULL
            AND attempt_count > 0
            AND (source_channel <> 'JUFUBAO' OR platform_intent_key IS NOT NULL)
        )
    ),
    ADD CONSTRAINT shipment_syncs_pending_fresh_check CHECK (
        sync_status <> 'PENDING'
        OR (
            intent_key IS NULL
            AND platform_intent_key IS NULL
            AND check_hash IS NULL
            AND artifact_hash IS NULL
            AND source_line_ref IS NULL
            AND carrier_code IS NULL
            AND tracking_number IS NULL
            AND intent_started_at IS NULL
            AND effect_started_at IS NULL
        )
    );

CREATE UNIQUE INDEX uq_shipment_syncs_intent
    ON app.shipment_syncs(source_channel, intent_key)
    WHERE intent_key IS NOT NULL;

CREATE UNIQUE INDEX uq_shipment_syncs_platform_intent
    ON app.shipment_syncs(source_channel, platform_intent_key)
    WHERE platform_intent_key IS NOT NULL;

-- V1 made generated source-return facts append-only. V34 later added a mutable push projection,
-- but the old trigger still rejected every UPDATE. Replace it with a narrow lifecycle guard:
-- generation facts stay immutable; only one legal push-status transition may update push fields.
CREATE FUNCTION app.guard_source_return_export_push_update() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'source return exports are append-only';
    END IF;

    IF ROW(
        NEW.id,
        NEW.import_batch_id,
        NEW.version_no,
        NEW.is_final,
        NEW.template_version,
        NEW.tracking_cutoff_at,
        NEW.file_ref,
        NEW.file_sha256,
        NEW.generated_by,
        NEW.generated_at,
        NEW.generated_from_tracking_batch_id
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.import_batch_id,
        OLD.version_no,
        OLD.is_final,
        OLD.template_version,
        OLD.tracking_cutoff_at,
        OLD.file_ref,
        OLD.file_sha256,
        OLD.generated_by,
        OLD.generated_at,
        OLD.generated_from_tracking_batch_id
    ) THEN
        RAISE EXCEPTION 'source return export generation facts are immutable';
    END IF;

    IF NEW.push_status IS NOT DISTINCT FROM OLD.push_status THEN
        IF ROW(
            NEW.push_started_at,
            NEW.pushed_at,
            NEW.pushed_by,
            NEW.push_platform_ref,
            NEW.push_error
        ) IS DISTINCT FROM ROW(
            OLD.push_started_at,
            OLD.pushed_at,
            OLD.pushed_by,
            OLD.push_platform_ref,
            OLD.push_error
        ) THEN
            RAISE EXCEPTION 'source return push fields require a status transition';
        END IF;
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.push_status IN ('NOT_PUSHED', 'FAILED') AND NEW.push_status = 'PUSHING')
        OR (OLD.push_status = 'PUSHING' AND NEW.push_status IN ('SUCCESS', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'invalid source return push transition: % -> %',
            OLD.push_status, NEW.push_status;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER trg_source_return_export_append_only ON app.source_return_exports;

CREATE TRIGGER trg_source_return_export_append_only
BEFORE UPDATE OR DELETE ON app.source_return_exports
FOR EACH ROW EXECUTE FUNCTION app.guard_source_return_export_push_update();

-- Online claim side of the mutex. Lock the Shipment row first so a concurrent parent-export
-- transition must wait, then reject both an in-flight and an already successful file fallback.
CREATE FUNCTION app.guard_shipment_source_sync_mutex() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sync_status <> 'SYNCING' THEN
        RETURN NEW;
    END IF;

    PERFORM 1
    FROM app.shipments
    WHERE id = NEW.shipment_id
    FOR UPDATE;

    IF EXISTS (
        SELECT 1
        FROM app.source_return_export_items item
        JOIN app.source_return_exports export
          ON export.id = item.source_return_export_id
        JOIN app.v_import_batch_effective_source source
          ON source.import_batch_id = export.import_batch_id
        WHERE item.shipment_id = NEW.shipment_id
          AND source.effective_source_channel = NEW.source_channel
          AND export.push_status IN ('PUSHING', 'SUCCESS')
    ) THEN
        RAISE EXCEPTION 'shipment source sync conflicts with active source return fallback';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_shipment_source_sync_mutex
BEFORE INSERT OR UPDATE OF sync_status, shipment_id, source_channel ON app.shipment_syncs
FOR EACH ROW EXECUTE FUNCTION app.guard_shipment_source_sync_mutex();

-- Serialize invalidation with a concurrent parent-export PUSHING claim on the same immutable row.
-- Whichever transaction gets the export row lock first wins; the other then observes the terminal fact.
CREATE OR REPLACE FUNCTION app.validate_source_return_invalidation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    export_batch_id BIGINT;
    correction_batch_id BIGINT;
    export_push_status VARCHAR(16);
BEGIN
    SELECT import_batch_id, push_status INTO STRICT export_batch_id, export_push_status
    FROM app.source_return_exports
    WHERE id=NEW.source_return_export_id
    FOR UPDATE;

    SELECT import_batch_id INTO STRICT correction_batch_id
    FROM app.source_attribution_corrections WHERE id=NEW.source_attribution_correction_id;

    IF export_batch_id <> correction_batch_id THEN
        RAISE EXCEPTION 'source return invalidation must use a correction from the same import batch';
    END IF;
    IF export_push_status IN ('PUSHING', 'SUCCESS') THEN
        RAISE EXCEPTION 'a pushing or pushed source return export cannot be invalidated';
    END IF;
    RETURN NEW;
END;
$$;

-- File-fallback side of the mutex. Resolve every Shipment from immutable export items, acquire
-- Shipment row locks in stable id order, then check the shared shipment_syncs projection.
CREATE FUNCTION app.guard_source_return_fallback_mutex() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    source_channel_value VARCHAR(32);
    shipment_id_value BIGINT;
BEGIN
    IF NEW.push_status <> 'PUSHING'
       OR OLD.push_status IS NOT DISTINCT FROM 'PUSHING' THEN
        RETURN NEW;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM app.source_return_export_invalidations invalidation
        WHERE invalidation.source_return_export_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'invalidated source return export cannot be pushed';
    END IF;

    SELECT effective_source_channel
    INTO STRICT source_channel_value
    FROM app.v_import_batch_effective_source
    WHERE import_batch_id = NEW.import_batch_id;

    FOR shipment_id_value IN
        SELECT DISTINCT item.shipment_id
        FROM app.source_return_export_items item
        WHERE item.source_return_export_id = NEW.id
          AND item.shipment_id IS NOT NULL
        ORDER BY item.shipment_id
    LOOP
        PERFORM 1
        FROM app.shipments
        WHERE id = shipment_id_value
        FOR UPDATE;
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM app.source_return_export_items item
        JOIN app.shipment_syncs sync
          ON sync.shipment_id = item.shipment_id
         AND sync.source_channel = source_channel_value
        WHERE item.source_return_export_id = NEW.id
          AND sync.sync_status IN ('SYNCING', 'SYNCED', 'RECONCILIATION_REQUIRED')
    ) THEN
        RAISE EXCEPTION 'source return fallback conflicts with active shipment source sync';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_source_return_export_push_mutex
BEFORE UPDATE OF push_status ON app.source_return_exports
FOR EACH ROW EXECUTE FUNCTION app.guard_source_return_fallback_mutex();

-- Advisory-only Agent definition. It has one read-only tool, no write capability and no blanket
-- PII guard exemption; deterministic application checks remain authoritative.
INSERT INTO app.agent_definitions (
    agent_slug,
    name,
    description,
    system_prompt,
    prompt_version,
    model_ref,
    input_format,
    enabled,
    version,
    status,
    activated_by,
    activated_at,
    allow_write,
    guard_exemptions,
    output_schema,
    tool_whitelist
) VALUES (
    'source-sync-reviewer',
    '来源回传复核 Agent',
    '只读复核单个 Shipment 的来源回传确定性检查结果，仅提供人工决策建议。',
    $prompt$你是来源回传复核 Agent。你是只读、建议型复核者，只能调用 check_shipment_source_sync 查看确定性检查结果。

规则：
1. 只陈述工具返回的收货、数量、承运商、运单与平台状态差异，不猜测或补造事实；
2. 确定性 blocker 永远优先，你的建议不得覆盖 blocker；
3. 不得执行回传，不得对账，不得调用任何写工具，也不得把建议表述为人工授权；
4. 信息不足或守卫拒绝时明确转人工，不尝试规避守卫。
$prompt$,
    'source-sync-reviewer-v1',
    'app.agent',
    'STRUCTURED_JSON',
    true,
    1,
    'active',
    'system:v53-seed',
    CURRENT_TIMESTAMP,
    false,
    '[]'::jsonb,
    NULL,
    '["check_shipment_source_sync"]'::jsonb
);
