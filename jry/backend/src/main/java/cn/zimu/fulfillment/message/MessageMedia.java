package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一条渠道媒体证据：下载状态、受控文件引用、哈希、内容类型、大小、解密信息与失败原因。
 *
 * <p>幂等键为 {@code (channel_message_id, channel_media_id)}（V8 唯一约束）；受控存储按明文内容
 * 哈希寻址、不可变复用，原件不可被识别结果覆盖。下载凭据（url/aeskey）不投影、不在本表留存。
 */
@Entity
@Table(name = "message_media")
public class MessageMedia extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "channel_message_id")
    private Long channelMessageId;

    @Column(name = "channel_media_id", nullable = false)
    private String channelMediaId;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "download_status", nullable = false)
    private MediaDownloadStatus downloadStatus = MediaDownloadStatus.PENDING;

    @Column(name = "content_ref")
    private String contentRef;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decrypt_info")
    private Map<String, Object> decryptInfo;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(Long submissionId) {
        this.submissionId = submissionId;
    }

    public Long getChannelMessageId() {
        return channelMessageId;
    }

    public void setChannelMessageId(Long channelMessageId) {
        this.channelMessageId = channelMessageId;
    }

    public String getChannelMediaId() {
        return channelMediaId;
    }

    public void setChannelMediaId(String channelMediaId) {
        this.channelMediaId = channelMediaId;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public MediaDownloadStatus getDownloadStatus() {
        return downloadStatus;
    }

    public void setDownloadStatus(MediaDownloadStatus downloadStatus) {
        this.downloadStatus = downloadStatus;
    }

    public String getContentRef() {
        return contentRef;
    }

    public void setContentRef(String contentRef) {
        this.contentRef = contentRef;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Map<String, Object> getDecryptInfo() {
        return decryptInfo;
    }

    public void setDecryptInfo(Map<String, Object> decryptInfo) {
        this.decryptInfo = decryptInfo;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }
}
