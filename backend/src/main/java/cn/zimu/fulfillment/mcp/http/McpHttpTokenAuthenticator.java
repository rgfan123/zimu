package cn.zimu.fulfillment.mcp.http;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP HTTP/SSE 传输面的 Bearer token 校验：外部 MCP 客户端在 {@code Authorization} 头携带
 * {@code Bearer <token>}，与 {@code app.mcp.http.token}（env {@code MCP_HTTP_TOKEN}）比对。
 *
 * <p>常数时间比较（{@link MessageDigest#isEqual}），避免响应时延侧信道探测 token。
 * token 明文只存在于本类构造期注入的内存字段，不参与任何日志/异常消息拼接——
 * {@link #requireAuthorized} 失败一律抛不带 token 信息的 401，与
 * {@code AuditLogService} 的 {@code SecretRedactor} / 京东 pin 脱敏先例同一原则：
 * 凭据永不进日志与审计负载。
 *
 * <p>此 bean 本身不带 {@link cn.zimu.fulfillment.mcp.http.McpHttpTransportCondition}——
 * 只有被条件注册的 HTTP 传输面控制器会调用它；token 未配置时（{@code expectedToken} 为空）
 * {@link #isAuthorized} 恒为 {@code false}，属于防御性兜底，不依赖调用方先检查。
 */
@Component
public class McpHttpTokenAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;

    public McpHttpTokenAuthenticator(@Value("${app.mcp.http.token:}") String token) {
        this.expectedToken = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 校验通过则直接返回；不通过抛 {@link BusinessException}（401，不含 token 或任何配置细节）。
     * 用 {@code BusinessException} 而非 {@code ResponseStatusException}：本应用的
     * {@code GlobalExceptionHandler} 只对 {@code BusinessException} 按其携带的
     * {@code httpStatus} 精确转译，未知异常类型一律兜底 500——用框架级异常会被那道
     * catch-all 吞成 500，401 语义丢失。
     */
    public void requireAuthorized(String authorizationHeader) {
        if (!isAuthorized(authorizationHeader)) {
            throw new BusinessException(401, "MCP_HTTP_UNAUTHORIZED", "MCP HTTP 传输鉴权失败");
        }
    }

    private boolean isAuthorized(String authorizationHeader) {
        if (expectedToken.length == 0
                || authorizationHeader == null
                || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presented =
                authorizationHeader.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expectedToken);
    }
}
