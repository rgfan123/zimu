package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把「来源回填文件」通过企业微信投递给操作员。
 *
 * <p><b>补的是哪一段</b>：运单回填后
 * {@code TrackingFileService.finalizeReadySourceReturnsForShipment} 已按平台原始列格式
 * 生成回填文件（含物流公司/物流单号），随后 {@code SourceReturnPushService} 负责回传来源平台。
 * 但那条路只对有在线回传能力的渠道成立（彩食鲜 importDeliverExcl / 聚福宝 multi-send）；
 * 飞象、大者、中汇的 {@code ConnectorCapabilities.onlinePush=false}，文件生成后无处可去。
 * 本服务就补这一段：**把文件发到企微，由人转交平台**。
 *
 * <p><b>刻意与回填事务解耦</b>：不挂在
 * {@code ShipmentJdTrackingBackfillService} 的回填路径上。那里在 {@code @Transactional} 内
 * 且无 try/catch——发文件失败会连带回滚已经成功的运单回填。回填是业务事实，
 * 发文件只是送达回执，后者绝不该反噬前者。故用扫描器异步领取。
 *
 * <p><b>不盲目重发</b>：企微 ack 超时可能已送达（见 {@code WecomSendResult} 契约），
 * 因此只有 {@code retryable=true}（帧未提交）才回到可重试态，其余一律 FAILED 等人工判断。
 */
@Service
public class SourceReturnWecomDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(SourceReturnWecomDeliveryService.class);

    private final JdbcTemplate jdbc;
    private final ContentAddressedFileStore files;
    private final WecomOutboundGateway gateway;

    public SourceReturnWecomDeliveryService(
            JdbcTemplate jdbc, ContentAddressedFileStore files, WecomOutboundGateway gateway) {
        this.jdbc = jdbc;
        this.files = files;
        this.gateway = gateway;
    }

    /** 待投递的回填文件：最终版、且所属渠道没有在线回传能力。 */
    public record Candidate(
            long exportId,
            long importBatchId,
            String sourceChannel,
            String fileRef,
            String originalFileName,
            int versionNo) {

        /**
         * 实际产物的扩展名，取自 file_ref（存储时按渠道决定 .csv/.xlsx）。
         *
         * <p>不能按渠道再推一遍，更不能写死 .xlsx——飞象的回填产物是 CSV，
         * 之前一律叫 .xlsx，收件人拿到的是扩展名对不上内容的文件。
         */
        String extension() {
            int dot = fileRef == null ? -1 : fileRef.lastIndexOf('.');
            return dot < 0 ? ".xlsx" : fileRef.substring(dot);
        }
    }

    /**
     * 领取候选。只挑 {@code is_final} 的版本——非最终版意味着批次还没回填齐，
     * 这时候发出去会让人以为已经完事。
     *
     * <p>渠道过滤在调用方做（能力声明在 Java 侧，SQL 里没有），这里只按状态取。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Candidate> pending(int limit) {
        return jdbc.query(
                """
                SELECT sre.id, sre.import_batch_id, ib.source_channel, sre.file_ref,
                       ib.original_file_name, sre.version_no
                FROM app.source_return_exports sre
                JOIN app.import_batches ib ON ib.id = sre.import_batch_id
                WHERE sre.is_final = true
                  AND sre.wecom_delivery_status = 'NOT_SENT'
                  AND sre.file_ref NOT LIKE 'structured://%'
                ORDER BY sre.id
                LIMIT ?
                """,
                (rs, row) -> new Candidate(
                        rs.getLong("id"),
                        rs.getLong("import_batch_id"),
                        rs.getString("source_channel"),
                        rs.getString("file_ref"),
                        rs.getString("original_file_name"),
                        rs.getInt("version_no")),
                Math.max(1, Math.min(limit, 50)));
    }

    /** 抢占投递意图；CAS 失败表示别的实例已领走。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(long exportId) {
        return jdbc.update(
                """
                UPDATE app.source_return_exports
                SET wecom_delivery_status='SENDING',
                    wecom_delivery_started_at=CURRENT_TIMESTAMP,
                    wecom_error=NULL
                WHERE id=? AND wecom_delivery_status='NOT_SENT'
                """,
                exportId) == 1;
    }

    /**
     * 事务外执行上传与发送。文件名带渠道与批次号，收件人一眼能对上是哪一批。
     *
     * @return 送达成功与否；失败原因已写入该行 {@code wecom_error}
     */
    public boolean deliver(Candidate candidate, String chatId) {
        Path temp = null;
        try {
            byte[] bytes = files.read(candidate.fileRef());
            // 收件人多半要把这个文件原样传回来源平台，所以保住平台原名，只追加后缀。
            String filename = SourceReturnFileNaming.fileName(
                    candidate.originalFileName(),
                    candidate.sourceChannel(),
                    candidate.versionNo(),
                    candidate.extension());
            temp = Files.createTempFile("zimu-source-return-", candidate.extension());
            Files.write(temp, bytes);

            WecomUploadResult upload = gateway.upload(temp, filename, WecomMediaType.FILE);
            if (upload.mediaId() == null || upload.mediaId().isBlank()) {
                fail(candidate.exportId(), "UPLOAD_" + upload.status(), false);
                return false;
            }
            WecomSendResult sent = gateway.send(WecomOutboundMessage.file(chatId, upload.mediaId()));
            if (sent.status() == WecomSendStatus.SUCCESS) {
                succeed(candidate.exportId(), chatId, sha256(upload.mediaId()));
                log.info("来源回填文件已投递企微 export={} batch={}", candidate.exportId(), candidate.importBatchId());
                return true;
            }
            // ack 超时/提交后断线可能已送达——只有帧未提交才允许回到可重试态
            fail(candidate.exportId(), "SEND_" + sent.status(), sent.retryable());
            return false;
        } catch (IOException | RuntimeException exception) {
            fail(candidate.exportId(), exception.getClass().getSimpleName(), true);
            log.warn(
                    "来源回填文件投递异常 export={} type={} message={}",
                    candidate.exportId(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件交给系统回收，不影响投递结果
                }
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void succeed(long exportId, String chatId, String mediaIdSha256) {
        jdbc.update(
                """
                UPDATE app.source_return_exports
                SET wecom_delivery_status='SENT', wecom_delivered_at=CURRENT_TIMESTAMP,
                    wecom_chat_id=?, wecom_media_id_sha256=?, wecom_error=NULL
                WHERE id=?
                """,
                chatId, mediaIdSha256, exportId);
    }

    /** 失败落稳定码；retryable 才回 NOT_SENT，否则停在 FAILED 等人工判断（防重复送达）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(long exportId, String code, boolean retryable) {
        jdbc.update(
                """
                UPDATE app.source_return_exports
                SET wecom_delivery_status=?, wecom_error=?
                WHERE id=?
                """,
                retryable ? "NOT_SENT" : "FAILED", code, exportId);
    }

    /** media_id 是 3 天期凭据，按既有纪律只落哈希不落明文。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
