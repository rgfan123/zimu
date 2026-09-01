package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

/**
 * 京东商品编号资料的只读预览：只产生候选映射证据，不写主数据，也不把资料文件当订单模板。
 */
@Service
class ProviderSkuMappingReferenceService {

    private final SourceFileParser sourceFileParser;
    private final DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);

    ProviderSkuMappingReferenceService(SourceFileParser sourceFileParser) {
        this.sourceFileParser = sourceFileParser;
    }

    Map<String, Object> preview(byte[] referenceBytes, byte[] sourceBytes) {
        if (!isOoxml(referenceBytes)) {
            throw BusinessException.unprocessable(
                    "MAPPING_REFERENCE_CONTAINER_INVALID", "SKU 映射资料必须是真实 XLSX");
        }
        ParsedSourceFile source = sourceFileParser.parse(sourceBytes);
        ReferenceData reference = parseReference(referenceBytes);

        Map<String, Set<String>> sourceNamesByRef = new HashMap<>();
        for (ParsedSourceRow row : source.rows()) {
            sourceNamesByRef.computeIfAbsent(row.sourceSkuRef(), ignored -> new LinkedHashSet<>())
                    .add(normalize(row.productName()));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int matched = 0;
        int needReview = 0;
        int conflict = 0;
        for (ParsedSourceRow row : source.rows()) {
            Map<String, Object> result = baseRow(row);
            Set<String> sourceNames = sourceNamesByRef.getOrDefault(row.sourceSkuRef(), Set.of());
            if (sourceNames.size() > 1) {
                result.put("match_status", "CONFLICT");
                result.put("reason_code", "SOURCE_SKU_NAME_CONFLICT");
                result.put("reason", "同一来源 SKU 编号对应多个商品名称");
                conflict++;
            } else {
                Map<String, Candidate> candidates = new LinkedHashMap<>();
                reference.candidates(source.sourceChannel(), row.productName())
                        .forEach(candidate -> candidates.putIfAbsent(candidate.conflictKey(), candidate));
                if (candidates.isEmpty()) {
                    result.put("match_status", "NEED_REVIEW");
                    result.put("reason_code", "NO_EXACT_NAME_MATCH");
                    result.put("reason", "映射资料中无精确同名商品，禁止模糊猜测");
                    needReview++;
                } else if (candidates.size() > 1) {
                    result.put("match_status", "CONFLICT");
                    result.put("reason_code", "REFERENCE_MAPPING_CONFLICT");
                    result.put("reason", "同一来源商品名在资料中对应多个京东编码或数量乘数");
                    result.put("candidates", candidates.values().stream().map(this::candidateMap).toList());
                    conflict++;
                } else {
                    Candidate candidate = candidates.values().iterator().next();
                    result.putAll(candidateMap(candidate));
                    result.put("match_status", "MATCHED");
                    result.put("reason_code", "EXACT_NAME_MATCH");
                    result.put("reason", "来源商品名与指定渠道映射资料精确匹配");
                    matched++;
                }
            }
            rows.add(result);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reference_sha256", sha256(referenceBytes));
        response.put("source_sha256", sha256(sourceBytes));
        response.put("source_channel", source.sourceChannel().name());
        response.put("summary", Map.of(
                "total", rows.size(), "matched", matched, "need_review", needReview, "conflict", conflict));
        response.put("reference_quality", reference.quality());
        response.put("rows", rows);
        return response;
    }

    private ReferenceData parseReference(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() < 4) {
                throw BusinessException.unprocessable(
                        "MAPPING_REFERENCE_LAYOUT_INVALID", "SKU 映射资料缺少预期的渠道或京东商品 Sheet");
            }
            Map<SourceChannel, Map<String, List<Candidate>>> aliases = new LinkedHashMap<>();
            for (SourceChannel channel : List.of(SourceChannel.CAISHIXIAN, SourceChannel.JUFUBAO, SourceChannel.FEIXIANG)) {
                aliases.put(channel, new LinkedHashMap<>());
            }
            Map<String, String> providerNames = providerCatalog(workbook);
            int blankProviderCodes = 0;

            var summary = workbook.getSheetAt(0);
            for (int index = 1; index <= summary.getLastRowNum(); index++) {
                Row row = summary.getRow(index);
                blankProviderCodes += addDirect(
                        aliases.get(SourceChannel.CAISHIXIAN), row, 0, 1, 4, 5, providerNames);
                blankProviderCodes += addDirect(
                        aliases.get(SourceChannel.JUFUBAO), row, 2, 3, 4, 5, providerNames);
            }
            // Sheet3 的“易和天下”是供应商/目录标识，没有证据等同于 FEIXIANG 来源渠道。
            // 其编码可用于京东商品目录补足，但禁止产生飞象 source_sku 候选。
            var jufubao = workbook.getSheetAt(3);
            for (int index = 0; index <= jufubao.getLastRowNum(); index++) {
                blankProviderCodes += addDirect(
                        aliases.get(SourceChannel.JUFUBAO), jufubao.getRow(index), 1, 2, 1, 0, providerNames);
            }

            Map<String, List<BundleComponent>> bundles = bundleCatalog(workbook, providerNames);
            int conflictingSourceNames = aliases.values().stream()
                    .mapToInt(byName -> (int) byName.values().stream()
                            .filter(values -> new LinkedHashSet<>(values).size() > 1).count())
                    .sum();
            int duplicateProviderCodes = duplicateProviderCodes(workbook);
            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("provider_sku_count", providerNames.size());
            quality.put("blank_provider_codes", blankProviderCodes);
            quality.put("duplicate_provider_codes", duplicateProviderCodes);
            quality.put("conflicting_source_names", conflictingSourceNames);
            quality.put("bundle_count", bundles.size());
            quality.put("ambiguous_bundle_rows", ambiguousBundleRows(workbook));
            return new ReferenceData(aliases, bundles, quality);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unprocessable("MAPPING_REFERENCE_READ_FAILED", "无法读取 SKU 映射资料");
        }
    }

    private Map<String, String> providerCatalog(Workbook workbook) {
        Map<String, Set<String>> names = new LinkedHashMap<>();
        // Sheet1 E/F 是京东编码与京东商品名的权威展示字段。
        var summary = workbook.getSheetAt(0);
        for (int index = 1; index <= summary.getLastRowNum(); index++) {
            Row row = summary.getRow(index);
            String code = value(row, 5);
            String name = value(row, 4);
            if (!code.isBlank() && !name.isBlank()) {
                names.computeIfAbsent(code, ignored -> new LinkedHashSet<>()).add(name);
            }
        }
        // Sheet3 仅用于补足 Sheet1 中没有的编码，不覆盖京东展示名。
        var feixiang = workbook.getSheetAt(2);
        for (int index = 0; index <= feixiang.getLastRowNum(); index++) {
            Row row = feixiang.getRow(index);
            String code = value(row, 0);
            String name = value(row, 1);
            if (!code.isBlank() && !name.isBlank() && !names.containsKey(code)) {
                names.computeIfAbsent(code, ignored -> new LinkedHashSet<>()).add(name);
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        names.forEach((code, values) -> result.put(code, values.stream().sorted().findFirst().orElse("")));
        return result;
    }

    private int addDirect(
            Map<String, List<Candidate>> aliases,
            Row row,
            int sourceNameColumn,
            int multiplierColumn,
            int fallbackProviderNameColumn,
            int providerCodeColumn,
            Map<String, String> providerNames) {
        String sourceName = value(row, sourceNameColumn);
        if (sourceName.isBlank()) {
            return 0;
        }
        String code = value(row, providerCodeColumn);
        if (code.isBlank()) {
            return 1;
        }
        Integer multiplier = positiveCount(countValue(row, multiplierColumn));
        if (multiplier == null) {
            return 0;
        }
        String providerName = providerNames.getOrDefault(code, value(row, fallbackProviderNameColumn));
        Candidate candidate = new Candidate(code, providerName, multiplier, List.of());
        aliases.computeIfAbsent(normalize(sourceName), ignored -> new ArrayList<>()).add(candidate);
        return 0;
    }

    private Map<String, List<BundleComponent>> bundleCatalog(Workbook workbook, Map<String, String> providerNames) {
        Map<String, List<BundleComponent>> result = new LinkedHashMap<>();
        var sheet = workbook.getSheetAt(1);
        // A:C 为主 BOM 区，礼包名在每行重复。
        for (int index = 0; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            String bundleName = value(row, 0);
            String code = value(row, 1);
            Integer quantity = positiveCount(countValue(row, 2));
            if (bundleName.isBlank() || code.isBlank() || quantity == null) {
                continue;
            }
            result.computeIfAbsent(normalize(bundleName), ignored -> new ArrayList<>())
                    .add(new BundleComponent(code, providerNames.get(code), quantity));
        }
        // E:G 存在两个并排 BOM 块；礼包名只在块首行，需安全地向下填充到空行。
        String currentBundle = null;
        for (int index = 0; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            String bundleCell = value(row, 4);
            String code = value(row, 5);
            String quantityText = countValue(row, 6);
            if (bundleCell.isBlank() && code.isBlank() && quantityText.isBlank()) {
                currentBundle = null;
                continue;
            }
            if (!bundleCell.isBlank() && !bundleCell.startsWith("EMG") && !code.isBlank()) {
                currentBundle = bundleCell;
            }
            Integer quantity = positiveCount(quantityText);
            if (currentBundle != null && !code.isBlank() && quantity != null) {
                result.computeIfAbsent(normalize(currentBundle), ignored -> new ArrayList<>())
                        .add(new BundleComponent(code, providerNames.get(code), quantity));
            }
        }
        return result;
    }

    private int duplicateProviderCodes(Workbook workbook) {
        Map<String, Integer> counts = new HashMap<>();
        var sheet = workbook.getSheetAt(0);
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            String code = value(sheet.getRow(index), 5);
            if (!code.isBlank()) counts.merge(code, 1, Integer::sum);
        }
        return (int) counts.values().stream().filter(count -> count > 1).count();
    }

    private int ambiguousBundleRows(Workbook workbook) {
        int count = 0;
        var sheet = workbook.getSheetAt(1);
        for (int index = 0; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            String possibleCode = value(row, 4);
            if (possibleCode.startsWith("EMG") && (!value(row, 7).isBlank() || !value(row, 8).isBlank())) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> baseRow(ParsedSourceRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheet_name", row.sheetName());
        result.put("sheet_index", row.sheetIndex());
        result.put("row_index", row.rowIndex());
        result.put("source_sku_ref", row.sourceSkuRef());
        result.put("source_product_name", row.productName());
        result.put("source_quantity", row.quantity());
        result.put("bundle_components", List.of());
        return result;
    }

    private Map<String, Object> candidateMap(Candidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quantity_multiplier", candidate.quantityMultiplier());
        result.put("provider_sku_code", candidate.providerSkuCode());
        result.put("provider_sku_name", candidate.providerSkuName());
        result.put("bundle_components", candidate.bundleComponents().stream().map(component -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider_sku_code", component.providerSkuCode());
            item.put("provider_sku_name", component.providerSkuName());
            item.put("quantity_per_bundle", component.quantityPerBundle());
            return item;
        }).toList());
        return result;
    }

    private String value(Row row, int column) {
        return row == null ? "" : normalize(formatter.formatCellValue(row.getCell(column)));
    }

    private String countValue(Row row, int column) {
        return row == null ? "" : normalize(ExcelCellValues.exactCount(row.getCell(column), formatter));
    }

    private Integer positiveCount(String value) {
        try {
            return CountQuantity.fromPositiveFileValue(value);
        } catch (InvalidCountQuantityException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace("\uFEFF", "").strip();
    }

    private boolean isOoxml(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Candidate(
            String providerSkuCode,
            String providerSkuName,
            Integer quantityMultiplier,
            List<BundleComponent> bundleComponents) {

        String conflictKey() {
            String components = bundleComponents.stream()
                    .map(component -> component.providerSkuCode() + ":" + component.quantityPerBundle())
                    .sorted()
                    .reduce("", (left, right) -> left + "|" + right);
            return (providerSkuCode == null ? "" : providerSkuCode) + "#" + quantityMultiplier + "#" + components;
        }
    }

    private record BundleComponent(String providerSkuCode, String providerSkuName, Integer quantityPerBundle) {}

    private record ReferenceData(
            Map<SourceChannel, Map<String, List<Candidate>>> aliases,
            Map<String, List<BundleComponent>> bundles,
            Map<String, Object> quality) {

        List<Candidate> candidates(SourceChannel channel, String sourceProductName) {
            String key = Normalizer.normalize(
                    sourceProductName == null ? "" : sourceProductName, Normalizer.Form.NFKC).strip();
            List<Candidate> direct = aliases.getOrDefault(channel, Map.of()).getOrDefault(key, List.of());
            if (!direct.isEmpty()) {
                return direct;
            }
            List<BundleComponent> components = bundles.get(key);
            if (components == null || components.isEmpty()) {
                return List.of();
            }
            return List.of(new Candidate(null, null, 1, List.copyOf(components)));
        }
    }
}
