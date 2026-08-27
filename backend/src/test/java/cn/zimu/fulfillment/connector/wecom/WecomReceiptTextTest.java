package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 企微即时回执文案。
 *
 * <p>发文件的人接下来要等十几秒才知道结果（下载 → 解密 → 认模板 → 解析），
 * 这期间只回「已接收」两个字，读起来像石沉大海。本测试守的是：
 * 文件必须给出比「已接收」更多的信息。
 */
class WecomReceiptTextTest {

    @Test
    void 文件回执要说清楚在做什么_而不是干巴巴两个字() {
        String text = WecomMessageDispatchHandler.receiptText("file");

        assertThat(text)
                .isEqualTo(WecomMessageDispatchHandler.FILE_RECEIPT_TEXT)
                .contains("已收到")
                .contains("稍后")
                .isNotEqualTo(WecomMessageDispatchHandler.RECEIPT_TEXT);
    }

    @Test
    void 非文件消息保持原有回执_不改既有行为() {
        for (String msgType : new String[] {"text", "image", "voice", "", null}) {
            assertThat(WecomMessageDispatchHandler.receiptText(msgType))
                    .as("msgType=%s", msgType)
                    .isEqualTo(WecomMessageDispatchHandler.RECEIPT_TEXT);
        }
    }

    @Test
    void 回执不承诺任何做不到的事_企微文件帧里没有文件名() {
        // aibot_msg_callback 的 file 载荷只有 url/aeskey/md5sum，没有文件名。
        // 回执里一旦出现「收到了 xxx.xlsx」，就是在猜。
        assertThat(WecomMessageDispatchHandler.FILE_RECEIPT_TEXT)
                .doesNotContain(".xlsx")
                .doesNotContain(".xls")
                .doesNotContain("文件名");
    }
}
