package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.sync.SourceShipmentSyncService;
import cn.zimu.fulfillment.connector.sync.SourceSyncBlocker;
import cn.zimu.fulfillment.connector.sync.SourceSyncCheck;
import cn.zimu.fulfillment.connector.sync.SourceSyncFacts;
import cn.zimu.fulfillment.connector.sync.SourceSyncProjection;
import cn.zimu.fulfillment.connector.sync.SourceSyncStatus;
import cn.zimu.fulfillment.fulfillment.FulfillmentReadService;
import cn.zimu.fulfillment.inventory.InventoryDetailsService;
import cn.zimu.fulfillment.inventory.InventoryOverviewService;
import cn.zimu.fulfillment.masterdata.MasterDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpDomainReadToolsSourceSyncRedactionTest {

    private static final String RECEIVER_NAME = "测试隐私姓名-阿星";
    private static final String RECEIVER_PHONE = "13900001111";
    private static final String RECEIVER_ADDRESS = "北京市朝阳区测试路 88 号 3 单元 1201";
    private static final String TRACKING_NUMBER = "SF-SECRET-13900001111";

    @Test
    void sourceSyncProjectionKeepsDecisionFactsButNeverReturnsPiiOrRawPlatformText() {
        SourceSyncFacts internal = new SourceSyncFacts(
                42L,
                24L,
                SourceChannel.JUFUBAO,
                "SOURCE-ORDER-CONFIDENTIAL",
                "SOURCE-LINE-CONFIDENTIAL",
                RECEIVER_NAME,
                RECEIVER_PHONE,
                RECEIVER_ADDRESS,
                new BigDecimal("3"),
                new BigDecimal("3"),
                new BigDecimal("30.000"),
                "FULLY_FULFILLED",
                "SF",
                "顺丰速运",
                "PLATFORM-CARRIER-SECRET",
                TRACKING_NUMBER);
        SourcePlatformCheckResult platform = new SourcePlatformCheckResult(
                true,
                "UNTRUSTED-" + RECEIVER_PHONE,
                "平台自由文本包含 " + RECEIVER_ADDRESS,
                "STATE-" + RECEIVER_NAME,
                false,
                SourcePlatformCheckResult.AddressStatus.CLEAR,
                RECEIVER_NAME,
                RECEIVER_PHONE,
                RECEIVER_ADDRESS,
                new BigDecimal("3"),
                true);
        SourceSyncCheck check = new SourceSyncCheck(
                42L,
                false,
                "safe-check-hash",
                "safe-artifact-hash",
                internal,
                platform,
                List.of(
                        new SourceSyncBlocker(
                                "SOURCE_PLATFORM_CARRIER_UNMAPPED",
                                "carrier",
                                "内部固定提示"),
                        new SourceSyncBlocker(
                                RECEIVER_NAME,
                                "receiver",
                                "不可信自由文本 " + RECEIVER_PHONE + " " + RECEIVER_ADDRESS)),
                new SourceSyncProjection(
                        SourceSyncStatus.PENDING,
                        2,
                        7L,
                        "ERROR-" + RECEIVER_PHONE,
                        "历史错误包含 " + RECEIVER_NAME + " " + RECEIVER_ADDRESS,
                        OffsetDateTime.parse("2026-08-24T12:34:56+08:00")));

        SourceShipmentSyncService sourceSync = mock(SourceShipmentSyncService.class);
        when(sourceSync.check(eq(42L), any(), eq(AuditActorType.AGENT))).thenReturn(check);
        McpDomainReadTools tools = new McpDomainReadTools(
                mock(FulfillmentReadService.class),
                mock(InventoryOverviewService.class),
                mock(InventoryDetailsService.class),
                mock(MasterDataService.class),
                sourceSync,
                mock(cn.zimu.fulfillment.batch.ImportBatchProgressService.class),
                new ObjectMapper());
        McpTool tool = tools.tools().stream()
                .filter(candidate -> "check_shipment_source_sync".equals(candidate.name()))
                .findFirst()
                .orElseThrow();

        JsonNode result = tool.invoke(
                new McpRequestContext("request-1", "trace-1", "source-sync-reviewer"),
                java.util.Map.of("shipment_id", "42"));

        assertThat(result.get("shipment_id").asLong()).isEqualTo(42L);
        assertThat(result.get("source_channel").asText()).isEqualTo("JUFUBAO");
        assertThat(result.get("receiver_comparison").get("name_matches").asBoolean()).isTrue();
        assertThat(result.get("receiver_comparison").get("phone_matches").asBoolean()).isTrue();
        assertThat(result.get("receiver_comparison").get("address_matches").asBoolean()).isTrue();
        assertThat(result.get("quantity_comparison").get("matches").asBoolean()).isTrue();
        assertThat(result.get("quantity_comparison").get("shipped_source_quantity").decimalValue())
                .isEqualByComparingTo("3");
        assertThat(result.get("platform_summary").get("address_status").asText()).isEqualTo("CLEAR");
        assertThat(result.get("shipment_summary").get("tracking_present").asBoolean()).isTrue();
        assertThat(result.get("sync_projection").get("status").asText()).isEqualTo("PENDING");
        assertThat(result.get("blocker_codes")).hasSize(2);
        assertThat(result.get("blocker_codes").toString())
                .contains("SOURCE_PLATFORM_CARRIER_UNMAPPED", "SOURCE_SYNC_BLOCKED");
        assertThat(result.get("outcome_category").asText()).isEqualTo("BLOCKED");
        assertThat(result.get("next_action").asText()).isEqualTo("FIX_BLOCKERS_AND_RECHECK");
        assertThat(result.get("advisory").asBoolean()).isTrue();
        assertThat(result.get("write_allowed").asBoolean()).isFalse();
        assertThat(result.has("check_hash")).isFalse();
        assertThat(result.has("artifact_hash")).isFalse();

        String serialized = result.toString();
        assertThat(serialized)
                .doesNotContain(RECEIVER_NAME)
                .doesNotContain(RECEIVER_PHONE)
                .doesNotContain(RECEIVER_ADDRESS)
                .doesNotContain(TRACKING_NUMBER)
                .doesNotContain("SOURCE-ORDER-CONFIDENTIAL")
                .doesNotContain("SOURCE-LINE-CONFIDENTIAL")
                .doesNotContain("PLATFORM-CARRIER-SECRET")
                .doesNotContain("顺丰速运")
                .doesNotContain("receiver_name")
                .doesNotContain("receiver_phone")
                .doesNotContain("receiver_address")
                .doesNotContain("tracking_number")
                .doesNotContain("reconciliation_intent")
                .doesNotContain("last_error_message")
                .doesNotContain("platform_state")
                .doesNotContain("business_code")
                .doesNotContain("message");
    }

    @Test
    void sourceSyncProjectionDoesNotClaimMatchesWhenPlatformFactsAreUnavailable() {
        SourceSyncFacts internal = new SourceSyncFacts(
                42L,
                24L,
                SourceChannel.CAISHIXIAN,
                null,
                null,
                RECEIVER_NAME,
                RECEIVER_PHONE,
                RECEIVER_ADDRESS,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "FULLY_FULFILLED",
                null,
                null,
                null,
                TRACKING_NUMBER);
        SourceSyncCheck check = new SourceSyncCheck(
                42L,
                false,
                "safe-check-hash",
                "safe-artifact-hash",
                internal,
                SourcePlatformCheckResult.unavailable(SourceChannel.CAISHIXIAN),
                List.of(new SourceSyncBlocker(
                        "SOURCE_PLATFORM_CHECK_FAILED", "platform", "平台不可用")),
                new SourceSyncProjection(SourceSyncStatus.PENDING, 0, 0L, null, null, null));

        JsonNode result = McpDomainReadTools.safeSourceSyncProjection(check, new ObjectMapper());

        assertThat(result.get("receiver_comparison").get("all_match").asBoolean()).isFalse();
        assertThat(result.get("quantity_comparison").get("matches").asBoolean()).isFalse();
        assertThat(result.get("platform_summary").get("available").asBoolean()).isFalse();
        assertThat(result.get("outcome_category").asText()).isEqualTo("BLOCKED");
        assertThat(result.get("next_action").asText()).isEqualTo("FIX_BLOCKERS_AND_RECHECK");
        assertThat(result.toString())
                .doesNotContain(RECEIVER_NAME)
                .doesNotContain(RECEIVER_PHONE)
                .doesNotContain(RECEIVER_ADDRESS)
                .doesNotContain(TRACKING_NUMBER);
    }

    @Test
    void sourceSyncProjectionNamesAnExplicitPlatformRejectionWithoutReturningTheRawCode() {
        SourceSyncFacts internal = new SourceSyncFacts(
                43L,
                25L,
                SourceChannel.JUFUBAO,
                "source-order",
                "source-line",
                RECEIVER_NAME,
                RECEIVER_PHONE,
                RECEIVER_ADDRESS,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "FULLY_FULFILLED",
                "JD",
                "京东物流",
                "京东物流",
                TRACKING_NUMBER);
        SourceSyncCheck check = new SourceSyncCheck(
                43L,
                false,
                "safe-check-hash",
                "safe-artifact-hash",
                internal,
                SourcePlatformCheckResult.unavailable(SourceChannel.JUFUBAO),
                List.of(),
                new SourceSyncProjection(
                        SourceSyncStatus.SYNC_FAILED,
                        1,
                        2L,
                        "JUFUBAO_SHIPMENT_REJECTED",
                        "平台原始拒绝文本",
                        null));

        JsonNode result = McpDomainReadTools.safeSourceSyncProjection(check, new ObjectMapper());

        assertThat(result.get("outcome_category").asText()).isEqualTo("PLATFORM_REJECTED");
        assertThat(result.get("next_action").asText()).isEqualTo("FIX_AND_RECHECK");
        assertThat(result.toString())
                .doesNotContain("JUFUBAO_SHIPMENT_REJECTED")
                .doesNotContain("平台原始拒绝文本");
    }
}
