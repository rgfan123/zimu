package cn.zimu.fulfillment.connector.zhonghui;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 中汇 PMS 登录态（内存级短期缓存）。JWT 只保存在内存，不落库、不打日志；
 * 应用重启后需要重新登录。MOCK 模式不需要真实 token，始终视为已登录。
 */
@Component
public class ZhonghuiPmsSession {

    /** 会话有效期；超过后视为未登录，调用方重新走验证码登录。 */
    static final java.time.Duration TTL = java.time.Duration.ofHours(2);

    private volatile String token;
    private volatile Instant issuedAt;

    public void set(String token) {
        this.token = token;
        this.issuedAt = Instant.now();
    }

    public void clear() {
        this.token = null;
        this.issuedAt = null;
    }

    public String token() {
        return token;
    }

    public boolean authenticated() {
        if (token == null || token.isBlank()) {
            return false;
        }
        return issuedAt != null && Instant.now().isBefore(issuedAt.plus(TTL));
    }

    /** 仅供展示的登录时间，避免把 token 本身暴露给前端。 */
    public Instant issuedAt() {
        return issuedAt;
    }
}
