package cn.zimu.fulfillment.catalog;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 读取并验证冻结 manifest；任何内容漂移或结构异常都在业务写事务前失败关闭。 */
@Component
final class AuthoritativeSkuCatalogManifestLoader {

    static final String RESOURCE = "data/authoritative-jd-sku-catalog.json";
    static final String JD_SOURCE_SHA256 =
            "85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873";
    static final String PRICE_SOURCE_SHA256 =
            "7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160";
    static final String MANIFEST_SHA256 =
            "f9d47bf4ee5b1766e7539762bb79593f44820de9a8e56c4679d3ae4551cc1a4b";

    private static final Set<String> DIFFERENCE_CODES = Set.of(
            "DUPLICATE_JD_CODE",
            "CAISHIXIAN_MAPPING_MISSING",
            "JUFUBAO_MAPPING_MISSING",
            "CHANNEL_QUANTITY_DIFFERS");

    private final ObjectMapper objectMapper;

    AuthoritativeSkuCatalogManifestLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    LoadedManifest load() {
        byte[] bytes = resourceBytes();
        String contentSha256 = sha256(bytes);
        if (!MANIFEST_SHA256.equals(contentSha256)) {
            throw BusinessException.conflict(
                    "AUTHORITATIVE_CATALOG_SOURCE_DRIFT",
                    "权威京东商品 manifest 内容已漂移，必须从固定源工作簿重新生成并复核");
        }
        AuthoritativeSkuCatalogManifest manifest;
        try {
            manifest = objectMapper.readValue(bytes, AuthoritativeSkuCatalogManifest.class);
        } catch (IOException exception) {
            throw invalid("权威京东商品 manifest 无法解析");
        }
        validate(manifest);
        return new LoadedManifest(manifest, contentSha256);
    }

    private byte[] resourceBytes() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw invalid("缺少权威京东商品 manifest");
        }
    }

    void validate(AuthoritativeSkuCatalogManifest manifest) {
        if (manifest == null
                || manifest.schemaVersion() != 1
                || manifest.jdSource() == null
                || manifest.priceSource() == null
                || manifest.expected() == null
                || manifest.items() == null
                || manifest.excludedSheets() == null) {
            throw invalid("权威京东商品 manifest 顶层结构无效");
        }
        requireSource(manifest.jdSource(), "京东商品编号.xlsx", "Sheet1", JD_SOURCE_SHA256, 63);
        requireSource(
                manifest.priceSource(),
                "合作商品价格查询导出_按商品名称去重.xlsx",
                "0",
                PRICE_SOURCE_SHA256,
                41);
        if (manifest.items().size() != 61
                || manifest.expected().uniqueJdCodes() != 61
                || manifest.expected().duplicateCodeCount() != 2
                || manifest.expected().priceMatchedCount() != 0
                || manifest.expected().unpricedCount() != 61) {
            throw invalid("权威京东商品 manifest 汇总数量无效");
        }

        Set<String> codes = new HashSet<>();
        Set<Integer> sourceRows = new HashSet<>();
        int duplicateCodes = 0;
        int priced = 0;
        for (AuthoritativeSkuCatalogManifest.Item item : manifest.items()) {
            requireItem(item, codes, sourceRows);
            if (item.sourceRows().size() > 1) duplicateCodes++;
            if (item.purchasePrice() != null) priced++;
        }
        Set<Integer> expectedRows = IntStream.rangeClosed(2, 64).boxed().collect(Collectors.toSet());
        if (!sourceRows.equals(expectedRows)
                || duplicateCodes != 2
                || priced != 0
                || manifest.items().size() - priced != 61) {
            throw invalid("权威京东商品 manifest 明细与汇总不一致");
        }

        List<AuthoritativeSkuCatalogManifest.ExcludedSheet> excluded = manifest.excludedSheets();
        if (excluded.size() != 3
                || !excluded.equals(List.of(
                        new AuthoritativeSkuCatalogManifest.ExcludedSheet(
                                "Sheet2", 179, "BUNDLE_MAPPING_OUT_OF_SCOPE"),
                        new AuthoritativeSkuCatalogManifest.ExcludedSheet(
                                "Sheet3", 20, "CAISHIXIAN_REFERENCE_OUT_OF_SCOPE"),
                        new AuthoritativeSkuCatalogManifest.ExcludedSheet(
                                "Sheet4", 37, "JUFUBAO_REFERENCE_OUT_OF_SCOPE")))) {
            throw invalid("权威京东商品 manifest 的排除工作表报告无效");
        }
    }

    private void requireItem(
            AuthoritativeSkuCatalogManifest.Item item,
            Set<String> codes,
            Set<Integer> allSourceRows) {
        if (item == null
                || item.jdCode() == null
                || !item.jdCode().matches("EMG\\d+")
                || !codes.add(item.jdCode())
                || blank(item.canonicalName())
                || item.canonicalName().length() > 200
                || item.aliases() == null
                || item.sourceRows() == null
                || item.sourceRows().isEmpty()
                || item.mappingDifferenceCodes() == null) {
            throw invalid("权威京东商品 manifest 存在非法商品行");
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>(item.aliases());
        if (aliases.size() != item.aliases().size()
                || aliases.stream().anyMatch(alias -> blank(alias) || alias.equals(item.canonicalName()))) {
            throw invalid("权威京东商品 manifest 存在非法别名");
        }
        if (!DIFFERENCE_CODES.containsAll(item.mappingDifferenceCodes())
                || new HashSet<>(item.mappingDifferenceCodes()).size() != item.mappingDifferenceCodes().size()) {
            throw invalid("权威京东商品 manifest 存在非法差异码");
        }
        if ((item.sourceRows().size() > 1)
                != item.mappingDifferenceCodes().contains("DUPLICATE_JD_CODE")) {
            throw invalid("权威京东商品 manifest 重复编码报告不一致");
        }
        for (AuthoritativeSkuCatalogManifest.SourceRow row : item.sourceRows()) {
            if (row == null
                    || row.row() < 2
                    || row.row() > 64
                    || blank(row.jdName())
                    || !validOptionalChannelPair(row.caishixianName(), row.caishixianQuantity())
                    || !validOptionalChannelPair(row.jufubaoName(), row.jufubaoQuantity())
                    || !allSourceRows.add(row.row())) {
                throw invalid("权威京东商品 manifest 存在非法或重复源行");
            }
        }

        if (item.purchasePrice() != null
                || item.retailPrice() != null
                || item.priceMatchName() != null
                || item.priceSourceRow() != null) {
            throw invalid("权威京东商品 manifest 不得携带价格");
        }
    }

    private static void requireSource(
            AuthoritativeSkuCatalogManifest.Source actual,
            String fileName,
            String sheetName,
            String sha256,
            int dataRows) {
        if (!fileName.equals(actual.fileName())
                || !sheetName.equals(actual.sheetName())
                || !sha256.equals(actual.sha256())
                || actual.dataRows() != dataRows) {
            throw BusinessException.conflict(
                    "AUTHORITATIVE_CATALOG_SOURCE_DRIFT", "权威商品源文件指纹或工作表范围已漂移");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean validOptionalChannelPair(String name, String quantity) {
        return (name == null && quantity == null)
                || (!blank(name) && quantity != null && quantity.matches("[1-9][0-9]*"));
    }

    private static BusinessException invalid(String message) {
        return BusinessException.unprocessable("AUTHORITATIVE_CATALOG_INVALID", message);
    }

    record LoadedManifest(AuthoritativeSkuCatalogManifest manifest, String contentSha256) {}
}
