package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WecomTrackingFilePublicProjectionTest {

    @Test
    void fileFailureReviewExposesOnlyStableCodeAndServerOwnedMessage() {
        Map<String, Object> projected = MessagePublicProjectionSanitizer.reviewCaseDetail(
                42L,
                WecomTrackingFileFailureCode.REVIEW_REASON,
                Map.of(
                        "error_code", "WECOM_TRACKING_FILE_TOO_LARGE",
                        "message", "attacker controlled text",
                        "source_url", "https://temporary.example/secret",
                        "aeskey", "must-not-leak"),
                new MessageModelMetadataRegistry());

        assertThat(projected).containsOnly(
                Map.entry("source", "WECOM_TRACKING_FILE"),
                Map.entry("error_code", "WECOM_TRACKING_FILE_TOO_LARGE"),
                Map.entry("message", "运单文件超过 20MB 上限，请拆分后重新发送"));
    }

    @Test
    void unknownHistoricalFileFailureFailsClosedToStableProcessingCode() {
        Map<String, Object> projected = MessagePublicProjectionSanitizer.reviewCaseDetail(
                43L,
                WecomTrackingFileFailureCode.REVIEW_REASON,
                Map.of("error_code", "raw exception secret=abc"),
                new MessageModelMetadataRegistry());

        assertThat(projected)
                .containsEntry("error_code", "WECOM_TRACKING_FILE_PROCESSING_FAILED")
                .containsEntry("message", "运单文件处理失败，请人工复核并重试")
                .doesNotContainValue("raw exception secret=abc");
    }

    @Test
    void fileDraftReviewDoesNotExposeProviderFreeTextOrInternalEvidence() {
        Map<String, Object> projected = MessagePublicProjectionSanitizer.reviewCaseDetail(
                null,
                WecomTrackingDraftFactory.REASON_TRACKING_DRAFT,
                Map.of(
                        "source", "WECOM_TRACKING_FILE",
                        "draft_id", "42",
                        "provider_failure_reason", "收件人手机号 13800000000 secret=abc",
                        "file_shipment_items", java.util.List.of(Map.of("fulfillment_id", "501"))),
                new MessageModelMetadataRegistry());

        assertThat(projected).containsOnly(Map.entry("source", "WECOM_TRACKING_FILE"));
    }
}
