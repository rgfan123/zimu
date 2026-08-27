package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 执行一次业务卡发送（#87/#88）。
 *
 * <p>三条纪律照抄订单草稿卡已建立的语义，不另起炉灶：
 * <ul>
 *   <li>**走 Gateway 不走 sendFrame**：ack 关联、超时不可重试、审计脱敏、背压优先级
 *       全在 Gateway 里，绕过去等于把 #81 建立的纪律全丢掉；</li>
 *   <li>**发送前按当前事实重新渲染**：source 返回 empty 即事实已变，落 SUPERSEDED
 *       而不是把一张过期卡发出去；</li>
 *   <li>**结局未知禁止盲发**：ACK 超时与提交后断连的外部效果未知，落 UNKNOWN 等人判定，
 *       重发会让人收到两张一模一样的卡。</li>
 * </ul>
 */
@Service
public class WecomBusinessCardRunner {

    public static final Duration LEASE_EXTENSION = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(WecomBusinessCardRunner.class);

    private final WecomBusinessCardStore cards;
    private final WecomBusinessCardSourceRegistry sources;
    private final AsyncTaskStore tasks;
    private final WecomOutboundGateway gateway;

    public WecomBusinessCardRunner(
            WecomBusinessCardStore cards,
            WecomBusinessCardSourceRegistry sources,
            AsyncTaskStore tasks,
            WecomOutboundGateway gateway) {
        this.cards = cards;
        this.sources = sources;
        this.tasks = tasks;
        this.gateway = gateway;
    }

    public void execute(AsyncTaskStore.AsyncTask task) {
        long cardId = cardId(task);
        if (!tasks.renewLease(task.id(), task.leaseOwner(), LEASE_EXTENSION)) {
            return;
        }
        WecomBusinessCard card = cards.load(cardId);
        WecomBusinessCardStore.CardSendPermit permit = cards.beginSend(cardId);
        if (permit.action() == WecomBusinessCardStore.CardSendAction.SKIP_HANDLED) {
            tasks.succeed(task.id(), task.leaseOwner());
            return;
        }
        if (permit.action() == WecomBusinessCardStore.CardSendAction.SKIP_UNKNOWN) {
            // 上一次结局未知：不重发，收口任务等人判定
            tasks.succeed(task.id(), task.leaseOwner());
            return;
        }

        Optional<WecomBusinessCardSource> source = sources.find(card.cardDomain());
        if (source.isEmpty()) {
            cards.recordSuperseded(cardId, "WECOM_CARD_SOURCE_UNREGISTERED");
            tasks.succeed(task.id(), task.leaseOwner());
            return;
        }

        Optional<ObjectNode> rendered;
        try {
            rendered = source.get().render(card.entityId(), card.entityVersion());
        } catch (RuntimeException ex) {
            log.warn("业务卡渲染失败 task_id={}", card.taskId(), ex);
            cards.recordRetryable(cardId, "WECOM_CARD_RENDER_FAILED");
            tasks.fail(task.id(), task.leaseOwner(), "WECOM_CARD_RENDER_FAILED", Duration.ofSeconds(30));
            return;
        }
        if (rendered.isEmpty()) {
            // 事实已变（已处置 / 版本已推进）：这张卡不该再发
            cards.recordSuperseded(cardId, "WECOM_CARD_FACTS_SUPERSEDED");
            tasks.succeed(task.id(), task.leaseOwner());
            return;
        }

        // 附件先行（如整批确认的明细清单）：先看清单、后见按钮。
        // 任一附件失败按可重试处理——整卡（含附件）下轮重来；重试可能让人多收到
        // 一份同样的清单，比「有按钮没明细」的卡安全得多。
        if (!deliverAttachments(card, source.get(), cardId, task)) {
            return;
        }

        WecomSendResult result;
        try {
            result = gateway.send(WecomOutboundMessage.templateCard(card.chatId(), rendered.get()));
        } catch (RuntimeException ex) {
            // 提交后异常：外部效果未知，围栏禁止盲发
            cards.recordUnknown(cardId, "WECOM_CARD_SEND_EXCEPTION");
            tasks.succeed(task.id(), task.leaseOwner());
            return;
        }

        if (result.status() == WecomSendStatus.SUCCESS) {
            cards.recordSent(cardId, result.requestId(), result.acknowledgedAt());
            tasks.succeed(task.id(), task.leaseOwner());
        } else if (result.retryable()) {
            cards.recordRetryable(cardId, stableCode(result));
            tasks.fail(task.id(), task.leaseOwner(), stableCode(result), Duration.ofSeconds(30));
        } else if (result.status() == WecomSendStatus.FAILED && result.errorCode() != null) {
            // 平台非零 ACK 是明确拒绝：卡片没被接受，重试也不会变
            cards.recordFailed(cardId, stableCode(result));
            tasks.failTerminal(task.id(), task.leaseOwner(), stableCode(result));
        } else {
            cards.recordUnknown(cardId, stableCode(result));
            tasks.succeed(task.id(), task.leaseOwner());
        }
    }

    /** 附件逐个上传并投递；全部成功返回 true，否则已落好状态直接返回 false。 */
    private boolean deliverAttachments(
            WecomBusinessCard card,
            WecomBusinessCardSource source,
            long cardId,
            AsyncTaskStore.AsyncTask task) {
        List<WecomBusinessCardSource.Attachment> attachments;
        try {
            attachments = source.attachments(card.entityId(), card.entityVersion());
        } catch (RuntimeException ex) {
            log.warn("业务卡附件生成失败 task_id={}", card.taskId(), ex);
            cards.recordRetryable(cardId, "WECOM_CARD_ATTACHMENT_RENDER_FAILED");
            tasks.fail(task.id(), task.leaseOwner(), "WECOM_CARD_ATTACHMENT_RENDER_FAILED", Duration.ofSeconds(30));
            return false;
        }
        for (WecomBusinessCardSource.Attachment attachment : attachments) {
            if (!deliverOne(card, attachment, cardId, task)) {
                return false;
            }
        }
        return true;
    }

    private boolean deliverOne(
            WecomBusinessCard card,
            WecomBusinessCardSource.Attachment attachment,
            long cardId,
            AsyncTaskStore.AsyncTask task) {
        Path temp = null;
        try {
            String suffix = attachment.filename().contains(".")
                    ? attachment.filename().substring(attachment.filename().lastIndexOf('.'))
                    : ".bin";
            temp = Files.createTempFile("wecom-card-attachment", suffix);
            Files.write(temp, attachment.content());
            WecomUploadResult upload = gateway.upload(temp, attachment.filename(), attachment.mediaType());
            if (upload.mediaId() == null || upload.mediaId().isBlank()) {
                cards.recordRetryable(cardId, "WECOM_CARD_ATTACHMENT_UPLOAD_FAILED");
                tasks.fail(task.id(), task.leaseOwner(),
                        "WECOM_CARD_ATTACHMENT_UPLOAD_FAILED", Duration.ofSeconds(30));
                return false;
            }
            WecomOutboundMessage message = attachment.mediaType() == WecomMediaType.IMAGE
                    ? WecomOutboundMessage.image(card.chatId(), upload.mediaId())
                    : WecomOutboundMessage.file(card.chatId(), upload.mediaId());
            WecomSendResult sent = gateway.send(message);
            if (sent.status() != WecomSendStatus.SUCCESS) {
                cards.recordRetryable(cardId, "WECOM_CARD_ATTACHMENT_SEND_FAILED");
                tasks.fail(task.id(), task.leaseOwner(),
                        "WECOM_CARD_ATTACHMENT_SEND_FAILED", Duration.ofSeconds(30));
                return false;
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("业务卡附件投递失败 task_id={} file={}", card.taskId(), attachment.filename(), ex);
            cards.recordRetryable(cardId, "WECOM_CARD_ATTACHMENT_SEND_FAILED");
            tasks.fail(task.id(), task.leaseOwner(), "WECOM_CARD_ATTACHMENT_SEND_FAILED", Duration.ofSeconds(30));
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件残留只影响磁盘，不影响业务
                }
            }
        }
    }

    static long cardId(AsyncTaskStore.AsyncTask task) {
        if (!WecomBusinessCardEnqueuer.TASK_TYPE.equals(task.taskType())
                || task.payloadRef() == null
                || !task.payloadRef().matches("card:[1-9][0-9]*")) {
            throw new IllegalArgumentException("非法的业务卡任务载荷: " + task.payloadRef());
        }
        return Long.parseLong(task.payloadRef().substring("card:".length()));
    }

    private static String stableCode(WecomSendResult result) {
        String value;
        if (result.errorCode() != null) {
            value = "WECOM_" + result.errorCode();
        } else {
            value = result.errorMessage() == null ? result.status().name() : result.errorMessage();
        }
        return value.substring(0, Math.min(128, value.length()));
    }
}
