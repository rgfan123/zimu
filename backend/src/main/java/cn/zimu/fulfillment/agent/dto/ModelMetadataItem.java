package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;

/**
 * 运行记录携带的模型元数据的服务端 allowlist 投影（12 票消费方要求 5）。
 *
 * <p>provider/model/prompt-version 只能经 {@link AgentModelMetadataRegistry} 投影后暴露
 * （红线：密钥/凭据绝不进 DTO）。投影结果保留三种可区分形态，避免「未命中 allowlist」
 * 与「未配置」折叠成同一个空值（界面文案不同）：
 * <ul>
 *   <li>{@link Visibility#EXPOSED}——存储三元组命中服务端 allowlist，暴露真实值；</li>
 *   <li>{@link Visibility#NOT_PUBLIC}——存储三元组存在但未命中 allowlist，折叠为
 *       none/none/none（元数据存在、因服务端登记缺失而不公开）；</li>
 *   <li>{@link Visibility#NOT_CONFIGURED}——存储三元组本身就是 none/none/none
 *       （该运行未携带任何模型元数据，如模型未配置或未到达模型调用）。</li>
 * </ul>
 */
public record ModelMetadataItem(
        String provider, String model, String promptVersion, Visibility visibility) {

    public enum Visibility {
        EXPOSED,
        NOT_PUBLIC,
        NOT_CONFIGURED
    }

    private static final String NONE = "none";

    /** 对存储三元组做 allowlist 投影并分类（null/空白按 none 归一）。 */
    public static ModelMetadataItem project(
            String provider, String model, String promptVersion, AgentModelMetadataRegistry registry) {
        String p = noneOr(provider);
        String m = noneOr(model);
        String v = noneOr(promptVersion);
        if (NONE.equals(p) && NONE.equals(m) && NONE.equals(v)) {
            return new ModelMetadataItem(NONE, NONE, NONE, Visibility.NOT_CONFIGURED);
        }
        AgentModelMetadataRegistry.PublicMetadata projected = registry.publicProjection(p, m, v);
        if (NONE.equals(projected.provider())
                && NONE.equals(projected.model())
                && NONE.equals(projected.promptVersion())) {
            return new ModelMetadataItem(NONE, NONE, NONE, Visibility.NOT_PUBLIC);
        }
        return new ModelMetadataItem(
                projected.provider(), projected.model(), projected.promptVersion(), Visibility.EXPOSED);
    }

    private static String noneOr(String value) {
        return value == null || value.isBlank() ? NONE : value;
    }
}
