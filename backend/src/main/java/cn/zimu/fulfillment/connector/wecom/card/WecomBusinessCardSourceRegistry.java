package cn.zimu.fulfillment.connector.wecom.card;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 卡片来源注册表：Spring 收集所有 {@link WecomBusinessCardSource} 按域索引。
 *
 * <p>同域重复注册直接在启动期抛异常——两个 source 抢同一个域，运行期表现是「卡片内容
 * 时对时错」，那种问题极难查。宁可起不来。
 */
@Service
public class WecomBusinessCardSourceRegistry {

    private final Map<String, WecomBusinessCardSource> byDomain;

    public WecomBusinessCardSourceRegistry(List<WecomBusinessCardSource> sources) {
        this.byDomain = sources.stream()
                .collect(Collectors.toUnmodifiableMap(
                        WecomBusinessCardSource::domain,
                        Function.identity(),
                        (first, second) -> {
                            throw new IllegalStateException(
                                    "卡片来源域重复注册: " + first.domain());
                        }));
    }

    public Optional<WecomBusinessCardSource> find(String domain) {
        return Optional.ofNullable(byDomain.get(domain));
    }

    /** 已注册的域（供健康检查与排障）。 */
    public List<String> domains() {
        return byDomain.keySet().stream().sorted().toList();
    }
}
