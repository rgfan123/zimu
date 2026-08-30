package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.CreateOperationalAlertCommand;
import cn.zimu.fulfillment.order.OperationalAlertService;
import cn.zimu.fulfillment.order.OperationalAlertSeverity;
import cn.zimu.fulfillment.sku.JdSkuMappingCheckService.CategoryDiff;
import cn.zimu.fulfillment.sku.JdSkuMappingCheckService.DiffItem;
import cn.zimu.fulfillment.sku.JdSkuMappingCheckService.JdSkuMappingCheckResult;
import cn.zimu.fulfillment.sku.JdSkuMappingCheckService.SkuMappingRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdSkuMappingCheckServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final JdGoodsReadOnlyVerifier goodsVerifier = mock(JdGoodsReadOnlyVerifier.class);
    private final OperationalAlertService alerts = mock(OperationalAlertService.class);

    private JdSkuMappingCheckService service() {
        return service("REAL");
    }

    private JdSkuMappingCheckService service(String clientMode) {
        return new JdSkuMappingCheckService(
                jdbc, idempotency, audits, goodsVerifier, alerts, clientMode);
    }

    @SuppressWarnings("unchecked")
    private void stubProviderAndMappings(List<SkuMappingRow> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("provider_code")) {
                        return List.of(1L);
                    }
                    return rows;
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    private void stubRunIdempotency() {
        when(idempotency.execute(anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    IdempotencyService.Work<JdSkuMappingCheckResult> work = invocation.getArgument(4);
                    return IdempotentResult.executed(work.execute(), 200);
                });
    }

    private JdSkuMappingCheckResult run(String idempotencyKey) {
        return service().run(idempotencyKey, new CommandContext("req-1", "trace-1", "ops-user")).result();
    }

    private JdSkuMappingCheckResult run(String clientMode, String idempotencyKey) {
        return service(clientMode)
                .run(idempotencyKey, new CommandContext("req-1", "trace-1", "ops-user"))
                .result();
    }

    private JdGoodsReadOnlyVerifier.Verification enabledGoods(String goodsNo, String goodsName) {
        // enableFlag=2 才是京东官方的「已启用」。
        return JdGoodsReadOnlyVerifier.Verification.found(
                "1000", "req-1", goodsNo, "ERP-" + goodsNo, goodsName, 2);
    }

    private SkuMappingRow row(long id, String providerSkuCode, String skuCode, String specification) {
        return new SkuMappingRow(id, 100 + id, providerSkuCode, null, null, skuCode, specification);
    }

    @Test
    void consistentMappingsProduceNoCategoryDiffsAndAuditConsistent() {
        stubProviderAndMappings(List.of(row(1, "JD-SKU-000001", "SKU-JD-000001", "500g/盒")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(enabledGoods("JD-SKU-000001", "子牧羊小腿 500g/盒"));
        stubRunIdempotency();

        JdSkuMappingCheckResult result = run("key-12345678");

        assertThat(result.checkRunNo()).startsWith("CHK-");
        assertThat(result.providerCode()).isEqualTo("JD");
        assertThat(result.checkedCount()).isEqualTo(1);
        assertThat(result.categories()).isEmpty();
        verify(alerts, never()).create(any(), anyString(), any());

        ArgumentCaptor<AuditLogService.AuditCommand> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(captor.capture());
        assertThat(auditField(captor.getValue(), "operation")).isEqualTo("jd_sku_mapping.check");
        assertThat(auditField(captor.getValue(), "businessCode")).isEqualTo("JD_SKU_MAPPING_CONSISTENT");
        assertThat(auditField(captor.getValue(), "operator")).isEqualTo("ops-user");
        verify(jdbc).update(contains("jd_goods_verification"), any(Object[].class));
    }

    @Test
    void mockClientResultNeverBecomesPersistentRealGoodsEvidence() {
        stubProviderAndMappings(List.of(row(1, "JD-SKU-000001", "SKU-JD-000001", "500g/盒")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(enabledGoods("JD-SKU-000001", "子牧羊小腿 500g/盒"));
        stubRunIdempotency();

        assertThat(run("MOCK", "key-mock-evidence").categories()).isEmpty();

        verify(jdbc, never()).update(contains("jd_goods_verification"), any(Object[].class));
    }

    /** AuditCommand 的字段读取器是包私有（仅 common.audit 包内可见），测试通过反射断言审计内容。 */
    private static Object auditField(AuditLogService.AuditCommand command, String field) {
        try {
            java.lang.reflect.Field f = AuditLogService.AuditCommand.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(command);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void mappingMissingCollectsOneCategoryAndFallsBackToAuditChannelWithoutBusinessSubject() {
        stubProviderAndMappings(List.of(
                row(1, "MOCK-MISSING-001", "SKU-JD-000001", "500g/盒"),
                row(2, "MOCK-MISSING-002", "SKU-JD-000002", "500g/盒")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(JdGoodsReadOnlyVerifier.Verification.notFound("1000", "req-1"));
        stubRunIdempotency();

        JdSkuMappingCheckResult result = run("key-12345678");

        assertThat(result.checkedCount()).isEqualTo(2);
        assertThat(result.categories()).hasSize(1);
        CategoryDiff category = result.categories().getFirst();
        assertThat(category.category()).isEqualTo("MAPPING_MISSING");
        assertThat(category.message()).contains("2 个商品编码在京东查不到");
        assertThat(category.alertPersisted()).isFalse();
        assertThat(category.alertChannel()).isEqualTo("AUDIT");
        assertThat(category.alertNo()).isNull();
        assertThat(category.fallbackReason()).contains("降级审计通道");
        assertThat(category.items()).hasSize(2);
        assertThat(category.items().getFirst().providerSkuCode()).isEqualTo("MOCK-MISSING-001");
        assertThat(category.items().getFirst().reason()).isEqualTo("NOT_FOUND");
        assertThat(category.items().getFirst().skuCode()).isEqualTo("SKU-JD-000001");
        verify(alerts, never()).create(any(), anyString(), any());
    }

    @Test
    void queryFailureIsClassifiedAsMappingMissing() {
        stubProviderAndMappings(List.of(row(1, "JD-SKU-000001", "SKU-JD-000001", "500g/盒")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(JdGoodsReadOnlyVerifier.Verification.queryFailed("SDK_CALL_FAILED", null));
        stubRunIdempotency();

        JdSkuMappingCheckResult result = run("key-12345678");

        CategoryDiff category = result.categories().getFirst();
        assertThat(category.category()).isEqualTo("MAPPING_MISSING");
        assertThat(category.items().getFirst().reason()).isEqualTo("QUERY_FAILED");
        assertThat(category.items().getFirst().message()).contains("SDK_CALL_FAILED");
    }

    @Test
    void disabledGoodsIsClassifiedAsGoodsInvalid() {
        stubProviderAndMappings(List.of(row(1, "MOCK-DISABLED-001", "SKU-JD-000001", "500g/盒")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(JdGoodsReadOnlyVerifier.Verification.found(
                        "1000", "req-1", "MOCK-DISABLED-001", "ERP-MOCK-DISABLED-001",
                        "子牧羊小腿 500g/盒", 1));
        stubRunIdempotency();

        JdSkuMappingCheckResult result = run("key-12345678");

        CategoryDiff category = result.categories().getFirst();
        assertThat(category.category()).isEqualTo("GOODS_INVALID");
        assertThat(category.message()).contains("已失效（非上架）");
        assertThat(category.items().getFirst().reason()).isEqualTo("DISABLED");
    }

    /** 官方只定义 1/2；未文档化取值（如 0）必须降级为告警，不能判失效——一次误读已经停过业务。 */
    @Test
    void undocumentedEnableFlagIsWarnedInsteadOfBeingTreatedAsInvalid() {
        stubProviderAndMappings(List.of(row(1, "JD-SKU-000001", "SKU-JD-000001", "子牧羊小腿 500g/盒")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(JdGoodsReadOnlyVerifier.Verification.found(
                        "1000", "req-1", "JD-SKU-000001", "ERP-JD-SKU-000001",
                        "子牧羊小腿 500g/盒", 0));
        stubRunIdempotency();

        JdSkuMappingCheckResult result = run("key-12345678");

        assertThat(result.categories()).hasSize(1);
        CategoryDiff category = result.categories().getFirst();
        assertThat(category.category()).isEqualTo("GOODS_STATUS_UNKNOWN");
        assertThat(category.items().getFirst().reason()).isEqualTo("STATUS_UNKNOWN");
        assertThat(category.items().getFirst().message()).contains("enableFlag=0");
    }

    @Test
    void mismatchedGoodsNameIsClassifiedAsNameMismatch() {
        stubProviderAndMappings(List.of(row(1, "JD-SKU-000001", "SKU-JD-000001", "标准箱")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(enabledGoods("JD-SKU-000001", "子牧羊小腿 500g/盒"));
        stubRunIdempotency();

        JdSkuMappingCheckResult result = run("key-12345678");

        CategoryDiff category = result.categories().getFirst();
        assertThat(category.category()).isEqualTo("NAME_MISMATCH");
        assertThat(category.message()).contains("名称与系统名称不一致");
        DiffItem item = category.items().getFirst();
        assertThat(item.reason()).isEqualTo("NAME_MISMATCH");
        assertThat(item.message()).contains("子牧羊小腿 500g/盒");
    }

    @Test
    void providerSideNameFromExternalCodesIsAcceptedAsNameReference() {
        stubProviderAndMappings(List.of(new SkuMappingRow(
                1, 101, "EMG4418861038167", null, "京东商品名", "SKU-JD-000001", "京东商品编号 EMG4418861038167")));
        when(goodsVerifier.verify(anyString()))
                .thenReturn(enabledGoods("EMG4418861038167", "京东商品名"));
        stubRunIdempotency();

        JdSkuMappingCheckResult result = run("key-12345678");

        assertThat(result.categories()).isEmpty();
    }

    @Test
    void hasBusinessSubjectSeamAcceptsOrderSideSubjectOnly() {
        assertThat(JdSkuMappingCheckService.hasBusinessSubject(
                new CreateOperationalAlertCommand(
                        "JD_SKU_MAPPING", OperationalAlertSeverity.YELLOW,
                        null, null, null, null, "消息", Map.of()))).isFalse();
        assertThat(JdSkuMappingCheckService.hasBusinessSubject(
                new CreateOperationalAlertCommand(
                        "JD_SKU_MAPPING", OperationalAlertSeverity.YELLOW,
                        1L, null, null, null, "消息", Map.of()))).isTrue();
        assertThat(JdSkuMappingCheckService.hasBusinessSubject(
                new CreateOperationalAlertCommand(
                        "JD_SKU_MAPPING", OperationalAlertSeverity.YELLOW,
                        null, 2L, 3L, 4L, "消息", Map.of()))).isTrue();
    }
}
