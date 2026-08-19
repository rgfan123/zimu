package cn.zimu.fulfillment.agent;

import java.util.Locale;
import java.util.Set;

/**
 * 供应商 response_format 能力判定（meta-agent-platform 01 调研证据）：
 * OpenAI 原生支持 {@code json_schema}（2024-08 起）；DeepSeek 官方仅 {@code json_object}
 * （api-docs.deepseek.com json_mode；实测 json_schema 返回 400）。一期以静态名单表达
 * 能力（能力探测的确定性代理），后续可演进为按端点探测。
 */
final class JsonSchemaCapability {

    /** 官方仅支持 json_object 的供应商（其余按支持 json_schema 处理）。 */
    private static final Set<String> JSON_OBJECT_ONLY_PROVIDERS = Set.of("deepseek");

    private JsonSchemaCapability() {}

    static boolean supports(String provider) {
        return provider != null
                && !provider.isBlank()
                && !JSON_OBJECT_ONLY_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT));
    }
}
