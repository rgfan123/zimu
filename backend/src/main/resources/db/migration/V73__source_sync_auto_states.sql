-- 自动在线回传调度状态与业务回传状态分离：文件渠道不是 SYNCED，也不是失败。
CREATE TABLE app.source_sync_auto_states (
    shipment_id        BIGINT NOT NULL REFERENCES app.shipments(id) ON DELETE RESTRICT,
    source_channel     VARCHAR(32) NOT NULL CHECK (
        source_channel IN (
            'CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI',
            'DAZHE', 'WANGQI', 'WANQI', 'WECOM'
        )
    ),
    disposition        VARCHAR(32) NOT NULL CHECK (
        disposition IN ('PENDING', 'NOT_APPLICABLE', 'RETRY_WAIT')
    ),
    reason_code        VARCHAR(64) NOT NULL CHECK (btrim(reason_code) <> ''),
    attempt_count      INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at    TIMESTAMPTZ,
    lease_owner        VARCHAR(128),
    lease_until        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (shipment_id, source_channel),
    CHECK (
        (disposition = 'NOT_APPLICABLE' AND next_attempt_at IS NULL)
        OR (disposition IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at IS NOT NULL)
    ),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK (lease_owner IS NULL OR btrim(lease_owner) <> '')
);

CREATE INDEX idx_source_sync_auto_retry_due
    ON app.source_sync_auto_states(next_attempt_at, shipment_id)
    WHERE disposition IN ('PENDING', 'RETRY_WAIT');

COMMENT ON TABLE app.source_sync_auto_states IS
    '在线自动回传资格、租约与退避；NOT_APPLICABLE 表示该渠道继续走文件回传';
