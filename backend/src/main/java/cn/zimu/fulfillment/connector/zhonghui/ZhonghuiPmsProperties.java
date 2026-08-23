package cn.zimu.fulfillment.connector.zhonghui;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 中汇好泰 PMS 商品录入客户端配置（管理面契约见 {@code docs/openapi.yaml}）。
 *
 * <p>凭据只经环境变量注入（{@code ZHONGHUI_PMS_*}，见 {@code .env.example}），绝不出现在日志、
 * 数据库或 API 响应中。`client-mode=MOCK`（默认）使用本地假客户端，不触网；`REAL` 时才连接
 * {@code pms.zhonghuihaotai.com}。
 *
 * <p>{@code defaults} 是创建商品时的全局默认值（对应供应商创建商品接口中仍需确认的字段：
 * brandId / certificationType / certificationId / thirdId / limitAreaTempId / goodsTax /
 * logisticsCarrier / producingArea 等），每个批次请求仍可按商品覆盖。
 */
@Component
@ConfigurationProperties(prefix = "app.zhonghui-pms")
public class ZhonghuiPmsProperties {

    private String clientMode = "MOCK";
    private String writeMode = "OFF";
    private String baseUrl = "";
    private String username = "";
    private String password = "";
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration idempotencyLease = Duration.ofMinutes(40);
    private Defaults defaults = new Defaults();

    public String getClientMode() {
        return clientMode;
    }

    public void setClientMode(String clientMode) {
        this.clientMode = clientMode;
    }

    public String getWriteMode() {
        return writeMode;
    }

    public void setWriteMode(String writeMode) {
        this.writeMode = writeMode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getIdempotencyLease() {
        return idempotencyLease;
    }

    public void setIdempotencyLease(Duration idempotencyLease) {
        this.idempotencyLease = idempotencyLease;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public boolean credentialsConfigured() {
        return present(baseUrl) && present(username) && present(password);
    }

    /** 只有 REAL 连接模式和显式 ON 写开关同时成立，才允许触发外部写。 */
    public boolean externalWritesEnabled() {
        return "REAL".equalsIgnoreCase(clientMode) && "ON".equalsIgnoreCase(writeMode);
    }

    /**
     * MOCK 不触网，保留本地联调能力；REAL 模式的每个写入口都必须先通过第二道写门闩。
     */
    public void requireExternalWritesEnabled() {
        if ("REAL".equalsIgnoreCase(clientMode) && !externalWritesEnabled()) {
            throw BusinessException.forbidden(
                    "ZHONGHUI_PMS_WRITE_MODE_DISABLED",
                    "中汇 PMS 外部写入未启用，请显式设置 ZHONGHUI_PMS_WRITE_MODE=ON");
        }
    }

    /** REAL 模式、写门闩开启且登录凭据齐备时才算外部写就绪；MOCK 始终不算 live。 */
    public boolean liveReady() {
        return externalWritesEnabled() && credentialsConfigured();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** 创建商品的全局默认字段；值为 null 表示未配置，由批量上传请求的 overrides 或兜底值补齐。 */
    public static class Defaults {

        private Integer brandId;
        private Integer certificationType;
        private Integer certificationId;
        private Integer thirdId;
        private Integer limitAreaTempId;
        private BigDecimal goodsTax;
        private String logisticsCarrier;
        private String producingArea;
        private Integer goodsNum = 99;
        private String saleUnit = "件";
        private Integer origincountry = 1;

        public Integer getBrandId() {
            return brandId;
        }

        public void setBrandId(Integer brandId) {
            this.brandId = brandId;
        }

        public Integer getCertificationType() {
            return certificationType;
        }

        public void setCertificationType(Integer certificationType) {
            this.certificationType = certificationType;
        }

        public Integer getCertificationId() {
            return certificationId;
        }

        public void setCertificationId(Integer certificationId) {
            this.certificationId = certificationId;
        }

        public Integer getThirdId() {
            return thirdId;
        }

        public void setThirdId(Integer thirdId) {
            this.thirdId = thirdId;
        }

        public Integer getLimitAreaTempId() {
            return limitAreaTempId;
        }

        public void setLimitAreaTempId(Integer limitAreaTempId) {
            this.limitAreaTempId = limitAreaTempId;
        }

        public BigDecimal getGoodsTax() {
            return goodsTax;
        }

        public void setGoodsTax(BigDecimal goodsTax) {
            this.goodsTax = goodsTax;
        }

        public String getLogisticsCarrier() {
            return logisticsCarrier;
        }

        public void setLogisticsCarrier(String logisticsCarrier) {
            this.logisticsCarrier = logisticsCarrier;
        }

        public String getProducingArea() {
            return producingArea;
        }

        public void setProducingArea(String producingArea) {
            this.producingArea = producingArea;
        }

        public Integer getGoodsNum() {
            return goodsNum;
        }

        public void setGoodsNum(Integer goodsNum) {
            this.goodsNum = goodsNum;
        }

        public String getSaleUnit() {
            return saleUnit;
        }

        public void setSaleUnit(String saleUnit) {
            this.saleUnit = saleUnit;
        }

        public Integer getOrigincountry() {
            return origincountry;
        }

        public void setOrigincountry(Integer origincountry) {
            this.origincountry = origincountry;
        }
    }
}
