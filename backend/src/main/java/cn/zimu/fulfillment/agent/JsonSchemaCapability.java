package cn.zimu.fulfillment.agent;

import java.util.Locale;
import java.util.Set;

/**
 * 供应商 response_format 能力判定（meta-agent-platform 01 调研证据）：
 * OpenAI 原生支持 {@code json_schema}（2024-08 起）；DeepSeek 官方仅 {@code json_object}
 * （api-docs.deepseek.com json_mode；实测 json_schema 返回 400）。
 *
 * <p><b>保守默认</b>：仅明确已知支持的供应商（openai）走 json_schema，其余一律降级
 * json_object + 客户端 JSON Schema 校验兜底（未知/兼容端点的 json_schema 支持不可靠，
 * 降级永远安全——客户端校验保证输出满足 output_schema）。一期以静态名单表达能力
 * （能力探测的确定性代理），后续可演进为按端点探测。
 */
final class JsonSchemaCapability {

    /** 明确支持 json_schema 的供应商（其余按仅 json_object 处理）。 */
    private static final Set<String> JSON_SCHEMA_SUPPORTED_PROVIDERS = Set.of("openai");

    private JsonSchemaCapability() {}

    static boolean supports(String provider) {
        return provider != null
                && JSON_SCHEMA_SUPPORTED_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT));
    }
}
