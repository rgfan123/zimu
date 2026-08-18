package cn.zimu.fulfillment.message;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NON_BUSINESS 消息保留策略配置（wecom-message-intake 12，US 52-53）。
 *
 * <p>默认保留 30 天；{@code nonBusinessDays < 1} 时清理任务整体禁用（定时与手动入口都返回空报告）。
 * 清理任务可重复运行，每次运行记录审计摘要。
 */
@Component
@ConfigurationProperties(prefix = "app.message-retention")
public class MessageRetentionProperties {

    private int nonBusinessDays = 30;
    private String cron = "0 30 3 * * *";

    public int getNonBusinessDays() {
        return nonBusinessDays;
    }

    public void setNonBusinessDays(int nonBusinessDays) {
        this.nonBusinessDays = nonBusinessDays;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public boolean isEnabled() {
        return nonBusinessDays >= 1;
    }
}
