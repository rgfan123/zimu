-- ---------------------------------------------------------------------------
-- 京东云仓履约闭环：Shipment 级已确认结构化收货地址（jd-fulfillment-loop/02）
-- ---------------------------------------------------------------------------
-- 京东建单要求省/市/区县/乡镇（要求时）与详细地址来自已确认的业务数据；
-- shipments.receiver_address_snapshot 是自由文本快照，只用于人工修正提示，
-- 系统不得自动猜测拆分地址层级（spec Implementation Decision: Receiver ... must be
-- structured and confirmed business data）。
--
-- jd_receiver_* 由运营人员通过 PUT /api/v1/shipments/{id}/jd-receiver-address
-- 人工确认后写入；预览/建单/库存判定要求这些列齐全，否则阻断。
-- ---------------------------------------------------------------------------

ALTER TABLE app.shipments
    ADD COLUMN lock_version               BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    ADD COLUMN jd_receiver_province      VARCHAR(64),
    ADD COLUMN jd_receiver_city          VARCHAR(64),
    ADD COLUMN jd_receiver_county        VARCHAR(64),
    ADD COLUMN jd_receiver_town          VARCHAR(64),
    ADD COLUMN jd_receiver_detail_address VARCHAR(255),
    ADD COLUMN jd_receiver_confirmed_by  VARCHAR(128),
    ADD COLUMN jd_receiver_confirmed_at  TIMESTAMPTZ;

ALTER TABLE app.shipments
    ADD CONSTRAINT shipments_jd_receiver_confirmation_consistency CHECK (
        (
            jd_receiver_confirmed_at IS NULL
            AND num_nonnulls(
                jd_receiver_province, jd_receiver_city, jd_receiver_county, jd_receiver_town,
                jd_receiver_detail_address, jd_receiver_confirmed_by) = 0
        )
        OR (
            jd_receiver_confirmed_at IS NOT NULL
            AND jd_receiver_confirmed_by IS NOT NULL AND btrim(jd_receiver_confirmed_by) <> ''
            AND jd_receiver_province IS NOT NULL AND btrim(jd_receiver_province) <> ''
            AND jd_receiver_city IS NOT NULL AND btrim(jd_receiver_city) <> ''
            AND jd_receiver_county IS NOT NULL AND btrim(jd_receiver_county) <> ''
            AND jd_receiver_detail_address IS NOT NULL AND btrim(jd_receiver_detail_address) <> ''
            AND (jd_receiver_town IS NULL OR btrim(jd_receiver_town) <> '')
        )
    );

COMMENT ON COLUMN app.shipments.jd_receiver_province IS '京东建单用已确认省份（人工确认，不自动猜测）';
COMMENT ON COLUMN app.shipments.lock_version IS '发货批次写操作乐观锁版本';
COMMENT ON COLUMN app.shipments.jd_receiver_city IS '京东建单用已确认城市（人工确认，不自动猜测）';
COMMENT ON COLUMN app.shipments.jd_receiver_county IS '京东建单用已确认区县（人工确认，不自动猜测）';
COMMENT ON COLUMN app.shipments.jd_receiver_town IS '京东建单用已确认乡镇（可选，人工确认）';
COMMENT ON COLUMN app.shipments.jd_receiver_detail_address IS '京东建单用已确认详细地址（人工确认）';
COMMENT ON COLUMN app.shipments.jd_receiver_confirmed_by IS '结构化地址确认操作人';
COMMENT ON COLUMN app.shipments.jd_receiver_confirmed_at IS '结构化地址确认时间';
