package cn.zimu.fulfillment.message;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Server-owned allowlist for model metadata that may be persisted or exposed publicly. */
@Component
@ConfigurationProperties(prefix = "app.message-interpreter")
public class MessageModelMetadataRegistry {

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

    /** Adapter output can never add aliases; it must match a server-owned triple exactly. */
    public boolean allows(InterpretationResult result) {
        if (result == null) {
            return false;
        }
        if (isNone(result.provider(), result.model(), result.promptVersion())) {
            return result.error() != null && !result.error().isBlank();
        }
        return explicitlyAllows(result.provider(), result.model(), result.promptVersion());
    }

    /** Historical unknown or deconfigured metadata always collapses to the public sentinel. */
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

    /** Mutable only for Spring configuration binding; application inputs never reach these setters. */
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
