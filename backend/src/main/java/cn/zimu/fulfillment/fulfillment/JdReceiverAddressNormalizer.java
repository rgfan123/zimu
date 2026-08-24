package cn.zimu.fulfillment.fulfillment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 京东结构化收货地址的确定性归一化（jd-real-sdk-switch 04 的规则词典扩展）。
 *
 * <p>只用中国行政区划词典（省/市/区三级）+ 最长前缀匹配，把来源渠道的自由文本
 * 「收件地址」拆成京东建单要求的省/市/区/详细地址。与「系统不从自由文本猜测」的红线一致：
 * 本类只做确定性词典查表，不引入模型判断。区划名在词典内按层级唯一，逐级最长前缀匹配
 * 即确定性命中，任一层级匹配失败返回 empty 交人工。
 *
 * <p>输出口径与既有已确认地址一致：province 用省级简称（甘肃/福建/北京），city 用市级全称
 * （庆阳市/福州市/北京市），county 用区县全称（西峰区/仓山区/海淀区），detail 为剩余自由文本。
 */
@Component
public class JdReceiverAddressNormalizer {

    private static final String RESOURCE = "data/admin-divisions.json";
    private static final List<String> PROVINCE_SUFFIXES = List.of("自治区", "省");
    private static final List<String> CITY_SUFFIXES = List.of(
            "自治州", "地区", "盟", "市", "州");

    private final List<Province> municipalities;
    private final List<Province> provinces;

    public JdReceiverAddressNormalizer(ObjectMapper objectMapper) {
        List<Province> loaded = load(objectMapper);
        for (Province province : loaded) {
            sortDescending(province.counties());
            if (province.cities() != null) {
                for (City city : province.cities()) {
                    sortDescending(city.counties());
                }
                province.cities().sort(Comparator
                        .comparingInt((City city) -> city.fullName().length())
                        .thenComparingInt(city -> city.city().length())
                        .reversed());
            }
        }
        loaded.sort(Comparator
                .comparingInt((Province province) -> province.fullName().length())
                .thenComparingInt(province -> province.province().length())
                .reversed());
        this.provinces = List.copyOf(loaded);
        this.municipalities = loaded.stream().filter(Province::municipality).toList();
    }

    /** 归一化自由文本地址；省/市/区/详细地址四级齐全才返回，否则 empty（交人工，不猜测）。 */
    public Optional<Normalized> normalize(String raw) {
        String text = raw == null ? "" : raw.replace('\u3000', ' ').strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }

        // 直辖市先做确定性匹配：省=简称、市=全称，直接进区县。
        for (Province province : municipalities) {
            String rest = afterPrefix(text, province.province());
            if (rest == null) {
                continue;
            }
            rest = stripLeading(rest, "市");
            CountyMatch county = matchCounty(rest, province.counties());
            if (county == null) {
                return Optional.empty();
            }
            return complete(province.province(), province.fullName(), county.name(), county.rest());
        }

        // 普通省/自治区：省 → 市 → 区县，逐级最长前缀匹配。
        for (Province province : provinces) {
            if (province.municipality()) {
                continue;
            }
            String rest = afterPrefix(text, province.fullName());
            if (rest == null) {
                rest = afterPrefix(text, province.province());
                if (rest == null) {
                    continue;
                }
                rest = stripLeadingAny(rest, PROVINCE_SUFFIXES);
            }
            CityMatch city = matchCity(rest, province.cities());
            if (city == null) {
                continue;
            }
            CountyMatch county = matchCounty(city.rest(), city.city().counties());
            if (county == null) {
                continue;
            }
            return complete(province.province(), city.city().fullName(), county.name(), county.rest());
        }
        return Optional.empty();
    }

    /** 详细地址为京东必填层级；缺详细地址时视为解析不完整，落人工。 */
    private Optional<Normalized> complete(String province, String city, String county, String detail) {
        String trimmed = detail.strip();
        return trimmed.isEmpty()
                ? Optional.empty()
                : Optional.of(new Normalized(province, city, county, trimmed));
    }

    /** 按全文匹配城市（庆阳市）；退回按简称匹配（庆阳）后再吞掉市/州/地区/盟后缀。 */
    private CityMatch matchCity(String text, List<City> cities) {
        for (City city : cities) {
            String rest = afterPrefix(text, city.fullName());
            if (rest != null) {
                return new CityMatch(city, rest);
            }
        }
        for (City city : cities) {
            String rest = afterPrefix(text, city.city());
            if (rest != null) {
                return new CityMatch(city, stripLeadingAny(rest, CITY_SUFFIXES));
            }
        }
        return null;
    }

    /** 区县最长前缀匹配（区县名按长度降序预排序）。 */
    private CountyMatch matchCounty(String text, List<String> counties) {
        for (String county : counties) {
            if (text.startsWith(county)) {
                return new CountyMatch(county, text.substring(county.length()));
            }
        }
        return null;
    }

    private static String afterPrefix(String text, String prefix) {
        return text.startsWith(prefix) ? text.substring(prefix.length()) : null;
    }

    private static String stripLeading(String text, String suffix) {
        return text.startsWith(suffix) ? text.substring(suffix.length()) : text;
    }

    private static String stripLeadingAny(String text, List<String> suffixes) {
        for (String suffix : suffixes) {
            String stripped = stripLeading(text, suffix);
            if (!stripped.equals(text)) {
                return stripped;
            }
        }
        return text;
    }

    private static void sortDescending(List<String> values) {
        if (values != null) {
            values.sort(Comparator.comparingInt(String::length).reversed());
        }
    }

    private static List<Province> load(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<List<Province>>() {});
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载中国行政区划词典", exception);
        }
    }

    /** 归一化结果：省/市/区 + 剩余自由文本作为详细地址。 */
    public record Normalized(String province, String city, String county, String detailAddress) {}

    private record Province(
            @JsonProperty("province") String province,
            @JsonProperty("fullName") String fullName,
            @JsonProperty("municipality") boolean municipality,
            @JsonProperty("counties") List<String> counties,
            @JsonProperty("cities") List<City> cities) {}

    private record City(
            @JsonProperty("city") String city,
            @JsonProperty("fullName") String fullName,
            @JsonProperty("counties") List<String> counties) {}

    private record CityMatch(City city, String rest) {}

    private record CountyMatch(String name, String rest) {}
}
