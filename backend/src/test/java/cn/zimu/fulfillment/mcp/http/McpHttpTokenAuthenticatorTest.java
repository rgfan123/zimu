package cn.zimu.fulfillment.mcp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import org.junit.jupiter.api.Test;

/**
 * {@link McpHttpTokenAuthenticator} 纯单元测试：不依赖 Spring 容器，直接构造校验各分支。
 * 断言异常消息不含 token 明文——校验失败的 401 不得泄露期望值，便于攻击者比对试探。
 *
 * <p>断言的是 {@link BusinessException} 而非 Spring 的 {@code ResponseStatusException}：
 * 这个应用的 {@code GlobalExceptionHandler} 只对 {@code BusinessException} 精确转译
 * httpStatus，其余异常类型一律兜底成 500（见该类的 catch-all {@code Exception.class}
 * 处理器）——鉴权失败必须走前者才能真正回 401。
 */
class McpHttpTokenAuthenticatorTest {

    private static final String TOKEN = "s3cr3t-mcp-http-token";

    private final McpHttpTokenAuthenticator authenticator = new McpHttpTokenAuthenticator(TOKEN);

    @Test
    void correctBearerTokenPasses() {
        assertThatCode(() -> authenticator.requireAuthorized("Bearer " + TOKEN)).doesNotThrowAnyException();
    }

    @Test
    void missingHeaderIsRejected() {
        assertUnauthorized(null);
    }

    @Test
    void wrongTokenIsRejected() {
        assertUnauthorized("Bearer wrong-token");
    }

    @Test
    void missingBearerPrefixIsRejected() {
        assertUnauthorized(TOKEN);
    }

    @Test
    void basicSchemeIsRejectedEvenIfCredentialsHappenToMatch() {
        assertUnauthorized("Basic " + TOKEN);
    }

    @Test
    void emptyConfiguredTokenAlwaysRejects() {
        McpHttpTokenAuthenticator unconfigured = new McpHttpTokenAuthenticator("");
        assertThatThrownBy(() -> unconfigured.requireAuthorized("Bearer anything"))
                .isInstanceOf(BusinessException.class);
    }

    private void assertUnauthorized(String authorizationHeader) {
        assertThatThrownBy(() -> authenticator.requireAuthorized(authorizationHeader))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException businessException = (BusinessException) ex;
                    assertThat(businessException.getHttpStatus()).isEqualTo(401);
                    assertThat(businessException.getMessage()).doesNotContain(TOKEN);
                });
    }
}
