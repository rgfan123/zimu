package cn.zimu.fulfillment.connector.jd;

import static org.assertj.core.api.Assertions.assertThat;

import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService.queryReturnToSupplier.RtsOpenQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService.queryRtwOrderList.RtwOpenQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.queryInStockSidBySku.GoodsSerialQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.queryJDMallSerialByPage.JDMallSerialQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.queryPageSerialByOwnerNoAndCondition.BusSerialQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.querySerialBySkuAndSerial.GoodsSIDQueryRequest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 收编 7 个 ISC client 前的等价性证据（票 03）：三份默认值注入策略
 * （反射探测 / 无条件注入 / 手写白名单）能否合并成一份反射策略，取决于 SDK DTO
 * 到底有没有对应 setter —— 由本测试实测回答，不靠推断。
 *
 * <p>若将来某个 DTO 的 setter 集合变化导致本测试失败，说明统一策略不再等价，
 * 必须回到按客户端显式配置，而不是修改断言。
 */
class JdIscDefaultsPolicyEquivalenceTest {

    private static boolean supports(Class<?> dtoType, String property) {
        String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return Arrays.stream(dtoType.getMethods()).anyMatch(method -> method.getName().equals(setter));
    }

    /** JdSerialClient 的 OWNER_NO_DEFAULTS = {pin, ownerNo}：这两个 DTO 应两者都支持。 */
    @Test
    void serialOwnerNoDtosSupportBothDefaults() {
        for (Class<?> dto : new Class<?>[] {JDMallSerialQueryRequest.class, BusSerialQueryRequest.class}) {
            assertThat(supports(dto, "pin")).as("%s.setPin", dto.getSimpleName()).isTrue();
            assertThat(supports(dto, "ownerNo")).as("%s.setOwnerNo", dto.getSimpleName()).isTrue();
        }
    }

    /** JdSerialClient 的 PIN_ONLY_DEFAULTS = {pin}：这两个 DTO 必须没有 ownerNo，手写白名单才等价于反射。 */
    @Test
    void serialPinOnlyDtosDoNotSupportOwnerNo() {
        for (Class<?> dto : new Class<?>[] {GoodsSIDQueryRequest.class, GoodsSerialQueryRequest.class}) {
            assertThat(supports(dto, "pin")).as("%s.setPin", dto.getSimpleName()).isTrue();
            assertThat(supports(dto, "ownerNo")).as("%s.setOwnerNo", dto.getSimpleName()).isFalse();
        }
    }

    /** JdReturnClient 无条件注入 pin/ownerNo：其 DTO 必须两者都支持，否则反射策略会改变行为。 */
    @Test
    void returnDtosSupportBothDefaults() {
        for (Class<?> dto : new Class<?>[] {
                RtwOpenQueryRequest.class,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .queryRtwOrderDetail.RtwOpenQueryRequest.class,
                RtsOpenQueryRequest.class}) {
            assertThat(supports(dto, "pin")).as("%s.setPin", dto.getName()).isTrue();
            assertThat(supports(dto, "ownerNo")).as("%s.setOwnerNo", dto.getName()).isTrue();
        }
    }

    /**
     * 陷阱固化：退货列表与退货详情各有一个**同名不同包**的 {@code RtwOpenQueryRequest}。
     * 收编时若图省事 import 其中一个、让两个调用点共用，编译器虽会报错，但换成
     * 结构相同的 DTO 就会静默走错请求体——这里断言二者确实是不同的类。
     */
    @Test
    void twoDistinctRtwRequestTypesShareTheSameSimpleName() {
        Class<?> list = RtwOpenQueryRequest.class;
        Class<?> detail = com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                .queryRtwOrderDetail.RtwOpenQueryRequest.class;
        assertThat(list.getSimpleName()).isEqualTo(detail.getSimpleName());
        assertThat(list).isNotEqualTo(detail);
    }
}
