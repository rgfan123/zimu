package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 来源礼包映射更新输入（至少修改一个业务字段，expectedVersion 走乐观锁）。
 *
 * <p>刻意<b>不放开</b> quantityMultiplier：一期一个来源单位恒等于一份礼包，创建入口
 * {@link SourceBundleMappingWrite} 用 int32 的 {@code @Min(1)}/{@code @Max(1)} 把它钉死，
 * 服务端也一律按整数 1 落库。
 * 若在这里把它列成可改字段，唯一合法值就是它当前已有的值——改了等于没改，还会让
 * 「只传 quantity_multiplier=1」的请求通过「至少改一个字段」的检查却什么都不动。
 * 与其留一个假的开关，不如现在不给；等一期之后真要放开包装乘数，创建与更新两处
 * 校验一起改，语义才是一致的。
 */
public record SourceBundleMappingPatch(
        @NotNull(message = "期望版本不能为空") @Min(0) Long expectedVersion,
        @Size(max = 255, message = "来源礼包名称超长") String sourceBundleName,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "礼包标识符无效") String bundleId,
        Boolean active) {}
