package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 发货批次生命周期词汇表的唯一裁决点。这里钉死的是「哪些状态回答哪个业务问题」，
 * 迁移前散在 4 个包里的判定必须与本表逐位一致：未知/null 一律落到否定分支，
 * 与迁移前各调用点的 {@code equals} 语义相同（数据库 CHECK 保证正常数据只有四个取值）。
 */
class ShipmentStatusTest {

    @Test
    void vocabularyMatchesDatabaseCheckConstraint() {
        assertThat(ShipmentStatus.values())
                .as("V1__baseline.sql CHECK (shipment_status IN ('CREATED','SHIPPED','FAILED','DELIVERED'))")
                .containsExactly(
                        ShipmentStatus.CREATED,
                        ShipmentStatus.SHIPPED,
                        ShipmentStatus.FAILED,
                        ShipmentStatus.DELIVERED);
    }

    @Test
    void parseAcceptsDatabaseValuesAndRejectsAnythingElse() {
        assertThat(ShipmentStatus.parse("SHIPPED")).contains(ShipmentStatus.SHIPPED);
        assertThat(ShipmentStatus.parse("DELIVERED")).contains(ShipmentStatus.DELIVERED);
        assertThat(ShipmentStatus.parse(null)).isEmpty();
        assertThat(ShipmentStatus.parse("")).isEmpty();
        assertThat(ShipmentStatus.parse("shipped")).as("数据库取值大小写敏感，不做宽松解析").isEmpty();
        assertThat(ShipmentStatus.parse("PARTIAL"))
                .as("PARTIAL 是运单导入文件「结果」列的词汇，不是发货批次状态")
                .isEmpty();
    }

    /** 已形成发货事实：来源回传前置、对账语义、看板与进度「已发货」口径共用。 */
    @Test
    void shippedFactCoversShippedAndDelivered() {
        assertThat(ShipmentStatus.isShipped("SHIPPED")).isTrue();
        assertThat(ShipmentStatus.isShipped("DELIVERED")).isTrue();
        assertThat(ShipmentStatus.isShipped("CREATED")).isFalse();
        assertThat(ShipmentStatus.isShipped("FAILED")).isFalse();
        assertThat(ShipmentStatus.isShipped(null)).isFalse();
        assertThat(ShipmentStatus.isShipped("UNKNOWN")).isFalse();
    }

    @Test
    void failedFactIsOnlyTheFailedStatus() {
        assertThat(ShipmentStatus.isFailed("FAILED")).isTrue();
        assertThat(ShipmentStatus.isFailed("CREATED")).isFalse();
        assertThat(ShipmentStatus.isFailed("SHIPPED")).isFalse();
        assertThat(ShipmentStatus.isFailed(null)).isFalse();
    }

    /**
     * 回填运单允许 CREATED 与 SHIPPED —— 这与「已发货事实」是不同的问题，
     * 迁移前 ShipmentJdTrackingBackfillService 的写法并非 bug，不得被合并成同一条规则。
     */
    @Test
    void trackingBackfillAcceptsCreatedAndShippedOnly() {
        assertThat(ShipmentStatus.acceptsTrackingBackfill("CREATED")).isTrue();
        assertThat(ShipmentStatus.acceptsTrackingBackfill("SHIPPED")).isTrue();
        assertThat(ShipmentStatus.acceptsTrackingBackfill("DELIVERED")).isFalse();
        assertThat(ShipmentStatus.acceptsTrackingBackfill("FAILED")).isFalse();
        assertThat(ShipmentStatus.acceptsTrackingBackfill(null)).isFalse();
    }

    /** 京东出库建单只允许 CREATED（迁移前 Preparer 的 SHIPMENT_STATUS_CREATED 常量）。 */
    @Test
    void outboundSubmitAcceptsCreatedOnly() {
        assertThat(ShipmentStatus.acceptsOutboundSubmit("CREATED")).isTrue();
        assertThat(ShipmentStatus.acceptsOutboundSubmit("SHIPPED")).isFalse();
        assertThat(ShipmentStatus.acceptsOutboundSubmit("DELIVERED")).isFalse();
        assertThat(ShipmentStatus.acceptsOutboundSubmit("FAILED")).isFalse();
        assertThat(ShipmentStatus.acceptsOutboundSubmit(null)).isFalse();
    }

    /** 迁移前后逐位等价：四个状态 × 四个问题的完整真值表。 */
    @Test
    void fullTruthTableIsPinned() {
        record Row(String status, boolean shipped, boolean failed, boolean backfill, boolean submit) {}
        for (Row row : java.util.List.of(
                new Row("CREATED", false, false, true, true),
                new Row("SHIPPED", true, false, true, false),
                new Row("FAILED", false, true, false, false),
                new Row("DELIVERED", true, false, false, false))) {
            assertThat(ShipmentStatus.isShipped(row.status())).as("%s.shipped", row.status()).isEqualTo(row.shipped());
            assertThat(ShipmentStatus.isFailed(row.status())).as("%s.failed", row.status()).isEqualTo(row.failed());
            assertThat(ShipmentStatus.acceptsTrackingBackfill(row.status()))
                    .as("%s.backfill", row.status()).isEqualTo(row.backfill());
            assertThat(ShipmentStatus.acceptsOutboundSubmit(row.status()))
                    .as("%s.submit", row.status()).isEqualTo(row.submit());
            assertThat(ShipmentStatus.parse(row.status())).as("%s.parse", row.status()).isNotEmpty();
        }
        assertThat(Optional.<ShipmentStatus>empty()).isEmpty();
    }
}
