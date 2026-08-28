package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 写响应判定与写后回查判据。
 *
 * <p>核心安全属性：<b>fail-closed 三值</b>。飞象的失败文案库整个没有采样过，
 * 「非 0 即成功」这种 fail-open 判法会把未知平台行为记成已发货。</p>
 */
class FeixiangShipmentResponseTest {

    // ---------------------------------------------------------------- 写响应

    @Test
    void statusOneIsTheOnlyAcceptedShape() {
        assertThat(FeixiangHttpShipmentGateway.interpret("{\"status\":1,\"msg\":\"\",\"data\":[]}").outcome())
                .isEqualTo(FeixiangShipmentGateway.Outcome.ACCEPTED);
        assertThat(FeixiangHttpShipmentGateway.interpret("{\"status\":\"1\",\"msg\":\"\"}").outcome())
                .isEqualTo(FeixiangShipmentGateway.Outcome.ACCEPTED);
    }

    @Test
    void statusZeroIsRejectedAndSafeToRetry() {
        FeixiangShipmentGateway.SubmitResult result =
                FeixiangHttpShipmentGateway.interpret("{\"status\":0,\"msg\":\"运单被拒绝\"}");

        assertThat(result.outcome()).isEqualTo(FeixiangShipmentGateway.Outcome.REJECTED);
        assertThat(result.businessCode()).isEqualTo("FEIXIANG_SHIPMENT_REJECTED");
        assertThat(result.message()).isEqualTo("运单被拒绝");
    }

    @Test
    void anyUnsampledStatusBecomesUnknownRatherThanSuccessOrRetryableFailure() {
        assertThat(FeixiangHttpShipmentGateway.interpret("{\"status\":2,\"msg\":\"x\"}").outcome())
                .isEqualTo(FeixiangShipmentGateway.Outcome.UNKNOWN);
        assertThat(FeixiangHttpShipmentGateway.interpret("{\"msg\":\"x\"}").outcome())
                .isEqualTo(FeixiangShipmentGateway.Outcome.UNKNOWN);
        assertThat(FeixiangHttpShipmentGateway.interpret("{\"status\":null}").outcome())
                .isEqualTo(FeixiangShipmentGateway.Outcome.UNKNOWN);
    }

    @Test
    void loginPageHtmlIsUnknownAndNeverEchoed() {
        FeixiangShipmentGateway.SubmitResult result = FeixiangHttpShipmentGateway.interpret(
                "<html><body><form><input name=\"password\"></form></body></html>");

        assertThat(result.outcome()).isEqualTo(FeixiangShipmentGateway.Outcome.UNKNOWN);
        assertThat(result.message()).doesNotContain("password").doesNotContain("<");
    }

    @Test
    void emptyOrMalformedBodyIsUnknown() {
        assertThat(FeixiangHttpShipmentGateway.interpret("").outcome())
                .isEqualTo(FeixiangShipmentGateway.Outcome.UNKNOWN);
        assertThat(FeixiangHttpShipmentGateway.interpret("not json").outcome())
                .isEqualTo(FeixiangShipmentGateway.Outcome.UNKNOWN);
    }

    @Test
    void platformMessageIsSanitizedBeforeItCanReachLastErrorMessage() {
        FeixiangShipmentGateway.SubmitResult result =
                FeixiangHttpShipmentGateway.interpret("{\"status\":0,\"msg\":\"  运单被拒绝\\n请复核  \"}");

        assertThat(result.message()).isEqualTo("运单被拒绝 请复核");
    }

    // ---------------------------------------------------------------- 写后回查

    @Test
    void confirmedOnlyWhenTrackingNumberMatchesExactly() {
        FeixiangShipmentGateway.VerifyResult verified = FeixiangShipmentGateway.verifyOnce(
                FeixiangShipmentTestSupport.detail("JDVA46783539436", "jingdong"),
                Set.of(FeixiangShipmentTestSupport.ORDER_PRODUCT_ID),
                "JDVA46783539436");

        assertThat(verified.state()).isEqualTo(FeixiangShipmentGateway.VerifyState.CONFIRMED);
    }

    @Test
    void someoneElsesTrackingNumberIsNotOurSuccess() {
        // 「这一行有运单号」不够——alreadyShipped() 那一档判据在写后回查这里太松。
        FeixiangShipmentGateway.VerifyResult verified = FeixiangShipmentGateway.verifyOnce(
                FeixiangShipmentTestSupport.detail("SF9999", "shunfeng"),
                Set.of(FeixiangShipmentTestSupport.ORDER_PRODUCT_ID),
                "JDVA46783539436");

        assertThat(verified.state()).isEqualTo(FeixiangShipmentGateway.VerifyState.NOT_CONFIRMED);
    }

    @Test
    void blankTrackingOnPlatformIsNotConfirmed() {
        FeixiangShipmentGateway.VerifyResult verified = FeixiangShipmentGateway.verifyOnce(
                FeixiangShipmentTestSupport.detail("", ""),
                Set.of(FeixiangShipmentTestSupport.ORDER_PRODUCT_ID),
                "JDVA46783539436");

        assertThat(verified.state()).isEqualTo(FeixiangShipmentGateway.VerifyState.NOT_CONFIRMED);
    }

    @Test
    void missingTargetLineIsUnknownNotConfirmed() {
        FeixiangShipmentGateway.VerifyResult verified = FeixiangShipmentGateway.verifyOnce(
                FeixiangShipmentTestSupport.detail("JDVA46783539436", "jingdong"),
                Set.of("99999999"),
                "JDVA46783539436");

        assertThat(verified.state()).isEqualTo(FeixiangShipmentGateway.VerifyState.UNKNOWN);
    }

    @Test
    void unreadableDetailIsUnknown() {
        assertThat(FeixiangShipmentGateway.verifyOnce(null, Set.of("1"), "JDVA1").state())
                .isEqualTo(FeixiangShipmentGateway.VerifyState.UNKNOWN);
    }
}
