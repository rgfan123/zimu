package cn.zimu.fulfillment.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.operator.OperatorResolutionStatus;
import cn.zimu.fulfillment.operator.OperatorResolver;
import cn.zimu.fulfillment.operator.OperatorTeamResolution;
import cn.zimu.fulfillment.operator.OperatorTeamResolution.OperatorResolutionMember;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Issue #90 public runner seam: 5-minute digest, explicit routing degradation and no blind resend. */
class WecomBusinessNotificationRunnerTest {

    private OperatorResolver operators;
    private WecomOutboundGateway gateway;
    private WecomNotificationStore store;
    private WecomBusinessNotificationRunner runner;

    @BeforeEach
    void setUp() {
        operators = mock(OperatorResolver.class);
        gateway = mock(WecomOutboundGateway.class);
        store = mock(WecomNotificationStore.class);
        runner = new WecomBusinessNotificationRunner(operators, gateway, store, 120L);
    }

    @Test
    void sendsOneUsefulDigestToEachBoundMemberAndRecordsUnboundMemberExplicitly() {
        NotificationBatch batch = batch();
        when(operators.resolve("ORDER_OPS")).thenReturn(new OperatorTeamResolution(
                "ORDER_OPS",
                List.of(
                        new OperatorResolutionMember(101L, "张三", "zhangsan"),
                        new OperatorResolutionMember(102L, "李四", null)),
                List.of("zhangsan"),
                List.of("李四"),
                OperatorResolutionStatus.PARTIALLY_BOUND,
                false));
        when(store.renewLease(41L, "worker-a", Duration.ofMinutes(2))).thenReturn(true);
        when(store.beginDelivery(
                        eq(41L),
                        eq("operator:101:userid:zhangsan"),
                        eq("张三"),
                        eq("zhangsan"),
                        any()))
                .thenReturn(new DeliveryPermit(DeliveryAction.SEND, 1));
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                cn.zimu.fulfillment.connector.wecom.WecomSendStatus.SUCCESS,
                "req-1",
                Instant.parse("2026-08-23T10:00:00Z"),
                null,
                null,
                false));

        runner.execute(batch, "worker-a");

        ArgumentCaptor<WecomOutboundMessage> outbound = ArgumentCaptor.forClass(WecomOutboundMessage.class);
        verify(gateway).send(outbound.capture());
        assertThat(outbound.getValue().chatId()).isEqualTo("zhangsan");
        assertThat(outbound.getValue().content())
                .contains("5 分钟汇总", "共 2 项", "RC-100", "SKU_MAPPING_REQUIRED", "ORD-100")
                .doesNotContain("receiver", "phone", "address");
        verify(store).reconcileRecipients(
                41L, Set.of("operator:101:userid:zhangsan", "operator:102:unbound"));
        verify(store).recordSent(41L, "operator:101:userid:zhangsan", "req-1");
        verify(store).recordBlocked(
                41L,
                "operator:102:unbound",
                "李四",
                "WECOM_USERID_UNBOUND",
                "运营人员未绑定企微 userid");
        verify(store).finishBatch(41L, "worker-a");
    }

    @Test
    void noMembersIsBlockedAndNeverCallsTheExternalGateway() {
        NotificationBatch batch = batch();
        when(operators.resolve("ORDER_OPS")).thenReturn(new OperatorTeamResolution(
                "ORDER_OPS", List.of(), List.of(), List.of(), OperatorResolutionStatus.NO_MEMBERS, false));
        when(store.renewLease(41L, "worker-a", Duration.ofMinutes(2))).thenReturn(true);

        runner.execute(batch, "worker-a");

        verify(gateway, never()).send(any());
        verify(store).recordBlocked(
                41L, "team:ORDER_OPS", null, "OPERATOR_TEAM_NO_MEMBERS", "责任团队暂无 active 运营人员");
        verify(store).finishBatch(41L, "worker-a");
    }

    @Test
    void retryablePreSubmissionFailureIsScheduledButUnknownDeliveryIsNeverBlindlyRetried() {
        NotificationBatch batch = batch();
        when(operators.resolve("ORDER_OPS")).thenReturn(new OperatorTeamResolution(
                "ORDER_OPS",
                List.of(new OperatorResolutionMember(101L, "张三", "zhangsan")),
                List.of("zhangsan"),
                List.of(),
                OperatorResolutionStatus.PUSHABLE,
                true));
        when(store.renewLease(anyLong(), any(), any())).thenReturn(true);
        when(store.beginDelivery(
                        eq(41L),
                        eq("operator:101:userid:zhangsan"),
                        eq("张三"),
                        eq("zhangsan"),
                        any()))
                .thenReturn(new DeliveryPermit(DeliveryAction.SEND, 1));
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                cn.zimu.fulfillment.connector.wecom.WecomSendStatus.FAILED,
                null,
                null,
                null,
                "NOT_CONNECTED",
                true));

        runner.execute(batch, "worker-a");

        verify(store).recordRetryableFailure(
                41L,
                "operator:101:userid:zhangsan",
                "NOT_CONNECTED",
                "NOT_CONNECTED",
                1);
        verify(store).finishBatch(41L, "worker-a");

        when(store.beginDelivery(
                        eq(41L),
                        eq("operator:101:userid:zhangsan"),
                        eq("张三"),
                        eq("zhangsan"),
                        any()))
                .thenReturn(new DeliveryPermit(DeliveryAction.SKIP_UNKNOWN, 1));
        runner.execute(batch, "worker-b");

        // Second execution sees a persisted unknown in-flight delivery and must not submit again.
        verify(gateway).send(any());
        verify(store).finishBatch(41L, "worker-b");
    }

    @Test
    void acknowledgedPlatformRejectionIsAVisibleFailureRatherThanUnknownDelivery() {
        NotificationBatch batch = batch();
        when(operators.resolve("ORDER_OPS")).thenReturn(new OperatorTeamResolution(
                "ORDER_OPS",
                List.of(new OperatorResolutionMember(101L, "张三", "zhangsan")),
                List.of("zhangsan"),
                List.of(),
                OperatorResolutionStatus.PUSHABLE,
                true));
        when(store.renewLease(anyLong(), any(), any())).thenReturn(true);
        when(store.beginDelivery(
                        eq(41L),
                        eq("operator:101:userid:zhangsan"),
                        eq("张三"),
                        eq("zhangsan"),
                        any()))
                .thenReturn(new DeliveryPermit(DeliveryAction.SEND, 1));
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                cn.zimu.fulfillment.connector.wecom.WecomSendStatus.FAILED,
                "req-rejected",
                null,
                93000,
                "user has not established a conversation",
                false));

        runner.execute(batch, "worker-a");

        verify(store).recordFailed(
                41L,
                "operator:101:userid:zhangsan",
                "req-rejected",
                "WECOM_93000",
                "user has not established a conversation");
        verify(store, never()).recordUnknown(anyLong(), any(), any(), any(), any());
        verify(store).finishBatch(41L, "worker-a");
    }

    @Test
    void lostLeaseStopsBeforeExternalCallAndDoesNotFinishTheBatch() {
        NotificationBatch batch = batch();
        when(store.renewLease(41L, "stale-worker", Duration.ofMinutes(2))).thenReturn(false);

        runner.execute(batch, "stale-worker");

        verify(operators, never()).resolve(any());
        verify(gateway, never()).send(any());
        verify(store, never()).finishBatch(anyLong(), any());
    }

    @Test
    void leaseLostAfterWritingSendingFenceStillStopsBeforeTheExternalCall() {
        NotificationBatch batch = batch();
        when(operators.resolve("ORDER_OPS")).thenReturn(new OperatorTeamResolution(
                "ORDER_OPS",
                List.of(new OperatorResolutionMember(101L, "张三", "zhangsan")),
                List.of("zhangsan"),
                List.of(),
                OperatorResolutionStatus.PUSHABLE,
                true));
        when(store.renewLease(41L, "worker-race", Duration.ofMinutes(2)))
                .thenReturn(true, true, false);
        when(store.beginDelivery(
                        eq(41L),
                        eq("operator:101:userid:zhangsan"),
                        eq("张三"),
                        eq("zhangsan"),
                        any()))
                .thenReturn(new DeliveryPermit(DeliveryAction.SEND, 1));

        runner.execute(batch, "worker-race");

        verify(store).beginDelivery(
                eq(41L),
                eq("operator:101:userid:zhangsan"),
                eq("张三"),
                eq("zhangsan"),
                any());
        verify(gateway, never()).send(any());
        verify(store, never()).finishBatch(anyLong(), any());
    }

    private static NotificationBatch batch() {
        return new NotificationBatch(
                41L,
                "ORDER_OPS",
                Instant.parse("2026-08-23T09:55:00Z"),
                List.of(
                        new NotificationItem(
                                1L,
                                "REVIEW_CASE",
                                100L,
                                "REVIEW_CASE",
                                Map.of(
                                        "case_no", "RC-100",
                                        "reason_code", "SKU_MAPPING_REQUIRED",
                                        "order_no", "ORD-100")),
                        new NotificationItem(
                                2L,
                                "ORDER_EVENT",
                                101L,
                                "ORDER_CREATED",
                                Map.of("order_no", "ORD-101", "source_channel", "WECOM"))));
    }
}
