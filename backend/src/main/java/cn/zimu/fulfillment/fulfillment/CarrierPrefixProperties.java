package cn.zimu.fulfillment.fulfillment;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可维护的 Carrier 前缀映射配置（确定性主数据，不写入模型提示词）。
 *
 * <p>{@code app.carrier-prefixes.carriers}：Carrier 内部代码 → 名称与启用状态。运单前缀规则自
 * V21 起由数据库的版本化映射集权威管理，本配置不再接受可绕过审计的前缀映射。
 */
@Component
@ConfigurationProperties(prefix = "app.carrier-prefixes")
public class CarrierPrefixProperties {

    /** Carrier 内部代码 → 主数据条目（名称、启用状态）。 */
    private Map<String, CarrierEntry> carriers = new LinkedHashMap<>();

    public Map<String, CarrierEntry> getCarriers() {
        return carriers;
    }

    public void setCarriers(Map<String, CarrierEntry> carriers) {
        this.carriers = carriers;
    }

    public static class CarrierEntry {

        private String name;
        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
