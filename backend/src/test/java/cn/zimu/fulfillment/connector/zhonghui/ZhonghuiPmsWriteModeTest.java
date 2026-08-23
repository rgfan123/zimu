package cn.zimu.fulfillment.connector.zhonghui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** #117: REAL 客户端的每个外部写入口都受默认关闭的第二道门闩保护。 */
class ZhonghuiPmsWriteModeTest {

    @Test
    void realClientRejectsEveryWriteOperationWhileWriteModeIsOff() {
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("REAL");
        properties.setWriteMode("OFF");
        properties.setBaseUrl("http://127.0.0.1:1");
        ZhonghuiPmsHttpClient client = new ZhonghuiPmsHttpClient(
                properties,
                new ZhonghuiPmsSession(),
                mock(AuditLogService.class),
                new ObjectMapper());

        assertWriteModeDisabled(() -> client.login(new LoginCommand("user", "password", "1234", "captcha")));
        assertWriteModeDisabled(() -> client.uploadImage(new byte[] {1}, "image/png"));
        assertWriteModeDisabled(() -> client.createGoods(mock(GoodsCreateCommand.class)));
    }

    private void assertWriteModeDisabled(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(403);
                    assertThat(exception.getBusinessCode()).isEqualTo("ZHONGHUI_PMS_WRITE_MODE_DISABLED");
                });
    }
}
