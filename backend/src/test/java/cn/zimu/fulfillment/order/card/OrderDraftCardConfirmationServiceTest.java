package cn.zimu.fulfillment.order.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.OrderDraftQueryService;
import cn.zimu.fulfillment.order.OrderDraftService;
import cn.zimu.fulfillment.order.dto.ConfirmOrderDraftCommand;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import cn.zimu.fulfillment.order.dto.OrderDraftLineDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Issue #87 card confirmation must rebuild the existing human-confirm command from current DB facts. */
class OrderDraftCardConfirmationServiceTest {

    private OrderDraftQueryService drafts;
    private OrderDraftService orderDrafts;
    private OrderDraftCardConfirmationService service;

    @BeforeEach
    void setUp() {
        drafts = mock(OrderDraftQueryService.class);
        orderDrafts = mock(OrderDraftService.class);
        service = new OrderDraftCardConfirmationService(drafts, orderDrafts);
    }

    @Test
    void completeDraftUsesUniqueDeterministicCandidatesAndTrustedClickActor() {
        OrderDraftDetailDto draft = draft(List.of());
        when(drafts.detail(41L)).thenReturn(draft);
        when(orderDrafts.confirm(eq(41L), org.mockito.ArgumentMatchers.any(), eq("wecom-card-confirm:EVT-41"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(IdempotentResult.executed(draft, 200));

        CardConfirmationResult result = service.confirm(41L, "EVT-41", "REQ-41", "zhangsan");

        assertThat(result.status()).isEqualTo(CardConfirmationStatus.CONFIRMED);
        assertThat(result.businessCode()).isEqualTo("ORDER_DRAFT_CONFIRMED");
        ArgumentCaptor<ConfirmOrderDraftCommand> command = ArgumentCaptor.forClass(ConfirmOrderDraftCommand.class);
        ArgumentCaptor<CommandContext> context = ArgumentCaptor.forClass(CommandContext.class);
        verify(orderDrafts).confirm(eq(41L), command.capture(), eq("wecom-card-confirm:EVT-41"), context.capture());
        assertThat(command.getValue().expectedRevision()).isZero();
        assertThat(command.getValue().expectedCaseVersion()).isZero();
        assertThat(command.getValue().customer().customerId()).isEqualTo("9");
        assertThat(command.getValue().items()).singleElement().satisfies(item -> {
            assertThat(item.skuId()).isEqualTo("17");
            assertThat(item.quantity()).isEqualTo("2.000");
        });
        assertThat(command.getValue().settlement().settlementTime())
                .isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(context.getValue().operator()).isEqualTo("wecom:zhangsan");
        assertThat(context.getValue().authenticatedOperator()).isEqualTo("wecom:zhangsan");
    }

    @Test
    void incompleteDraftReturnsSupplementBranchWithoutCallingBusinessConfirmation() {
        when(drafts.detail(41L)).thenReturn(draft(List.of("settlement_time", "line_1_sku")));

        CardConfirmationResult result = service.confirm(41L, "EVT-42", "REQ-42", "zhangsan");

        assertThat(result.status()).isEqualTo(CardConfirmationStatus.MISSING_INFORMATION);
        assertThat(result.missingFields()).containsExactly("settlement_time", "line_1_sku");
        assertThat(result.businessCode()).isEqualTo("ORDER_DRAFT_CARD_MISSING_INFORMATION");
        verify(orderDrafts, never()).confirm(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static OrderDraftDetailDto draft(List<String> missing) {
        return new OrderDraftDetailDto(
                "41", "OD-41", "WECOM-SUB-41", "11", "OPEN", 0,
                null, null, null,
                List.of(Map.of("customer_id", "9", "customer_name", "测试客户")),
                "测试客户", "张三", "13800000000", "上海市测试地址", "MONTHLY",
                Instant.parse("2026-08-31T16:00:00Z"), missing,
                List.of(new OrderDraftLineDto(
                        "51", 1, null, null,
                        List.of(Map.of("sku_id", "17", "sku_code", "SKU-17")),
                        "商品", "规格", "件", "2.000")),
                "61", 0L, null, null, null, null,
                Instant.now(), Instant.now());
    }
}
