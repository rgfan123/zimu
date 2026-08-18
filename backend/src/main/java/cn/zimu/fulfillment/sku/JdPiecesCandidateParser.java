package cn.zimu.fulfillment.sku;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从商品规格文本解析「每包含件数」候选（jd-real-sdk-switch 03）。
 *
 * <p>识别 {@code 500g*2}、{@code 150g*4}、{@code 500g×2} 这类「数量×乘数」模式，
 * 乘数即候选件数。候选只是供人工确认的建议值，本身不构成已配置的换算。
 */
public final class JdPiecesCandidateParser {

    private static final Pattern MULTIPLIER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[a-zA-Z]*\\s*[xX*×]\\s*(\\d+)");

    private JdPiecesCandidateParser() {}

    /** 依次扫描文本，返回第一个可解析的乘数；全部无法解析时返回 null。 */
    public static Integer candidateOrNull(String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher matcher = MULTIPLIER.matcher(text);
            if (matcher.find()) {
                return Integer.valueOf(matcher.group(2));
            }
        }
        return null;
    }
}
