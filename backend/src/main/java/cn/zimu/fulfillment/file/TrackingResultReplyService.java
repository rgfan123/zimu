package cn.zimu.fulfillment.file;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 把回填好的 Excel 发回来件人所在会话，闭合「你发表格 → 系统回填 → 表格还你」这一圈。
 *
 * <p>回发目标是**原会话**，不是配置的群：这张表是对方那次提交的产物，
 * 应该回到他发起的地方。与业务卡片不同——卡片是系统主动播报，没有原会话可回，
 * 所以才需要显式配群。
 *
 * <p><b>失败不回滚业务</b>：回填已经落库并生效，发不出去只是「没收到回执」。
 * 因此本服务吞掉发送异常并记日志，绝不让企微不可用把已完成的回填一起拖垮。
 */
@Service
public class TrackingResultReplyService {

    private static final Logger log = LoggerFactory.getLogger(TrackingResultReplyService.class);

    private final JdbcTemplate jdbc;
    private final TrackingResultWorkbookService workbooks;
    private final WecomOutboundGateway gateway;

    public TrackingResultReplyService(
            JdbcTemplate jdbc,
            TrackingResultWorkbookService workbooks,
            WecomOutboundGateway gateway) {
        this.jdbc = jdbc;
        this.workbooks = workbooks;
        this.gateway = gateway;
    }

    /**
     * 为某个发货批次生成回填结果并回发到指定会话。
     *
     * @return 是否已成功送达；任何一步不具备条件都返回 false 并记明原因，不抛异常。
     */
    public boolean replyForShipment(long shipmentId, String chatId) {
        if (chatId == null || chatId.isBlank()) {
            log.info("回填结果无回发目标 shipment={}", shipmentId);
            return false;
        }
        List<TrackingResultWorkbookService.ResultRow> rows = workbooks.rows(shipmentId);
        if (rows.isEmpty()) {
            log.info("回填结果为空，不回发 shipment={}", shipmentId);
            return false;
        }
        Path temp = null;
        try {
            byte[] bytes = workbooks.workbook(rows);
            // 企微上传要求本地文件路径；文件名带出库单号，对方一眼能对上是哪一批
            String filename = "回填结果-" + safeName(rows.getFirst().outboundOrderNo()) + ".xlsx";
            temp = Files.createTempFile("zimu-tracking-result-", ".xlsx");
            Files.write(temp, bytes);

            WecomUploadResult upload = gateway.upload(temp, filename, WecomMediaType.FILE);
            if (upload.mediaId() == null || upload.mediaId().isBlank()) {
                log.warn("回填结果上传未取得 media_id shipment={} status={}", shipmentId, upload.status());
                return false;
            }
            WecomSendResult sent = gateway.send(WecomOutboundMessage.file(chatId, upload.mediaId()));
            boolean ok = sent.status() == WecomSendStatus.SUCCESS;
            if (ok) {
                log.info("回填结果已回发 shipment={} rows={}", shipmentId, rows.size());
            } else {
                log.warn("回填结果回发未成功 shipment={} status={}", shipmentId, sent.status());
            }
            return ok;
        } catch (IOException | RuntimeException exception) {
            // 回填已生效，回执发不出去不该反噬业务——记下来，人工可在发货台重发
            log.warn(
                    "回填结果回发失败 shipment={} type={} message={}",
                    shipmentId,
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响结果，交给系统临时目录回收
                }
            }
        }
    }

    /**
     * 由提交记录反查来件会话：这张表要回到他发起的那个会话。
     * 查不到就返回 null——宁可不发，也不发到一个猜出来的会话里。
     */
    public String originatingChatId(long submissionId) {
        List<String> found = jdbc.queryForList(
                """
                SELECT cm.chat_id
                FROM app.message_submissions ms
                JOIN app.channel_messages cm ON cm.id = ms.source_message_id
                WHERE ms.id = ?
                """,
                String.class,
                submissionId);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** 文件名安全化：只保留数字字母与连字符，避免企微侧对特殊字符的处置差异。 */
    private static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_-]", "");
    }
}
