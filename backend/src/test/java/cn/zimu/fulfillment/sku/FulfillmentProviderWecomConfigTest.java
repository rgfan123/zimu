package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #84: 履约方提醒间隔配置键（wecomReminderIntervalMinutes）的契约唯一解析。 */
class FulfillmentProviderWecomConfigTest {

    @Test
    void missingOrNullReminderIntervalDefaultsToSlaSnapshot() {
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(null, 1440)).isEqualTo(1440);
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(Map.of(), 60)).isEqualTo(60);
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY, null);
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(withNull, 60)).isEqualTo(60);
        // 非法存量值（如历史脏数据）同样回退到 SLA 默认，不向消费侧输出非法值
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(
                Map.of(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY, "abc"), 90)).isEqualTo(90);
    }

    @Test
    void explicitReminderIntervalIsAcceptedWithinOneTo10080() {
        Map<String, Object> config = Map.of(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY, 30);
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(config, 1440)).isEqualTo(30);
        Map<String, Object> max = Map.of(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY, 10080);
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(max, 60)).isEqualTo(10080);
        Map<String, Object> min = Map.of(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY, 1);
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(min, 60)).isEqualTo(1);
    }

    @Test
    void invalidReminderIntervalValuesAreRejectedOnWrite() {
        for (Object invalid : new Object[] {0, -5, 10081, 1.5, "30", true}) {
            assertThatThrownBy(() -> FulfillmentProviderWecomConfig.validateReminderInterval(invalid))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                    .isEqualTo(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_INVALID_ERROR_CODE);
        }
        // null 表示清除（恢复默认 = SLA），不是非法值
        assertThat(FulfillmentProviderWecomConfig.validateReminderInterval(null)).isNull();
    }

    @Test
    void reminderIntervalParsingLivesOnlyInTheWecomConfigContract() {
        // 键名唯一归属：代码中不得再出现该字符串字面量（除契约模块本身）
        assertThat(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY).isEqualTo("wecomReminderIntervalMinutes");
        // 与群 chatid 键共存互不影响
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(FulfillmentProviderWecomConfig.GROUP_CHAT_ID_KEY, "wrJgVnTQAAD001");
        config.put(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY, 120);
        assertThat(FulfillmentProviderWecomConfig.requireGroupChatId(config, "TP")).isEqualTo("wrJgVnTQAAD001");
        assertThat(FulfillmentProviderWecomConfig.requireReminderInterval(config, 1440)).isEqualTo(120);
    }
}
