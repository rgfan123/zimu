package cn.zimu.fulfillment.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent 提示词安全扫描（05 决策门禁清单）：凭据扫描（密钥/Token 形态——提示词会进 DB，
 * 红线）与越权指令扫描（要求写操作/绕过审计的指令）为阻断项；PII 扫描为仅警告（示例
 * 数据含手机号等可能是合理内容，确认流程高亮，不阻断）。
 *
 * <p>模式匹配是保守启发式：只对明确形态/语义触发，避免把「提示词中说明禁止泄露/拒绝
 * 越权」的说明性文字误判为违规（如「请勿执行任何写操作」不命中）。仍属静态判定，
 * 供 T10 写工具静态门禁与 T11 确认前全量复跑使用，不替代人工判断。
 */
public final class AgentGateScan {

    // 凭据形态（值模式）：命中即阻断
    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{16,}");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern SECRET_ASSIGN = Pattern.compile(
            "(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|token)\\s*[=:：]\\s*['\"]?[A-Za-z0-9_@#$%^&*./-]{6,}");
    private static final Pattern CHINESE_SECRET_ASSIGN =
            Pattern.compile("(密码|密钥|口令|秘钥)\\s*[=:：]\\s*\\S{4,}");

    // 越权指令语义（要求写操作/绕过审计）：命中即阻断
    private static final Pattern BYPASS_AUDIT = Pattern.compile("绕过.{0,6}(审计|确认|人工|复核)");
    private static final Pattern SKIP_AUDIT =
            Pattern.compile("(跳过|无需|不要|不用|不进行|不记录|免去|省略).{0,4}(审计|人工|确认|复核)");
    private static final Pattern DIRECT_WRITE = Pattern.compile("直接(执行写|写入|改)");
    private static final Pattern ENGLISH_BYPASS =
            Pattern.compile("(?i)(bypass|skip)\\s+(audit|approval|review)");

    // PII 形态（示例数据可能合理）：仅警告，不阻断
    private static final Pattern PHONE = Pattern.compile("1[3-9][0-9]{9}");
    private static final Pattern ID_CARD = Pattern.compile("\\b[0-9]{17}[0-9Xx]\\b");
    private static final Pattern ADDRESS_LIKE =
            Pattern.compile("(收货地址|联系地址|住址)\\s*[=:：]?\\s*\\S{4,}");

    private AgentGateScan() {}

    /** 凭据命中返回问题（逐条）；未命中返回空列表。null/空白输入视为无命中。 */
    public static List<String> credentialProblems(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        if (OPENAI_KEY.matcher(text).find()) {
            hits.add("提示词含 OpenAI 风格密钥（sk-…），凭据绝不允许进提示词/DB");
        }
        if (AWS_ACCESS_KEY.matcher(text).find()) {
            hits.add("提示词含 AWS Access Key（AKIA…），凭据绝不允许进提示词/DB");
        }
        if (BEARER_TOKEN.matcher(text).find()) {
            hits.add("提示词含 Bearer Token，凭据绝不允许进提示词/DB");
        }
        if (PRIVATE_KEY_BLOCK.matcher(text).find()) {
            hits.add("提示词含私钥块（-----BEGIN … PRIVATE KEY-----），凭据绝不允许进提示词/DB");
        }
        if (SECRET_ASSIGN.matcher(text).find()) {
            hits.add("提示词含密钥/口令赋值（password/secret/api_key/token=…），凭据绝不允许进提示词/DB");
        }
        if (CHINESE_SECRET_ASSIGN.matcher(text).find()) {
            hits.add("提示词含明文密码/密钥赋值，凭据绝不允许进提示词/DB");
        }
        return hits;
    }

    /** 越权指令命中返回问题（逐条）；未命中返回空列表。null/空白输入视为无命中。 */
    public static List<String> escalationProblems(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        if (BYPASS_AUDIT.matcher(text).find()) {
            hits.add("提示词含绕过审计/确认/人工的指令，越权指令必须阻断");
        }
        if (SKIP_AUDIT.matcher(text).find()) {
            hits.add("提示词含跳过审计/确认/人工的指令，越权指令必须阻断");
        }
        if (DIRECT_WRITE.matcher(text).find()) {
            hits.add("提示词含直接执行写操作/改数据的指令，越权指令必须阻断");
        }
        if (ENGLISH_BYPASS.matcher(text).find()) {
            hits.add("提示词含 bypass/skip-audit 越权指令，必须阻断");
        }
        return hits;
    }

    /** PII 命中返回警告（逐条，不阻断）；未命中返回空列表。null/空白输入视为无命中。 */
    public static List<String> piiWarnings(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        if (PHONE.matcher(text).find()) {
            hits.add("提示词含手机号（示例数据？），确认时请人工核对是否应为脱敏占位");
        }
        if (ID_CARD.matcher(text).find()) {
            hits.add("提示词含身份证号（示例数据？），确认时请人工核对是否应为脱敏占位");
        }
        if (ADDRESS_LIKE.matcher(text).find()) {
            hits.add("提示词含收货/联系地址（示例数据？），确认时请人工核对是否应为脱敏占位");
        }
        return hits;
    }
}
