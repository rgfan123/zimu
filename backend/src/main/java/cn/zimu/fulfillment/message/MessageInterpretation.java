package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.jpa.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 一次解释的版本化派生结果；重跑追加版本，不覆盖历史。 */
@Entity
@Table(name = "message_interpretations")
public class MessageInterpretation extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false)
    private MessageIntent intent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_output", nullable = false)
    private Map<String, Object> structuredOutput = new LinkedHashMap<>();

    @Column(name = "error")
    private String error;

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(Long submissionId) {
        this.submissionId = submissionId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public MessageIntent getIntent() {
        return intent;
    }

    public void setIntent(MessageIntent intent) {
        this.intent = intent;
    }

    public Map<String, Object> getStructuredOutput() {
        return structuredOutput;
    }

    public void setStructuredOutput(Map<String, Object> structuredOutput) {
        this.structuredOutput = structuredOutput;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
