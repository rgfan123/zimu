package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JdReceiverAddressNormalizerTest {

    private static JdReceiverAddressNormalizer normalizer;

    @BeforeAll
    static void setUp() {
        normalizer = new JdReceiverAddressNormalizer(new ObjectMapper());
    }

    private static JdReceiverAddressNormalizer.Normalized normalized(String raw) {
        Optional<JdReceiverAddressNormalizer.Normalized> result = normalizer.normalize(raw);
        assertThat(result).as("normalize(%s)", raw).isPresent();
        return result.get();
    }

    @Test
    void municipalityWithoutCitySuffix() {
        JdReceiverAddressNormalizer.Normalized result =
                normalized("北京海淀区清河街道安宁里北区12号楼6单元201");
        assertThat(result.province()).isEqualTo("北京");
        assertThat(result.city()).isEqualTo("北京市");
        assertThat(result.county()).isEqualTo("海淀区");
        assertThat(result.detailAddress()).isEqualTo("清河街道安宁里北区12号楼6单元201");
    }

    @Test
    void municipalityWithCitySuffix() {
        JdReceiverAddressNormalizer.Normalized result =
                normalized("北京市朝阳区十八里店中路6号院云筑一期8-1-1501");
        assertThat(result.province()).isEqualTo("北京");
        assertThat(result.city()).isEqualTo("北京市");
        assertThat(result.county()).isEqualTo("朝阳区");
        assertThat(result.detailAddress()).isEqualTo("十八里店中路6号院云筑一期8-1-1501");
    }

    @Test
    void municipalityKeepsRedundantSuffixInDetail() {
        JdReceiverAddressNormalizer.Normalized result =
                normalized("北京丰台区北宫镇丰台北宫镇 红山郡小区13号楼2单元904");
        assertThat(result.province()).isEqualTo("北京");
        assertThat(result.city()).isEqualTo("北京市");
        assertThat(result.county()).isEqualTo("丰台区");
        assertThat(result.detailAddress()).isEqualTo("北宫镇丰台北宫镇 红山郡小区13号楼2单元904");
    }

    @Test
    void provinceByBaseNameThenCityAndCounty() {
        JdReceiverAddressNormalizer.Normalized result =
                normalized("甘肃庆阳市西峰区南街街道润泽园小区西门口进来12号楼楼下");
        assertThat(result.province()).isEqualTo("甘肃");
        assertThat(result.city()).isEqualTo("庆阳市");
        assertThat(result.county()).isEqualTo("西峰区");
        assertThat(result.detailAddress()).isEqualTo("南街街道润泽园小区西门口进来12号楼楼下");
    }

    @Test
    void provinceByFullNameAndCountyLevelCity() {
        JdReceiverAddressNormalizer.Normalized result =
                normalized("福建省南平市建瓯市建贸路27号601室");
        assertThat(result.province()).isEqualTo("福建");
        assertThat(result.city()).isEqualTo("南平市");
        assertThat(result.county()).isEqualTo("建瓯市");
        assertThat(result.detailAddress()).isEqualTo("建贸路27号601室");
    }

    @Test
    void autonomousRegionByFullName() {
        JdReceiverAddressNormalizer.Normalized result =
                normalized("内蒙古自治区呼和浩特市新城区新华大街1号");
        assertThat(result.province()).isEqualTo("内蒙古");
        assertThat(result.city()).isEqualTo("呼和浩特市");
        assertThat(result.county()).isEqualTo("新城区");
        assertThat(result.detailAddress()).isEqualTo("新华大街1号");
    }

    @Test
    void addressWithoutDetailFallsBackToEmpty() {
        // 详细地址是京东必填层级：省/市/区命中但缺详细地址时不得当作完整候选。
        assertThat(normalizer.normalize("北京市朝阳区")).isEmpty();
        assertThat(normalizer.normalize("甘肃省庆阳市西峰区")).isEmpty();
    }

    @Test
    void unknownOrMalformedAddressFallsBackToEmpty() {
        assertThat(normalizer.normalize("随便写个地址没有区划")).isEmpty();
        assertThat(normalizer.normalize("")).isEmpty();
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("   ")).isEmpty();
    }
}
