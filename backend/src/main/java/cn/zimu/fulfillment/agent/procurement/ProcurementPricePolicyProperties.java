package cn.zimu.fulfillment.agent.procurement;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 采购比价 Agent 的不可比候选策略配置（01 票）：{@code app.agent.procurement-price.*}。
 *
 * <p>离群判定倍数是配置项而非魔法数字：默认 {@value ProcurementPricePolicy#PRICE_OUTLIER_MULTIPLE}
 * （依据见 {@link ProcurementPricePolicy#PRICE_OUTLIER_MULTIPLE} 的 javadoc），可通过
 * {@code app.agent.procurement-price.outlier-multiple} 覆盖；运行时把该值注入
 * {@link ProcurementPricePolicy#enforce(ProcurementPriceRecommendation, double)}。
 */
@Component
@ConfigurationProperties(prefix = "app.agent.procurement-price")
public class ProcurementPricePolicyProperties {

    private double outlierMultiple = ProcurementPricePolicy.PRICE_OUTLIER_MULTIPLE;

    public double getOutlierMultiple() {
        return outlierMultiple;
    }

    public void setOutlierMultiple(double outlierMultiple) {
        this.outlierMultiple = outlierMultiple;
    }
}
