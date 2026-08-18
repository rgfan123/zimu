package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.dto.OperationalAlertDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OperationalAlertServiceCreateTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AuditLogService audits = mock(AuditLogService.class);

    private OperationalAlertService service() {
        return new OperationalAlertService(jdbc, new ObjectMapper(), idempotency, audits);
    }

    private CreateOperationalAlertCommand subjectAlert() {
        return new CreateOperationalAlertCommand(
                "JD_SKU_MAPPING",
                OperationalAlertSeverity.YELLOW,
                1L, null, null, null,
                "京东 SKU 映射核对发现 2 个商品编码在京东查不到",
                Map.of("check_run_no", "CHK-001", "category", "MAPPING_MISSING"));
    }

    @Test
    void rejectsAlertWithoutAnyBusinessSubjectBeforeTouchingIdempotency() {
        OperationalAlertService service = service();

        assertThatThrownBy(() -> service.create(
                new CreateOperationalAlertCommand(
                        "JD_SKU_MAPPING", OperationalAlertSeverity.YELLOW,
                        null, null, null, null, "消息", Map.of()),
                "key-12345678",
                new CommandContext("req", null, "ops")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getBusinessCode()).isEqualTo("ALERT_SUBJECT_REQUIRED");
                    assertThat(business.getHttpStatus()).isEqualTo(422);
                });

        verify(idempotency, never()).execute(anyString(), anyString(), any(), anyInt(), any());
    }

    @Test
    void rejectsBlankAlertTypeSeverityNullAndBlankMessage() {
        OperationalAlertService service = service();
        CommandContext context = new CommandContext("req", null, "ops");

        assertThatThrownBy(() -> service.create(
                new CreateOperationalAlertCommand(" ", OperationalAlertSeverity.YELLOW,
                        1L, null, null, null, "消息", Map.of()),
                "key-12345678", context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("ALERT_TYPE_REQUIRED"));

        assertThatThrownBy(() -> service.create(
                new CreateOperationalAlertCommand("JD_SKU_MAPPING", null,
                        1L, null, null, null, "消息", Map.of()),
                "key-12345678", context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("ALERT_SEVERITY_REQUIRED"));

        assertThatThrownBy(() -> service.create(
                new CreateOperationalAlertCommand("JD_SKU_MAPPING", OperationalAlertSeverity.YELLOW,
                        1L, null, null, null, "  ", Map.of()),
                "key-12345678", context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("ALERT_MESSAGE_REQUIRED"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void insertsAlertWithGeneratedAlertNoAndAuditsWhenSubjectPresent() {
        OperationalAlertService service = service();
        when(idempotency.execute(anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    IdempotencyService.Work<OperationalAlertDto> work = invocation.getArgument(4);
                    return IdempotentResult.executed(work.execute(), 201);
                });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        OperationalAlertDto dto = new OperationalAlertDto(
                "42", "ALERT-ABCD", "JD_SKU_MAPPING", "YELLOW", "OPEN",
                "1", null, null, null, "消息", Map.of(), null, null, null, 0, Instant.now());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(dto));

        IdempotentResult<OperationalAlertDto> result = service.create(
                subjectAlert(), "key-12345678", new CommandContext("req-1", "trace-1", "ops-user"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.result().alertNo()).isEqualTo("ALERT-ABCD");

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), eq(Long.class), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).asString().startsWith("ALERT-");
        assertThat(args[1]).isEqualTo("JD_SKU_MAPPING");
        assertThat(args[2]).isEqualTo("YELLOW");
        assertThat(args[3]).isEqualTo(1L);
        assertThat(args[4]).isNull();
        assertThat(args[5]).isNull();
        assertThat(args[6]).isNull();
        assertThat(args[7]).isEqualTo("京东 SKU 映射核对发现 2 个商品编码在京东查不到");
        assertThat(args[8]).asString().contains("\"check_run_no\":\"CHK-001\"");

        ArgumentCaptor<AuditLogService.AuditCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(auditCaptor.capture());
        assertThat(auditField(auditCaptor.getValue(), "operation")).isEqualTo("operational_alert.create");
        assertThat(auditField(auditCaptor.getValue(), "businessCode")).isEqualTo("OPERATIONAL_ALERT_CREATED");
        assertThat(auditField(auditCaptor.getValue(), "operator")).isEqualTo("ops-user");

        ArgumentCaptor<String> scopeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(idempotency).execute(scopeCaptor.capture(), keyCaptor.capture(), any(), anyInt(), any());
        assertThat(scopeCaptor.getValue()).isEqualTo("operational_alert.create");
        assertThat(keyCaptor.getValue()).isEqualTo("key-12345678");
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
}
