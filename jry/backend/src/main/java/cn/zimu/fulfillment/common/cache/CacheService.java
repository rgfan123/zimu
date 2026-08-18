package cn.zimu.fulfillment.common.cache;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 非权威缓存骨架：任何 Redis 故障都降级为数据库直读，绝不改变业务结果。
 * 写事务路径不经过本服务；本服务只用于读侧解析加速与映射补丁后的主动失效。
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final boolean enabled;
    private final StringRedisTemplate redis;

    public CacheService(
            @Value("${app.cache.enabled:true}") boolean enabled, StringRedisTemplate redis) {
        this.enabled = enabled;
        this.redis = redis;
    }

    public Optional<String> get(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException ex) {
            log.debug("cache read failed, falling back to database: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException ex) {
            log.debug("cache write failed: {}", ex.getMessage());
        }
    }

    public void evict(String key) {
        if (!enabled) {
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.debug("cache evict failed: {}", ex.getMessage());
        }
    }
}
