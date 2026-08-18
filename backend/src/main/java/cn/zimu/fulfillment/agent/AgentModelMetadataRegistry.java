package cn.zimu.fulfillment.agent;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 运行元数据的服务端 allowlist（agent-decision-layer 01）。
 *
 * <p>复用 {@code message/MessageModelMetadataRegistry} 的服务端 allowlist 模式：只有服务端
 * 登记且可发布的 provider/model/prompt-version 三元组才允许持久化或对外暴露；未白名单的
 * 三元组一律投影为 {@code none/none/none}（Agent 结果公共投影默认 none，白名单命中才暴露真实值）。
 */
@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentModelMetadataRegistry {

    private static final String NONE = "none";

    private List<PublicMetadataAlias> publicMetadataAliases = List.of();

    public List<PublicMetadataAlias> getPublicMetadataAliases() {
        return publicMetadataAliases;
    }

    public void setPublicMetadataAliases(List<PublicMetadataAlias> publicMetadataAliases) {
        this.publicMetadataAliases = publicMetadataAliases == null
                ? List.of()
                : List.copyOf(publicMetadataAliases);
    }

    /** Agent 输出永远不能自行添加别名；必须与服务端登记的三元组完全一致。 */
    public boolean allows(AgentRunResult result) {
        if (result == null) {
            return false;
        }
        if (isNone(result.provider(), result.model(), result.promptVersion())) {
            return result.error() != null && !result.error().isBlank();
        }
        return explicitlyAllows(result.provider(), result.model(), result.promptVersion());
    }

    /** 未白名单或已停用的运行元数据一律折叠为公共 sentinel。 */
    public PublicMetadata publicProjection(String provider, String model, String promptVersion) {
        if (isNone(provider, model, promptVersion)
                || explicitlyAllows(provider, model, promptVersion)) {
            return new PublicMetadata(provider, model, promptVersion);
        }
        return none();
    }

    public static PublicMetadata none() {
        return new PublicMetadata(NONE, NONE, NONE);
    }

    private boolean explicitlyAllows(String provider, String model, String promptVersion) {
        return publicMetadataAliases.stream()
                .filter(PublicMetadataAlias::isPublishable)
                .anyMatch(alias -> alias.matches(provider, model, promptVersion));
    }

    private static boolean isNone(String provider, String model, String promptVersion) {
        return NONE.equals(provider) && NONE.equals(model) && NONE.equals(promptVersion);
    }

    public record PublicMetadata(String provider, String model, String promptVersion) {}

    /** 仅 Spring 配置绑定需要可变 setter；应用输入永远到不了这些 setter。 */
    public static final class PublicMetadataAlias {

        private String provider;
        private String model;
        private String promptVersion;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = normalize(provider);
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = normalize(model);
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public void setPromptVersion(String promptVersion) {
            this.promptVersion = normalize(promptVersion);
        }

        private boolean matches(String provider, String model, String promptVersion) {
            return this.provider.equals(provider)
                    && this.model.equals(model)
                    && this.promptVersion.equals(promptVersion);
        }

        private boolean isPublishable() {
            return isPublishable(provider, 128)
                    && isPublishable(model, 128)
                    && isPublishable(promptVersion, 64);
        }

        private static boolean isPublishable(String value, int maxLength) {
            return value != null
                    && !value.isBlank()
                    && value.length() <= maxLength
                    && value.codePoints().noneMatch(Character::isISOControl);
        }

        private static String normalize(String value) {
            return value == null ? null : value.strip();
        }
    }
}
