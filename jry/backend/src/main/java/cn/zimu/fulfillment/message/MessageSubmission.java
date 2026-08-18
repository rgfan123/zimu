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

/** 进入意图识别的一次显式业务提交；默认一条 @机器人 消息形成一个提交。 */
@Entity
@Table(name = "message_submissions")
public class MessageSubmission extends AuditableEntity {

    public enum Status {
        RECEIVED,
        INTERPRETED,
        FAILED,
        DRAFTED,
        CONFIRMED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_no", nullable = false)
    private String submissionNo;

    @Column(name = "source_message_id", nullable = false)
    private Long sourceMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.RECEIVED;

    public Long getId() {
        return id;
    }

    public String getSubmissionNo() {
        return submissionNo;
    }

    public void setSubmissionNo(String submissionNo) {
        this.submissionNo = submissionNo;
    }

    public Long getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(Long sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}
