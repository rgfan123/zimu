package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.message.IntentRouter;
import cn.zimu.fulfillment.message.MessageModelMetadataRegistry;
import cn.zimu.fulfillment.message.MessagePublicProjectionSanitizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessFollowUpPublicProjectionTest {

    @Test
    void followUpFailuresKeepKnownStableCodeAndCollapseRawErrors() {
        assertThat(BusinessFollowUpFailureProjection.project("KEHUZX_TIMEOUT"))
                .isEqualTo("KEHUZX_TIMEOUT");
        assertThat(BusinessFollowUpFailureProjection.project("secret=raw socket failure"))
                .isEqualTo("FOLLOWUP_ORGANIZATION_FAILED");
    }

    @Test
    void reviewProjectionKeepsOnlyValidatedFollowUpTraceFields() {
        Map<String, Object> projected = MessagePublicProjectionSanitizer.reviewCaseDetail(
                42L,
                IntentRouter.REASON_NEED_REVIEW,
                Map.of(
                        "business_followup_id", "123",
                        "followup_reason_code", "KEHUZX_TIMEOUT",
                        "followup_trace_id", "run_12345678",
                        "agent_run_id", "run_12345678",
                        "phone", "13800000000",
                        "raw_error", "secret=must-not-leak"),
                new MessageModelMetadataRegistry());

        assertThat(projected)
                .containsEntry("business_followup_id", "123")
                .containsEntry("followup_reason_code", "KEHUZX_TIMEOUT")
                .containsEntry("followup_trace_id", "run_12345678")
                .containsEntry("agent_run_id", "run_12345678")
                .doesNotContainKeys("phone", "raw_error")
                .doesNotContainValue("13800000000")
                .doesNotContainValue("secret=must-not-leak");
    }

    @Test
    void modelInputKeepsOnlyAllowlistedBusinessIdentifiersAndDropsFreeTextPii() {
        List<String> identifiers = BusinessFollowUpOrganizationService.customerIdentifiersForModel(
                "张三说送到海淀区知春路27号，手机 138 0000 0000，邮箱 buyer@example.com；"
                        + "请核对客户 KH-260826-001 和订单 FO-20260826-19");

        assertThat(identifiers)
                .containsExactly("KH-260826-001")
                .allSatisfy(value -> assertThat(value)
                        .doesNotContain("张三", "知春路", "138", "buyer"));
    }
}
