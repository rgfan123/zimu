package cn.zimu.fulfillment.notification;

import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.operator.OperatorTeamResolution;
import cn.zimu.fulfillment.operator.OperatorTeamResolution.OperatorResolutionMember;
import cn.zimu.fulfillment.operator.OperatorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Executes one durable five-minute digest (Issue #90).
 *
 * <p>The source snapshot is an allowlisted, PII-free summary. Bound members receive one digest;
 * unbound or missing members are persisted as BLOCKED rather than silently discarded. Only a
 * transport result explicitly marked retryable may be retried. An in-flight/unknown result is
 * fenced by the store and never blindly resubmitted.
 */
@Service
public class WecomBusinessNotificationRunner {

    private final OperatorResolver operators;
    private final WecomOutboundGateway gateway;
    private final WecomNotificationStore store;
    private final Duration lease;

    public WecomBusinessNotificationRunner(
            OperatorResolver operators,
            WecomOutboundGateway gateway,
            WecomNotificationStore store,
            @Value("${app.wecom-notification.lease-seconds:120}") long leaseSeconds) {
        this.operators = operators;
        this.gateway = gateway;
        this.store = store;
        this.lease = Duration.ofSeconds(Math.max(30, leaseSeconds));
    }

    public void execute(NotificationBatch batch, String owner) {
        boolean leaseOwned = store.renewLease(batch.id(), owner, lease);
        if (!leaseOwned) {
            return;
        }
        try {
            OperatorTeamResolution resolution = operators.resolve(batch.responsibleTeam());
            Set<String> currentRecipientKeys = resolution.members().stream()
                    .map(WecomBusinessNotificationRunner::recipientKey)
                    .collect(Collectors.toUnmodifiableSet());
            store.reconcileRecipients(batch.id(), currentRecipientKeys);
            if (resolution.members().isEmpty()) {
                store.recordBlocked(
                        batch.id(),
                        "team:" + batch.responsibleTeam(),
                        null,
                        "OPERATOR_TEAM_NO_MEMBERS",
                        "责任团队暂无 active 运营人员");
                return;
            }

            String content = content(batch);
            String contentDigest = sha256(content);
            for (OperatorResolutionMember member : resolution.members()) {
                if (member.wecomUserid() == null) {
                    store.recordBlocked(
                            batch.id(),
                            recipientKey(member),
                            member.displayName(),
                            "WECOM_USERID_UNBOUND",
                            "运营人员未绑定企微 userid");
                } else {
                    leaseOwned = sendToBoundMember(batch, owner, member, content, contentDigest);
                    if (!leaseOwned) {
                        return;
                    }
                }
            }
        } catch (RuntimeException ex) {
            leaseOwned = store.renewLease(batch.id(), owner, lease);
            if (leaseOwned) {
                store.recordRoutingFailure(
                        batch.id(), "NOTIFICATION_ROUTING_FAILED", stableException(ex));
            }
        } finally {
            if (leaseOwned && store.renewLease(batch.id(), owner, lease)) {
                store.finishBatch(batch.id(), owner);
            }
        }
    }

    private boolean sendToBoundMember(
            NotificationBatch batch,
            String owner,
            OperatorResolutionMember member,
            String content,
            String contentDigest) {
        if (!store.renewLease(batch.id(), owner, lease)) {
            return false;
        }
        String recipientKey = recipientKey(member);
        DeliveryPermit permit = store.beginDelivery(
                batch.id(), recipientKey, member.displayName(), member.wecomUserid(), contentDigest);
        if (permit.action() != DeliveryAction.SEND) {
            return true;
        }
        // Fence again immediately before the only external side effect. beginDelivery has its own
        // transaction, so an expired owner must never cross this boundary even if it wrote SENDING.
        if (!store.renewLease(batch.id(), owner, lease)) {
            return false;
        }
        try {
            WecomSendResult result = gateway.send(WecomOutboundMessage.markdown(member.wecomUserid(), content));
            if (result.status() == WecomSendStatus.SUCCESS) {
                store.recordSent(batch.id(), recipientKey, result.requestId());
            } else if (result.retryable()) {
                store.recordRetryableFailure(
                        batch.id(),
                        recipientKey,
                        stableCode(result),
                        stableMessage(result),
                        permit.attempt());
            } else if (result.errorCode() != null) {
                // A server ACK with a non-zero errcode is an explicit rejection (for example the
                // user never established a bot conversation), not an unknown external effect.
                store.recordFailed(
                        batch.id(),
                        recipientKey,
                        result.requestId(),
                        stableCode(result),
                        stableMessage(result));
            } else {
                store.recordUnknown(
                        batch.id(),
                        recipientKey,
                        result.requestId(),
                        stableCode(result),
                        stableMessage(result));
            }
        } catch (RuntimeException ex) {
            // The gateway can throw after handing bytes to its transport; delivery is unknown.
            store.recordUnknown(
                    batch.id(), recipientKey, null, "WECOM_SEND_EXCEPTION", stableException(ex));
        }
        return true;
    }

    private static String recipientKey(OperatorResolutionMember member) {
        return "operator:" + member.operatorId()
                + (member.wecomUserid() == null ? ":unbound" : ":userid:" + member.wecomUserid());
    }

    static String content(NotificationBatch batch) {
        StringBuilder text = new StringBuilder()
                .append("【子牧待办｜5 分钟汇总】\n")
                .append("责任团队：")
                .append(safe(batch.responsibleTeam()))
                .append("\n共 ")
                .append(batch.items().size())
                .append(" 项\n");
        int index = 1;
        for (NotificationItem item : batch.items()) {
            text.append(index++).append(". ").append(line(item)).append('\n');
        }
        text.append("请在子牧工作台核对处理；当前消息不附跳转链接。");
        return text.toString();
    }

    private static String line(NotificationItem item) {
        Map<String, Object> value = item.summary();
        String subject = subject(value, item);
        return switch (item.notificationKind()) {
            case "REVIEW_CASE" -> "复核 " + field(value, "case_no", "#" + item.sourceId())
                    + "；原因 " + field(value, "reason_code", "待人工判断")
                    + "；对象 " + subject;
            case "ORDER_CREATED" -> "订单已创建 " + subject
                    + "；来源 " + field(value, "source_channel", "未知");
            case "SHIPMENT_COMPLETED" -> "发货已完成 " + subject
                    + "；发货批次 " + field(value, "shipment_id", "未知");
            default -> "业务事项 " + safe(item.notificationKind()) + "；对象 " + subject;
        };
    }

    private static String subject(Map<String, Object> summary, NotificationItem item) {
        if (summary.get("order_no") != null) {
            return "订单 " + field(summary, "order_no", "未知");
        }
        String[][] candidates = {
            {"order_draft_id", "订单草稿"},
            {"provider_tracking_draft_id", "运单草稿"},
            {"shipment_id", "发货批次"},
            {"fulfillment_id", "履约任务"},
            {"import_batch_id", "导入批次"},
            {"raw_import_row_id", "导入行"},
            {"message_submission_id", "消息提交"},
            {"order_line_id", "订单行"},
            {"order_id", "订单"}
        };
        for (String[] candidate : candidates) {
            if (summary.get(candidate[0]) != null) {
                return candidate[1] + "#" + field(summary, candidate[0], "未知");
            }
        }
        return item.sourceType() + "#" + item.sourceId();
    }

    private static String field(Map<String, Object> summary, String key, String fallback) {
        Object value = summary.get(key);
        return value == null ? fallback : safe(String.valueOf(value));
    }

    private static String safe(String value) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private static String stableCode(WecomSendResult result) {
        return result.errorCode() == null
                ? safe(result.errorMessage() == null ? result.status().name() : result.errorMessage())
                : "WECOM_" + result.errorCode();
    }

    private static String stableMessage(WecomSendResult result) {
        return safe(result.errorMessage() == null ? result.status().name() : result.errorMessage());
    }

    private static String stableException(RuntimeException ex) {
        return ex.getClass().getSimpleName();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
