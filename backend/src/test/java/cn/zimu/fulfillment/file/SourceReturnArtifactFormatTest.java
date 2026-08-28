package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import org.junit.jupiter.api.Test;

/**
 * 来源回填产物格式的单一真源：同一份 export，HTTP 下载与企微投递必须给出一致的扩展名，
 * 且与 template_version 蕴含的实际格式一致。
 *
 * <p>2026-08-28 生产实证：飞象 export 的 template_version='v2-gb18030-lf'（内容是 GB18030 CSV），
 * 下载路径给 .csv 是对的，企微投递却写死 .xlsx。两条出口对同一份字节给出不同扩展名，
 * 用户拿到手用 Excel 打不开。判定收敛到一处后，分叉应当被测试抓住而不是被用户抓住。
 */
class SourceReturnArtifactFormatTest {

    @Test
    void feixiangV2IsGb18030Csv() {
        SourceReturnArtifactFormat format = SourceReturnArtifactFormat.of("FEIXIANG", "v2-gb18030-lf-40col");
        assertThat(format.extension()).isEqualTo(".csv");
        assertThat(format.contentType()).isEqualTo("text/csv;charset=GB18030");
    }

    @Test
    void feixiangV1IsUtf8Csv() {
        SourceReturnArtifactFormat format = SourceReturnArtifactFormat.of("FEIXIANG", "v1-utf8");
        assertThat(format.extension()).isEqualTo(".csv");
        assertThat(format.contentType()).isEqualTo("text/csv;charset=UTF-8");
    }

    @Test
    void caishixianIsXlsx() {
        SourceReturnArtifactFormat format = SourceReturnArtifactFormat.of("CAISHIXIAN", "1");
        assertThat(format.extension()).isEqualTo(".xlsx");
        assertThat(format.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void nullTemplateVersionDoesNotBlowUp() {
        assertThat(SourceReturnArtifactFormat.of("FEIXIANG", null).extension()).isEqualTo(".csv");
        assertThat(SourceReturnArtifactFormat.of("CAISHIXIAN", null).extension()).isEqualTo(".xlsx");
    }

    /**
     * 两条出口读的是同一份判定，所以「下载的扩展名」与「投递的扩展名」在构造上就不可能分叉。
     * 这条用飞象 v2（CSV）与彩食鲜（xlsx）各造一例，钉住一致性本身。
     */
    @Test
    void bothExitsAgreeOnTheExtensionForEveryChannel() {
        for (String[] channelAndTemplate : new String[][] {
                {"FEIXIANG", "v2-gb18030-lf-40col"},
                {"CAISHIXIAN", "1"}}) {
            String channel = channelAndTemplate[0];
            String templateVersion = channelAndTemplate[1];
            String download = SourceReturnArtifactFormat.of(channel, templateVersion).extension();
            String wecomDelivery = new SourceReturnWecomDeliveryService.Candidate(
                    1L, 2L, channel, "/store/abc" + download, "订单导出.xlsx", 1, templateVersion).extension();
            assertThat(wecomDelivery)
                    .withFailMessage("下载与企微投递的扩展名必须一致: channel=%s", channel)
                    .isEqualTo(download);
        }
    }

    /**
     * 修正扩展名不能反而被企微本地校验拒收：投递用的每个扩展名都必须在 file 类型允许集里。
     */
    @Test
    void everyDeliverableExtensionIsAcceptedByTheWecomFileUploader() {
        for (String[] channelAndTemplate : new String[][] {
                {"FEIXIANG", "v2-gb18030-lf-40col"},
                {"FEIXIANG", "v1-utf8"},
                {"CAISHIXIAN", "1"}}) {
            String extension = SourceReturnArtifactFormat
                    .of(channelAndTemplate[0], channelAndTemplate[1]).extension();
            assertThat(WecomMediaType.FILE.allowedExtensions())
                    .withFailMessage("企微 file 上传不接受 %s，投递会被本地校验直接拒收", extension)
                    .contains(extension.substring(1));
        }
    }
}
