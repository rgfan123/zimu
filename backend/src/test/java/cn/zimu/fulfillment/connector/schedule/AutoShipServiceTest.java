package cn.zimu.fulfillment.connector.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.SourceBatchConfirmer;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 自动发货的五条硬性安全要求，每条一组断言。
 *
 * <p>用 mock 而不是真库：这些要求全部是编排层的判断（发不发、用什么键、以什么身份、
 * 一批炸了别的还跑不跑），与 SQL 无关。SQL 正确性由
 * {@code AutoShipSqlIntegrationTest} 用真库单独覆盖。
 */
class AutoShipServiceTest {

    private static final LocalDate RUN_DATE = LocalDate.of(2026, 8, 28);

    private AutoShipReadiness readiness;
    private SourceBatchConfirmer confirmer;
    private AutoShipBlockerReader blockers;

    @BeforeEach
    void setUp() {
        readiness = mock(AutoShipReadiness.class);
        confirmer = mock(SourceBatchConfirmer.class);
        blockers = mock(AutoShipBlockerReader.class);
        when(blockers.of(anyLong())).thenReturn(AutoShipBlockerReader.Failures.none());
    }

    private AutoShipService service() {
        return new AutoShipService(readiness, confirmer, blockers, 20);
    }

    private static AutoShipReadiness.Candidate ready(long id, String batchNo) {
        return new AutoShipReadiness.Candidate(id, batchNo, "FEIXIANG", 5, 0, List.of());
    }

    private static AutoShipReadiness.Candidate blocked(long id, String batchNo, List<String> codes) {
        return new AutoShipReadiness.Candidate(id, batchNo, "FEIXIANG", 4, 1, codes);
    }

    private static IdempotentResult<Map<String, Object>> confirmed() {
        return IdempotentResult.executed(Map.of("skipped_rows", List.of()), 200);
    }

    // ------------------------------------------------------------------
    // 要求 1：只自动确认「完全就绪」的批次
    // ------------------------------------------------------------------

    @Test
    void batchWithAnyBlockedRowIsNeverAutoConfirmed() {
        when(readiness.candidates(anyInt()))
                .thenReturn(List.of(blocked(7L, "B-7", List.of("PROVIDER_SKU_MAPPING_REQUIRED"))));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        // 一行阻断就整批交给人：部分确认是给人用的能力，定时任务用它等于悄悄少发货。
        verify(confirmer, never()).confirmSourceBatch(anyLong(), anyString(), any());
        verify(confirmer, never()).submitJdOutboundsForSourceBatch(anyLong(), any());
        assertThat(outcome.shippedBatches()).isZero();
        assertThat(outcome.problemCount()).isEqualTo(1);
        assertThat(outcome.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.get("outcome")).isEqualTo("SKIPPED_BLOCKED");
            assertThat(entry.get("reason_codes")).isEqualTo(List.of("PROVIDER_SKU_MAPPING_REQUIRED"));
        });
    }

    @Test
    void fullyReadyBatchIsConfirmedAndSubmitted() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(9L, "B-9")));
        when(confirmer.confirmSourceBatch(eq(9L), anyString(), any())).thenReturn(confirmed());

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        verify(confirmer).submitJdOutboundsForSourceBatch(eq(9L), any());
        assertThat(outcome.shippedBatches()).isEqualTo(1);
        assertThat(outcome.problemCount()).isZero();
        assertThat(outcome.entries()).singleElement().extracting(entry -> entry.get("outcome"))
                .isEqualTo("SHIPPED");
    }

    // ------------------------------------------------------------------
    // 要求 2：幂等，且幂等键稳定（批次 + 日期，不含时间戳）
    // ------------------------------------------------------------------

    @Test
    void idempotencyKeyIsBatchPlusDateAndCarriesNoTimestamp() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(42L, "B-42")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());

        service().shipReadyBatches(RUN_DATE);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(confirmer).confirmSourceBatch(eq(42L), key.capture(), any());
        assertThat(key.getValue()).isEqualTo("auto-ship-42-2026-08-28");
    }

    @Test
    void theSameBatchOnTheSameDayProducesTheSameKeyAcrossRuns() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(42L, "B-42")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());

        // 早班与晚班是两次运行，但对同一批次必须是同一个键——否则同一批货会被确认两次。
        service().shipReadyBatches(RUN_DATE);
        service().shipReadyBatches(RUN_DATE);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(confirmer, org.mockito.Mockito.times(2)).confirmSourceBatch(eq(42L), keys.capture(), any());
        assertThat(keys.getAllValues()).containsExactly(
                "auto-ship-42-2026-08-28", "auto-ship-42-2026-08-28");
    }

    @Test
    void replayedConfirmNeverTriggersASecondJdSubmit() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(11L, "B-11")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any()))
                .thenReturn(IdempotentResult.replayed(200, null));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        // 重放返回的是首次结果；再调一次建单等于对同一批货发起第二次外部写。
        verify(confirmer, never()).submitJdOutboundsForSourceBatch(anyLong(), any());
        assertThat(outcome.shippedBatches()).isZero();
        assertThat(outcome.problemCount()).isZero();
        assertThat(outcome.entries()).singleElement().extracting(entry -> entry.get("outcome"))
                .isEqualTo("ALREADY_CONFIRMED");
    }

    // ------------------------------------------------------------------
    // 要求 3：操作人是明确的系统身份，不冒充人类
    // ------------------------------------------------------------------

    @Test
    void operatorIsAnExplicitMachineIdentity() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(5L, "B-5")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());

        service().shipReadyBatches(RUN_DATE);

        ArgumentCaptor<CommandContext> context = ArgumentCaptor.forClass(CommandContext.class);
        verify(confirmer).confirmSourceBatch(anyLong(), anyString(), context.capture());
        assertThat(context.getValue().operator()).isEqualTo("system:scheduled-pull");
        assertThat(context.getValue().operator()).doesNotStartWith("wecom:");
        // authenticatedOperator 必须非空且与 operator 相等，否则
        // ShipmentJdOutboundService#requireAuthorized 三个条件不成立，稳定 403。
        assertThat(context.getValue().authenticatedOperator()).isEqualTo("system:scheduled-pull");
    }

    // ------------------------------------------------------------------
    // 要求 4：失败不连坐
    // ------------------------------------------------------------------

    @Test
    void oneRejectedBatchDoesNotStopTheOthers() {
        when(readiness.candidates(anyInt()))
                .thenReturn(List.of(ready(1L, "B-1"), ready(2L, "B-2"), ready(3L, "B-3")));
        when(confirmer.confirmSourceBatch(eq(1L), anyString(), any())).thenReturn(confirmed());
        when(confirmer.confirmSourceBatch(eq(2L), anyString(), any()))
                .thenThrow(BusinessException.conflict("IMPORT_BATCH_BLOCKED", "批次仍有待处理问题"));
        when(confirmer.confirmSourceBatch(eq(3L), anyString(), any())).thenReturn(confirmed());

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        assertThat(outcome.shippedBatches()).isEqualTo(2);
        assertThat(outcome.problemCount()).isEqualTo(1);
        assertThat(outcome.entries()).extracting(entry -> entry.get("outcome"))
                .containsExactly("SHIPPED", "CONFIRM_REJECTED", "SHIPPED");
        // 被拒的那一批要留下它自己的业务码，不能只报一句「失败」。
        assertThat(outcome.entries().get(1).get("reason_codes")).isEqualTo(List.of("IMPORT_BATCH_BLOCKED"));
    }

    @Test
    void anUnexpectedRuntimeFailureIsIsolatedAndNotRetriedInTheSameRun() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(1L, "B-1"), ready(2L, "B-2")));
        when(confirmer.confirmSourceBatch(eq(1L), anyString(), any()))
                .thenThrow(new IllegalStateException("连接断了"));
        when(confirmer.confirmSourceBatch(eq(2L), anyString(), any())).thenReturn(confirmed());

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        assertThat(outcome.entries()).extracting(entry -> entry.get("outcome"))
                .containsExactly("CONFIRM_EXCEPTION", "SHIPPED");
        // 结局未知时不在本次运行里重按一次：正确动作是让人来看。
        verify(confirmer, org.mockito.Mockito.times(1)).confirmSourceBatch(eq(1L), anyString(), any());
    }

    @Test
    void aRejectionWithNoBusinessCodeStillDoesNotBreakTheRun() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(1L, "B-1"), ready(2L, "B-2")));
        // BusinessException 不保证 businessCode 非空；List.of(null) 会抛 NPE，
        // 而那个 NPE 会从 catch 块里逃出去，把「失败不连坐」整条要求掀翻。
        when(confirmer.confirmSourceBatch(eq(1L), anyString(), any()))
                .thenThrow(new BusinessException(409, null, "说不清为什么"));
        when(confirmer.confirmSourceBatch(eq(2L), anyString(), any())).thenReturn(confirmed());

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        assertThat(outcome.entries()).extracting(entry -> entry.get("outcome"))
                .containsExactly("CONFIRM_REJECTED", "SHIPPED");
        assertThat(outcome.entries().getFirst().get("reason_codes")).isEqualTo(List.of("AUTO_SHIP_REJECTED"));
        assertThat(outcome.shippedBatches()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 要求 5：爆炸半径有界 + 可整体关闭
    // ------------------------------------------------------------------

    @Test
    void batchLimitBoundsHowMuchOneRunCanShip() {
        when(readiness.candidates(anyInt())).thenReturn(List.of());

        new AutoShipService(readiness, confirmer, blockers, 3).shipReadyBatches(RUN_DATE);

        verify(readiness).candidates(3);
    }

    @Test
    void aNonPositiveLimitIsClampedRatherThanDisablingTheScan() {
        when(readiness.candidates(anyInt())).thenReturn(List.of());

        new AutoShipService(readiness, confirmer, blockers, 0).shipReadyBatches(RUN_DATE);

        verify(readiness).candidates(1);
    }

    @Test
    void disabledShipperIsStructurallyIncapableOfShipping() {
        // 关掉开关时本 bean 不存在，编排拿到的是这个实现——没有任何路径能把货发出去。
        assertThat(SourceBatchAutoShipper.disabled().shipReadyBatches(RUN_DATE))
                .isEqualTo(SourceBatchAutoShipper.Outcome.none());
    }

    // ------------------------------------------------------------------
    // 播报：区分类型，不笼统报失败
    // ------------------------------------------------------------------

    @Test
    void stockShortageAndMappingProblemsAreReportedSeparately() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(8L, "B-8")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());
        when(blockers.of(8L)).thenReturn(new AutoShipBlockerReader.Failures(
                2,
                0,
                List.of(
                        Map.of("code", "JD_STOCK_INSUFFICIENT"),
                        Map.of("code", "JD_SKU_MAPPING_GATE_BLOCKED", "mapping_issue_code", "MAPPING_MISSING")),
                List.of()));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        Map<String, Object> entry = outcome.entries().getFirst();
        assertThat(entry.get("outcome")).isEqualTo("SHIPPED_WITH_JD_FAILURES");
        assertThat(entry.get("reason_codes")).isEqualTo(List.of(
                "STOCK_INSUFFICIENT:JD_STOCK_INSUFFICIENT", "SKU_MAPPING:MAPPING_MISSING"));
        assertThat(String.valueOf(entry.get("detail")))
                .contains("缺货: JD_STOCK_INSUFFICIENT")
                .contains("映射校验: MAPPING_MISSING(疑似误报)");
        assertThat(outcome.problemCount()).isEqualTo(1);
    }

    @Test
    void aJdFailureOutsideStockJudgementIsStillReportedWithItsOwnCode() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(8L, "B-8")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());
        when(blockers.of(8L)).thenReturn(new AutoShipBlockerReader.Failures(
                1, 0, List.of(), List.of("JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED")));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        // 未授权是最容易被「反正就是失败了」掩盖掉的一类，必须原样带出来。
        assertThat(outcome.entries().getFirst().get("reason_codes"))
                .isEqualTo(List.of("OTHER:JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED"));
    }

    @Test
    void aBatchThatLeftNoJdTraceAtAllIsReportedAsNotShippedNotAsSuccess() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(3L, "B-3")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());
        // 操作人不在 JD_OUTBOUND_AUTHORIZED_OPERATORS 白名单时，requireAuthorized 抛在
        // persistSubmitIntent 之前，shipment_jd_outbounds 里一行痕迹都没有。
        // 只看失败表的话，这与「一切正常」长得一模一样——绝不能播报成 SHIPPED。
        when(blockers.of(3L)).thenReturn(new AutoShipBlockerReader.Failures(0, 2, List.of(), List.of()));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        Map<String, Object> entry = outcome.entries().getFirst();
        assertThat(entry.get("outcome")).isEqualTo("SHIPPED_WITH_JD_FAILURES");
        assertThat(entry.get("reason_codes")).isEqualTo(List.of("NOT_SUBMITTED:JD_OUTBOUND_NOT_SUBMITTED"));
        assertThat(String.valueOf(entry.get("detail"))).contains("未建单");
        assertThat(outcome.problemCount()).isEqualTo(1);
    }

    @Test
    void notShippedIsListedBeforeStockSoItIsReadFirst() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(3L, "B-3")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());
        when(blockers.of(3L)).thenReturn(new AutoShipBlockerReader.Failures(
                1, 1, List.of(Map.of("code", "JD_STOCK_INSUFFICIENT")), List.of()));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        // 「货根本没发出去」比「缺货」更需要人立刻动手，卡面字数有限，顺序即优先级。
        assertThat(outcome.entries().getFirst().get("reason_codes")).isEqualTo(List.of(
                "NOT_SUBMITTED:JD_OUTBOUND_NOT_SUBMITTED", "STOCK_INSUFFICIENT:JD_STOCK_INSUFFICIENT"));
    }

    @Test
    void skippedRowsAfterAFullyReadyConfirmAreRaisedAsPredicateDivergence() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(6L, "B-6")));
        // 本类判定「完全就绪」，闸门却跳过了行——两套判据已经不是同一条了。
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any()))
                .thenReturn(IdempotentResult.executed(
                        Map.of("skipped_rows", List.of(Map.of(
                                "row_id", "1",
                                "error_code", "PROVIDER_SKU_MAPPING_REQUIRED",
                                "reason", "收件人 张三 的行缺映射"))),
                        200));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        Map<String, Object> entry = outcome.entries().getFirst();
        assertThat(entry.get("outcome")).isEqualTo("READINESS_DIVERGED");
        assertThat(entry.get("reason_codes")).isEqualTo(List.of("PROVIDER_SKU_MAPPING_REQUIRED"));
        assertThat(outcome.problemCount()).isEqualTo(1);
        // 只取受控词表的 error_code；reason 是自由文本，可能带收件人字段，绝不能进摘要。
        assertThat(entry.toString()).doesNotContain("张三");
    }

    @Test
    void aFailingReadinessQueryIsReportedRatherThanLookingLikeAQuietDay() {
        when(readiness.candidates(anyInt())).thenThrow(new IllegalStateException("数据库炸了"));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        assertThat(outcome.problemCount()).isEqualTo(1);
        assertThat(outcome.shippedBatches()).isZero();
        assertThat(outcome.entries()).singleElement().extracting(entry -> entry.get("outcome"))
                .isEqualTo("READINESS_QUERY_FAILED");
    }

    @Test
    void aFailingBlockerReadDoesNotUndoShippedGoodsButIsStillReported() {
        when(readiness.candidates(anyInt())).thenReturn(List.of(ready(4L, "B-4")));
        when(confirmer.confirmSourceBatch(anyLong(), anyString(), any())).thenReturn(confirmed());
        when(blockers.of(4L)).thenThrow(new IllegalStateException("读不到"));

        SourceBatchAutoShipper.Outcome outcome = service().shipReadyBatches(RUN_DATE);

        assertThat(outcome.shippedBatches()).isEqualTo(1);
        assertThat(outcome.problemCount()).isEqualTo(1);
        assertThat(outcome.entries().getFirst().get("reason_codes"))
                .isEqualTo(List.of("OTHER:BLOCKER_READ_FAILED"));
    }
}
