package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.catalog.CatalogMasterDataLock;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.MasterDataRecord;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.error.FieldErrorItem;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerJdCodeImport;
import cn.zimu.fulfillment.customer.CustomerJdCodeImportResult;
import cn.zimu.fulfillment.customer.CustomerPatch;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.customer.CustomerStatus;
import cn.zimu.fulfillment.customer.CustomerWrite;
import cn.zimu.fulfillment.product.Category;
import cn.zimu.fulfillment.product.CategoryRepository;
import cn.zimu.fulfillment.product.NamedCodePatch;
import cn.zimu.fulfillment.product.NamedCodeWrite;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductPatch;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.product.ProductWrite;
import cn.zimu.fulfillment.sku.FulfillmentProvider;
import cn.zimu.fulfillment.sku.FulfillmentProviderDto;
import cn.zimu.fulfillment.sku.FulfillmentProviderJdConfig;
import cn.zimu.fulfillment.sku.JdPiecesCandidateParser;
import cn.zimu.fulfillment.sku.FulfillmentProviderPatch;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.FulfillmentProviderWecomConfig;
import cn.zimu.fulfillment.sku.ProviderSku;
import cn.zimu.fulfillment.sku.ProviderSkuDetail;
import cn.zimu.fulfillment.sku.ProviderSkuJdFactorImport;
import cn.zimu.fulfillment.sku.ProviderSkuJdFactorImportResult;
import cn.zimu.fulfillment.sku.ProviderSkuMappingPatch;
import cn.zimu.fulfillment.sku.ProviderSkuMappingWrite;
import cn.zimu.fulfillment.sku.ProviderSkuRepository;
import cn.zimu.fulfillment.sku.ProviderType;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuCommercialPrice;
import cn.zimu.fulfillment.sku.SkuDetail;
import cn.zimu.fulfillment.sku.SkuPatch;
import cn.zimu.fulfillment.sku.SkuRepository;
import cn.zimu.fulfillment.sku.SkuSearchFilter;
import cn.zimu.fulfillment.sku.SkuWrite;
import cn.zimu.fulfillment.sku.SourceChannelSku;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import cn.zimu.fulfillment.sku.SourceSkuMappingPatch;
import cn.zimu.fulfillment.sku.SourceSkuMappingWrite;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OpenAPI 主数据查询与幂等写用例。 */
@Service
public class MasterDataService {

    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Sort ID_ASC = Sort.by("id").ascending();

    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final CatalogMasterDataLock catalogLock;
    private final CustomerRepository customers;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final SkuRepository skus;
    private final SourceChannelSkuRepository sourceMappings;
    private final ProviderSkuRepository providerMappings;
    private final FulfillmentProviderRepository providers;
    private final EntityManager entityManager;

    public MasterDataService(
            IdempotencyService idempotency,
            AuditLogService audit,
            CatalogMasterDataLock catalogLock,
            CustomerRepository customers,
            CategoryRepository categories,
            ProductRepository products,
            SkuRepository skus,
            SourceChannelSkuRepository sourceMappings,
            ProviderSkuRepository providerMappings,
            FulfillmentProviderRepository providers,
            EntityManager entityManager) {
        this.idempotency = idempotency;
        this.audit = audit;
        this.catalogLock = catalogLock;
        this.customers = customers;
        this.categories = categories;
        this.products = products;
        this.skus = skus;
        this.sourceMappings = sourceMappings;
        this.providerMappings = providerMappings;
        this.providers = providers;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> customers(int page, int size, String query) {
        String normalized = blankToNull(query);
        Page<Customer> result = normalized == null
                ? customers.findByDataScope(DataScope.BUSINESS, page(page, size))
                : customers.search(DataScope.BUSINESS, normalized, page(page, size));
        return PageResponse.of(result.stream().map(this::customer).toList(), result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord customer(long id) {
        Customer value = customers.findById(id)
                .filter(it -> it.getDataScope() == DataScope.BUSINESS)
                .orElseThrow(() -> BusinessException.notFound("客户不存在"));
        return customer(value);
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createCustomer(CustomerWrite input, String key, CommandContext ctx) {
        return write("customer.create", key, input, CREATED, ctx, () -> {
            if (customers.existsByCustomerCode(input.customerCode())) {
                throw BusinessException.conflict("CUSTOMER_CODE_EXISTS", "客户编码已存在");
            }
            Customer value = new Customer();
            value.setCustomerCode(input.customerCode());
            value.setCustomerName(input.customerName());
            value.setDataScope(DataScope.BUSINESS);
            value.setStatus(Boolean.FALSE.equals(input.active()) ? CustomerStatus.INACTIVE : CustomerStatus.ACTIVE);
            value.setProfile(customerProfile(input.departmentCode(), input.contactName(), input.contactPhone()));
            return customer(refresh(customers.saveAndFlush(value)));
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchCustomer(long id, CustomerPatch input, String key, CommandContext ctx) {
        requireAny(input.customerName(), input.departmentCode(), input.contactName(), input.contactPhone(),
                input.active(), input.jdCustomerCode());
        Customer current = customers.findById(id)
                .filter(it -> it.getDataScope() == DataScope.BUSINESS)
                .orElseThrow(() -> BusinessException.notFound("客户不存在"));
        Object jdCodeBefore = current.getProfile().get("jd_customer_code");
        // 审计负载携带变更前值：客户编码变更可追溯操作人与前后值
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("id", id);
        auditPayload.put("body", input);
        auditPayload.put("jd_customer_code_before", jdCodeBefore);
        return write("customer.update", key, auditPayload, OK, ctx, () -> {
            version(current.getLockVersion(), input.expectedVersion());
            if (input.customerName() != null) current.setCustomerName(input.customerName());
            Map<String, Object> profile = new LinkedHashMap<>(current.getProfile());
            if (input.jdCustomerCode() != null) {
                if (input.jdCustomerCode().isBlank()) {
                    profile.remove("jd_customer_code");
                } else {
                    requireJdCustomerCodeUnique(id, input.jdCustomerCode());
                    profile.put("jd_customer_code", input.jdCustomerCode());
                }
            }
            putNullable(profile, "department_code", input.departmentCode());
            putNullable(profile, "contact_name", input.contactName());
            putNullable(profile, "contact_phone", input.contactPhone());
            current.setProfile(profile);
            if (input.active() != null) current.setStatus(input.active() ? CustomerStatus.ACTIVE : CustomerStatus.INACTIVE);
            return customer(refresh(customers.saveAndFlush(current)));
        });
    }

    /**
     * 京东客户编码批量导入（jd-real-sdk-switch 02）：逐行显式报错、可重复执行、
     * 不静默覆盖既有编码。同一幂等键重放返回相同结果。
     */
    @Transactional
    public IdempotentResult<CustomerJdCodeImportResult> importJdCustomerCodes(
            CustomerJdCodeImport input, String key, CommandContext ctx) {
        List<CustomerJdCodeImport.CustomerJdCodeImportRow> rows = input.rows() == null ? List.of() : input.rows();
        if (rows.isEmpty()) {
            throw BusinessException.unprocessable("CUSTOMER_JD_CODE_IMPORT_EMPTY", "导入行不能为空");
        }
        validateImportRows(rows);
        return writeCatalogMasterData(
                "customer.jd_code.import", key, input, OK, ctx, () -> {
            List<CustomerJdCodeImportResult.ImportedRow> outcomes = new ArrayList<>();
            for (CustomerJdCodeImport.CustomerJdCodeImportRow row : rows) {
                Customer customer = customers.findByCustomerCode(row.customerCode())
                        .filter(it -> it.getDataScope() == DataScope.BUSINESS)
                        .orElseThrow(() -> BusinessException.unprocessable(
                                "CUSTOMER_JD_CODE_IMPORT_CUSTOMER_UNKNOWN",
                                "客户编码不存在: " + row.customerCode()));
                Object currentRaw = customer.getProfile().get("jd_customer_code");
                String current = currentRaw == null ? null : currentRaw.toString();
                if (row.jdCustomerCode().equals(current)) {
                    outcomes.add(importedRow(row, "SKIPPED"));
                    continue;
                }
                if (current != null) {
                    throw BusinessException.unprocessable(
                            "CUSTOMER_JD_CODE_IMPORT_CONFLICT",
                            "客户 " + row.customerCode() + " 已维护京东客户编码 " + current
                                    + "，导入值 " + row.jdCustomerCode() + " 不静默覆盖；请先用客户档案维护入口显式修改");
                }
                Long owner = jdCustomerCodeOwner(customer.getId(), row.jdCustomerCode());
                if (owner != null) {
                    throw BusinessException.unprocessable(
                            "CUSTOMER_JD_CODE_IMPORT_CONFLICT",
                            "京东客户编码 " + row.jdCustomerCode() + " 已被客户 id=" + owner + " 占用");
                }
                Map<String, Object> profile = new LinkedHashMap<>(customer.getProfile());
                profile.put("jd_customer_code", row.jdCustomerCode());
                customer.setProfile(profile);
                customers.saveAndFlush(customer);
                outcomes.add(importedRow(row, "ACCEPTED"));
            }
            int accepted = (int) outcomes.stream().filter(row -> "ACCEPTED".equals(row.status())).count();
            return new CustomerJdCodeImportResult(accepted, outcomes.size() - accepted, outcomes);
        });
    }

    /**
     * 京东件数换算批量导入（jd-real-sdk-switch 03）：只作用于京东履约方，逐行显式报错、
     * 可重复执行、不静默覆盖既有值。换算值必须为正整数件数。
     */
    @Transactional
    public IdempotentResult<ProviderSkuJdFactorImportResult> importJdPiecesPerUnit(
            ProviderSkuJdFactorImport input, String key, CommandContext ctx) {
        List<ProviderSkuJdFactorImport.ProviderSkuJdFactorRow> rows =
                input.rows() == null ? List.of() : input.rows();
        if (rows.isEmpty()) {
            throw BusinessException.unprocessable("PROVIDER_SKU_FACTOR_IMPORT_EMPTY", "导入行不能为空");
        }
        validateFactorImportRows(rows);
        long jdProviderId = jdProviderId();
        return writeCatalogMasterData(
                "provider_sku.factor.import", key, input, OK, ctx, () -> {
            List<ProviderSkuJdFactorImportResult.ImportedRow> outcomes = new ArrayList<>();
            for (ProviderSkuJdFactorImport.ProviderSkuJdFactorRow row : rows) {
                ProviderSku mapping = providerMappings
                        .findByFulfillmentProviderIdAndProviderSkuCode(jdProviderId, row.providerSkuCode())
                        .orElseThrow(() -> BusinessException.unprocessable(
                                "PROVIDER_SKU_FACTOR_IMPORT_PROVIDER_SKU_UNKNOWN",
                                "京东履约方不存在该 SKU 映射: " + row.providerSkuCode()));
                BigDecimal current = decimalOf(mapping.getExternalCodes().get("jd_pieces_per_unit"));
                BigDecimal incoming = new BigDecimal(row.jdPiecesPerUnit());
                if (current != null && current.compareTo(incoming) == 0) {
                    outcomes.add(factorImportedRow(row, "SKIPPED"));
                    continue;
                }
                if (current != null) {
                    throw BusinessException.unprocessable(
                            "PROVIDER_SKU_FACTOR_IMPORT_CONFLICT",
                            "SKU " + row.providerSkuCode() + " 已配置京东件数换算 "
                                    + current.toPlainString() + "，导入值 " + row.jdPiecesPerUnit()
                                    + " 不静默覆盖；请先显式修改");
                }
                Map<String, Object> externalCodes = new LinkedHashMap<>(mapping.getExternalCodes());
                externalCodes.put("jd_pieces_per_unit", incoming);
                mapping.setExternalCodes(externalCodes);
                providerMappings.saveAndFlush(mapping);
                outcomes.add(factorImportedRow(row, "ACCEPTED"));
            }
            int accepted = (int) outcomes.stream()
                    .filter(row -> "ACCEPTED".equals(row.status())).count();
            return new ProviderSkuJdFactorImportResult(accepted, outcomes.size() - accepted, outcomes);
        });
    }

    /**
     * 京东件数换算候选（jd-real-sdk-switch 03）：从内部 SKU 规格与来源规格解析
     * 「每包含件数」建议值；候选只读、不落库，不构成已配置换算。
     *
     * <p>注意：商品名里的 {@code *N}（如 {@code 500g*2}）是渠道销售捆绑数，属于
     * {@code source_channel_skus.quantity_multiplier}，<b>不是</b>本字段的取值来源。
     * 内部 SKU 单位与京东计数单位一致时本字段为 1；从商品名推导会与渠道乘数重复相乘。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> jdPiecesCandidates() {
        long jdProviderId = jdProviderId();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ProviderSku mapping : providerMappings
                .findByFulfillmentProviderIdOrderByProviderSkuCodeAsc(jdProviderId)) {
            Sku sku = skus.findById(mapping.getSkuId()).orElse(null);
            String specification = sku == null ? null : sku.getSpecification();
            String unit = sku == null ? null : sku.getUnit();
            String sourceName = null;
            String sourceSpecification = null;
            for (SourceChannelSku source : sourceMappings.findAll()) {
                if (source.getSkuId() != null && source.getSkuId().equals(mapping.getSkuId())) {
                    sourceName = source.getSourceProductName();
                    sourceSpecification = source.getSourceSpecification();
                    break;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider_sku_code", mapping.getProviderSkuCode());
            row.put("sku_id", id(mapping.getSkuId()));
            row.put("unit", unit);
            row.put("specification", specification);
            row.put("source_specification", sourceSpecification);
            row.put("source_product_name", sourceName);
            row.put("candidate", JdPiecesCandidateParser.candidateOrNull(
                    sourceSpecification, sourceName, specification));
            row.put("configured", decimalText(mapping.getExternalCodes().get("jd_pieces_per_unit")));
            candidates.add(row);
        }
        return candidates;
    }

    private static void validateFactorImportRows(List<ProviderSkuJdFactorImport.ProviderSkuJdFactorRow> rows) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ProviderSkuJdFactorImport.ProviderSkuJdFactorRow row = rows.get(i);
            boolean shapeInvalid = !hasText(row.providerSkuCode()) || row.providerSkuCode().length() > 128
                    || !hasText(row.jdPiecesPerUnit())
                    || !row.jdPiecesPerUnit().matches(Patterns.POSITIVE_INTEGER_QUANTITY);
            if (shapeInvalid) {
                throw BusinessException.unprocessable(
                        "PROVIDER_SKU_FACTOR_IMPORT_INVALID_ROW",
                        "第 " + (i + 1) + " 行：履约方商品编码非空且不超过 128 字符，"
                                + "京东件数换算必须为正整数件数");
            }
            if (!seen.add(row.providerSkuCode())) {
                throw BusinessException.unprocessable(
                        "PROVIDER_SKU_FACTOR_IMPORT_DUPLICATE_ROW",
                        "第 " + (i + 1) + " 行与文件内更早行重复履约方商品编码 " + row.providerSkuCode());
            }
        }
    }

    private static void validateImportRows(List<CustomerJdCodeImport.CustomerJdCodeImportRow> rows) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            CustomerJdCodeImport.CustomerJdCodeImportRow row = rows.get(i);
            boolean shapeInvalid = !hasText(row.customerCode()) || row.customerCode().length() > 64
                    || !hasText(row.jdCustomerCode()) || row.jdCustomerCode().length() > 64;
            if (shapeInvalid) {
                throw BusinessException.unprocessable(
                        "CUSTOMER_JD_CODE_IMPORT_INVALID_ROW",
                        "第 " + (i + 1) + " 行：客户编码与京东客户编码都必须是非空且不超过 64 字符");
            }
            if (!seen.add(row.customerCode())) {
                throw BusinessException.unprocessable(
                        "CUSTOMER_JD_CODE_IMPORT_DUPLICATE_ROW",
                        "第 " + (i + 1) + " 行与文件内更早行重复客户编码 " + row.customerCode());
            }
        }
    }

    private long jdProviderId() {
        return providers.findAll().stream()
                .filter(provider -> "JD".equals(provider.getProviderCode()))
                .map(FulfillmentProvider::getId)
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("京东履约方不存在"));
    }

    private static BigDecimal decimalOf(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static ProviderSkuJdFactorImportResult.ImportedRow factorImportedRow(
            ProviderSkuJdFactorImport.ProviderSkuJdFactorRow row, String status) {
        return new ProviderSkuJdFactorImportResult.ImportedRow(
                row.providerSkuCode(), row.jdPiecesPerUnit(), status);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Long jdCustomerCodeOwner(long customerId, String jdCustomerCode) {
        List<?> owners = entityManager.createNativeQuery(
                        "SELECT id FROM app.customers WHERE data_scope='BUSINESS' AND id<>? "
                                + "AND profile->>'jd_customer_code'=?")
                .setParameter(1, customerId)
                .setParameter(2, jdCustomerCode)
                .getResultList();
        return owners.isEmpty() ? null : ((Number) owners.getFirst()).longValue();
    }

    private void requireJdCustomerCodeUnique(long customerId, String jdCustomerCode) {
        Long owner = jdCustomerCodeOwner(customerId, jdCustomerCode);
        if (owner != null) {
            throw BusinessException.conflict(
                    "JD_CUSTOMER_CODE_EXISTS", "京东客户编码已被其他客户占用，请先核对客户档案");
        }
    }

    private static CustomerJdCodeImportResult.ImportedRow importedRow(
            CustomerJdCodeImport.CustomerJdCodeImportRow row, String status) {
        return new CustomerJdCodeImportResult.ImportedRow(row.customerCode(), row.jdCustomerCode(), status);
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> categories(int page, int size) {
        Page<Category> result = categories.findAll(page(page, size));
        return PageResponse.of(result.stream().map(this::category).toList(), result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord category(long id) {
        return category(categories.findById(id).orElseThrow(() -> BusinessException.notFound("品类不存在")));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createCategory(NamedCodeWrite input, String key, CommandContext ctx) {
        return writeCatalogMasterData("category.create", key, input, CREATED, ctx, () -> {
            if (categories.existsByCategoryCode(input.code())) {
                throw BusinessException.conflict("CATEGORY_CODE_EXISTS", "品类编码已存在");
            }
            Category value = new Category();
            value.setCategoryCode(input.code());
            value.setCategoryName(input.name());
            value.setActive(!Boolean.FALSE.equals(input.active()));
            return category(refresh(categories.saveAndFlush(value)));
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchCategory(long id, NamedCodePatch input, String key, CommandContext ctx) {
        requireAny(input.name(), input.active());
        return writeCatalogMasterData("category.update", key, Map.of("id", id, "body", input), OK, ctx, () -> {
            Category value = categories.findById(id).orElseThrow(() -> BusinessException.notFound("品类不存在"));
            version(value.getLockVersion(), input.expectedVersion());
            if (input.name() != null) value.setCategoryName(input.name());
            if (input.active() != null) value.setActive(input.active());
            return category(refresh(categories.saveAndFlush(value)));
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> products(int page, int size) {
        Page<Product> result = products.findAll(page(page, size));
        return PageResponse.of(result.stream().map(this::product).toList(), result);
    }

    @Transactional(readOnly = true)
    public List<String> productTags() {
        return products.distinctTags();
    }

    @Transactional(readOnly = true)
    public MasterDataRecord product(long id) {
        return product(products.findById(id).orElseThrow(() -> BusinessException.notFound("商品不存在")));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createProduct(ProductWrite input, String key, CommandContext ctx) {
        LocalDate listedFrom = parseListedDate(input.listedFrom(), "listed_from");
        LocalDate listedUntil = parseListedDate(input.listedUntil(), "listed_until");
        requireListingOrder(listedFrom, listedUntil);
        List<String> tags = normalizeTags(input.tags(), "tags");
        return writeCatalogMasterData("product.create", key, input, CREATED, ctx, () -> {
            if (products.existsByProductCode(input.productCode())) {
                throw BusinessException.conflict("PRODUCT_CODE_EXISTS", "商品编码已存在");
            }
            long categoryId = WriteCommands.parseIdentifier(input.categoryId());
            requireCategory(categoryId);
            Product value = new Product();
            value.setProductCode(input.productCode());
            value.setProductName(input.productName());
            value.setCategoryId(categoryId);
            value.setIngredients(blankToNull(input.ingredients()));
            value.setTags(tags);
            value.setListedFrom(listedFrom);
            value.setListedUntil(listedUntil);
            value.setLeadTimeHours(input.leadTimeHours());
            value.setMainImageRef(blankToNull(input.mainImageRef()));
            value.setActive(!Boolean.FALSE.equals(input.active()));
            return product(refresh(products.saveAndFlush(value)));
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createProductWithInitialSku(
            ProductWithInitialSkuWrite input, String key, CommandContext ctx) {
        ProductWrite productInput = input.product();
        InitialSkuWrite skuInput = input.sku();
        BigDecimal skuPurchasePrice = SkuCommercialPrice.parse(skuInput.purchasePrice(), "purchase_price");
        BigDecimal skuRetailPrice = SkuCommercialPrice.parse(skuInput.retailPrice(), "retail_price");
        LocalDate listedFrom = parseListedDate(productInput.listedFrom(), "listed_from");
        LocalDate listedUntil = parseListedDate(productInput.listedUntil(), "listed_until");
        requireListingOrder(listedFrom, listedUntil);
        List<String> tags = normalizeTags(productInput.tags(), "tags");
        return writeCatalogMasterData("product_with_sku.create", key, input, CREATED, ctx, () -> {
            if (products.existsByProductCode(productInput.productCode())) {
                throw BusinessException.conflict("PRODUCT_CODE_EXISTS", "商品编码已存在");
            }
            long categoryId = WriteCommands.parseIdentifier(productInput.categoryId());
            long providerId = WriteCommands.parseIdentifier(skuInput.providerId());
            requireCategory(categoryId);
            requireProvider(providerId);

            Product product = new Product();
            product.setProductCode(productInput.productCode());
            product.setProductName(productInput.productName());
            product.setCategoryId(categoryId);
            product.setIngredients(blankToNull(productInput.ingredients()));
            product.setTags(tags);
            product.setListedFrom(listedFrom);
            product.setListedUntil(listedUntil);
            product.setLeadTimeHours(productInput.leadTimeHours());
            product.setMainImageRef(blankToNull(productInput.mainImageRef()));
            product.setActive(!Boolean.FALSE.equals(productInput.active()));
            product = refresh(products.saveAndFlush(product));

            Sku sku = new Sku();
            sku.setFulfillmentProviderId(providerId);
            sku.setProductId(product.getId());
            sku.setSpecification(skuInput.specification());
            sku.setUnit(skuInput.unit());
            sku.setBarcode(blankToNull(skuInput.barcode()));
            sku.setPurchasePrice(skuPurchasePrice);
            sku.setRetailPrice(skuRetailPrice);
            sku.setActive(!Boolean.FALSE.equals(skuInput.active()));
            return sku(refresh(skus.saveAndFlush(sku)));
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchProduct(long id, ProductPatch input, String key, CommandContext ctx) {
        if (!input.anyArchiveFieldPresent()) {
            requireAny(input.productName(), input.categoryId(), input.active());
        }
        return writeCatalogMasterData("product.update", key, productPatchPayload(id, input), OK, ctx, () -> {
            Product value = products.findById(id).orElseThrow(() -> BusinessException.notFound("商品不存在"));
            version(value.getLockVersion(), input.expectedVersion());
            if (input.productName() != null) value.setProductName(input.productName());
            if (input.categoryId() != null) {
                long categoryId = WriteCommands.parseIdentifier(input.categoryId());
                requireCategory(categoryId);
                value.setCategoryId(categoryId);
            }
            if (input.active() != null) value.setActive(input.active());
            if (input.ingredientsPresent()) value.setIngredients(blankToNull(input.ingredients()));
            if (input.tagsPresent()) value.setTags(normalizeTags(input.tags(), "tags"));
            if (input.listedFromPresent()) value.setListedFrom(parseListedDate(input.listedFrom(), "listed_from"));
            if (input.listedUntilPresent()) value.setListedUntil(parseListedDate(input.listedUntil(), "listed_until"));
            if (input.leadTimeHoursPresent()) value.setLeadTimeHours(input.leadTimeHours());
            if (input.mainImageRefPresent()) value.setMainImageRef(blankToNull(input.mainImageRef()));
            requireListingOrder(value.getListedFrom(), value.getListedUntil());
            return product(refresh(products.saveAndFlush(value)));
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> skus(int page, int size, String providerId, String query) {
        Page<Sku> result;
        if (query != null && !query.isBlank()) {
            // 商品名/规格/SKU 编码大小写不敏感模糊检索，可与履约方筛选叠加。
            result = skus.search(
                    "%" + query.trim() + "%",
                    providerId == null ? null : WriteCommands.parseIdentifier(providerId),
                    page(page, size));
        } else if (providerId == null) {
            result = skus.findAll(page(page, size));
        } else {
            result = skus.findByFulfillmentProviderId(WriteCommands.parseIdentifier(providerId), page(page, size));
        }
        Map<Long, String> jdEmgCodes = jdEmgCodes(result.stream().map(Sku::getId).toList());
        return PageResponse.of(
                result.stream().map(sku -> sku(sku, jdEmgCodes.get(sku.getId()))).toList(),
                result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord sku(long id) {
        return sku(skus.findById(id).orElseThrow(() -> BusinessException.notFound("SKU 不存在")));
    }

    @Transactional(readOnly = true)
    public PageResponse<SkuDetail> searchSkus(int page, int size, SkuSearchFilter filter) {
        String pattern = filter.query() == null ? null : "%" + filter.query() + "%";
        Page<Sku> result = skus.searchFiltered(
                pattern,
                filter.providerId(),
                filter.barcode(),
                filter.skuCode(),
                filter.categoryId(),
                filter.tag(),
                filter.active(),
                PageRequest.of(page, size));
        return PageResponse.of(result.stream().map(this::skuDetail).toList(), result);
    }

    @Transactional(readOnly = true)
    public SkuDetail skuDetail(long id) {
        return skuDetail(skus.findById(id).orElseThrow(() -> BusinessException.notFound("SKU 不存在")));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createSku(SkuWrite input, String key, CommandContext ctx) {
        BigDecimal purchasePrice = SkuCommercialPrice.parse(input.purchasePrice(), "purchase_price");
        BigDecimal retailPrice = SkuCommercialPrice.parse(input.retailPrice(), "retail_price");
        return writeCatalogMasterData("sku.create", key, input, CREATED, ctx, () -> {
            long providerId = WriteCommands.parseIdentifier(input.providerId());
            long productId = WriteCommands.parseIdentifier(input.productId());
            requireProvider(providerId);
            requireProduct(productId);
            Sku value = new Sku();
            value.setFulfillmentProviderId(providerId);
            value.setProductId(productId);
            value.setSpecification(input.specification());
            value.setUnit(input.unit());
            value.setBarcode(input.barcode());
            value.setPurchasePrice(purchasePrice);
            value.setRetailPrice(retailPrice);
            value.setActive(!Boolean.FALSE.equals(input.active()));
            return sku(refresh(skus.saveAndFlush(value)));
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchSku(long id, SkuPatch input, String key, CommandContext ctx) {
        if (!input.purchasePricePresent() && !input.retailPricePresent()) {
            requireAny(input.specification(), input.unit(), input.barcode(), input.active());
        }
        BigDecimal purchasePrice = input.purchasePricePresent()
                ? SkuCommercialPrice.parse(input.purchasePrice(), "purchase_price") : null;
        BigDecimal retailPrice = input.retailPricePresent()
                ? SkuCommercialPrice.parse(input.retailPrice(), "retail_price") : null;
        return writeCatalogMasterData("sku.update", key, skuPatchPayload(id, input), OK, ctx, () -> {
            Sku value = skus.findById(id).orElseThrow(() -> BusinessException.notFound("SKU 不存在"));
            version(value.getLockVersion(), input.expectedVersion());
            if (input.specification() != null) value.setSpecification(input.specification());
            if (input.unit() != null) value.setUnit(input.unit());
            if (input.barcode() != null) value.setBarcode(input.barcode());
            if (input.purchasePricePresent()) value.setPurchasePrice(purchasePrice);
            if (input.retailPricePresent()) value.setRetailPrice(retailPrice);
            if (input.active() != null) value.setActive(input.active());
            return sku(refresh(skus.saveAndFlush(value)));
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> sourceMappings(int page, int size, SourceChannel channel) {
        Page<SourceChannelSku> result = channel == null
                ? sourceMappings.findAll(page(page, size))
                : sourceMappings.findBySourceChannel(channel, page(page, size));
        return PageResponse.of(result.stream().map(this::sourceMapping).toList(), result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord sourceMapping(long id) {
        return sourceMapping(sourceMappings.findById(id)
                .orElseThrow(() -> BusinessException.notFound("来源 SKU 映射不存在")));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createSourceMapping(
            SourceSkuMappingWrite input, String key, CommandContext ctx) {
        return write("source_sku_mapping.create", key, input, CREATED, ctx, () -> {
            if (sourceMappings.existsBySourceChannelAndSourceSkuRef(input.sourceChannel(), input.sourceSkuRef())) {
                throw BusinessException.conflict("SOURCE_SKU_MAPPING_EXISTS", "来源 SKU 映射已存在");
            }
            long skuId = WriteCommands.parseIdentifier(input.skuId());
            requireSku(skuId);
            SourceChannelSku value = new SourceChannelSku();
            value.setSourceChannel(input.sourceChannel());
            value.setSourceSkuRef(input.sourceSkuRef());
            value.setSourceProductName(input.sourceSkuName());
            value.setSkuId(skuId);
            value.setQuantityMultiplier(new BigDecimal(input.quantityMultiplier()));
            value.setActive(!Boolean.FALSE.equals(input.active()));
            return sourceMapping(refresh(sourceMappings.saveAndFlush(value)));
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchSourceMapping(
            long id, SourceSkuMappingPatch input, String key, CommandContext ctx) {
        requireAny(input.skuId(), input.quantityMultiplier(), input.active());
        return write("source_sku_mapping.update", key, Map.of("id", id, "body", input), OK, ctx, () -> {
            SourceChannelSku value = sourceMappings.findById(id)
                    .orElseThrow(() -> BusinessException.notFound("来源 SKU 映射不存在"));
            version(value.getLockVersion(), input.expectedVersion());
            if (input.skuId() != null) {
                long skuId = WriteCommands.parseIdentifier(input.skuId());
                requireSku(skuId);
                value.setSkuId(skuId);
            }
            if (input.quantityMultiplier() != null) value.setQuantityMultiplier(new BigDecimal(input.quantityMultiplier()));
            if (input.active() != null) value.setActive(input.active());
            return sourceMapping(refresh(sourceMappings.saveAndFlush(value)));
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> providerMappings(int page, int size) {
        Page<ProviderSku> result = providerMappings.findAll(page(page, size));
        return PageResponse.of(result.stream().map(this::providerMapping).toList(), result);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProviderSkuDetail> providerSkus(long providerId, int page, int size) {
        if (!providers.existsById(providerId)) {
            throw BusinessException.notFound("履约方不存在");
        }
        Sort sort = Sort.by("providerSkuCode").ascending().and(Sort.by("id").ascending());
        Page<ProviderSku> result =
                providerMappings.findByFulfillmentProviderId(providerId, PageRequest.of(page, size, sort));
        return PageResponse.of(result.stream().map(this::providerSkuDetail).toList(), result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord providerMapping(long id) {
        return providerMapping(providerMappings.findById(id)
                .orElseThrow(() -> BusinessException.notFound("履约方 SKU 映射不存在")));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createProviderMapping(
            ProviderSkuMappingWrite input, String key, CommandContext ctx) {
        return writeCatalogMasterData("provider_sku_mapping.create", key, input, CREATED, ctx, () -> {
            long providerId = WriteCommands.parseIdentifier(input.providerId());
            long skuId = WriteCommands.parseIdentifier(input.skuId());
            requireProvider(providerId);
            Sku sku = requireSku(skuId);
            if (!sku.getFulfillmentProviderId().equals(providerId)) {
                throw BusinessException.unprocessable("SKU_PROVIDER_MISMATCH", "SKU 与履约方不匹配");
            }
            if (providerMappings.existsByFulfillmentProviderIdAndProviderSkuCode(providerId, input.providerSkuCode())) {
                throw BusinessException.conflict("PROVIDER_SKU_MAPPING_EXISTS", "履约方 SKU 映射已存在");
            }
            if (providerMappings.existsByFulfillmentProviderIdAndSkuId(providerId, skuId)) {
                throw BusinessException.conflict("PROVIDER_SKU_MAPPING_EXISTS", "该内部 SKU 已绑定此履约方的其他商品编号");
            }
            ProviderSku value = new ProviderSku();
            value.setFulfillmentProviderId(providerId);
            value.setSkuId(skuId);
            value.setProviderSkuCode(input.providerSkuCode());
            value.setMerchantSkuCode(input.merchantSkuCode());
            value.setExternalCodes(providerMappingDetails(input.providerSkuName(), input.jdPiecesPerUnit()));
            value.setActive(!Boolean.FALSE.equals(input.active()));
            return providerMapping(refresh(providerMappings.saveAndFlush(value)));
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchProviderMapping(
            long id, ProviderSkuMappingPatch input, String key, CommandContext ctx) {
        requireAny(
                input.providerSkuCode(), input.merchantSkuCode(), input.providerSkuName(),
                input.jdPiecesPerUnit(), input.active());
        return writeCatalogMasterData(
                "provider_sku_mapping.update", key, Map.of("id", id, "body", input), OK, ctx, () -> {
            ProviderSku value = providerMappings.findById(id)
                    .orElseThrow(() -> BusinessException.notFound("履约方 SKU 映射不存在"));
            version(value.getLockVersion(), input.expectedVersion());
            if (input.providerSkuCode() != null) {
                if (providerMappings.existsByFulfillmentProviderIdAndProviderSkuCode(
                        value.getFulfillmentProviderId(), input.providerSkuCode())
                        && !input.providerSkuCode().equals(value.getProviderSkuCode())) {
                    throw BusinessException.conflict("PROVIDER_SKU_MAPPING_EXISTS", "履约方 SKU 映射已存在");
                }
                value.setProviderSkuCode(input.providerSkuCode());
            }
            if (input.merchantSkuCode() != null) value.setMerchantSkuCode(input.merchantSkuCode());
            if (input.providerSkuName() != null) {
                Map<String, Object> externalCodes = new LinkedHashMap<>(value.getExternalCodes());
                externalCodes.put("provider_sku_name", input.providerSkuName());
                value.setExternalCodes(externalCodes);
            }
            if (input.jdPiecesPerUnit() != null) {
                Map<String, Object> externalCodes = new LinkedHashMap<>(value.getExternalCodes());
                externalCodes.put("jd_pieces_per_unit", new BigDecimal(input.jdPiecesPerUnit()));
                value.setExternalCodes(externalCodes);
            }
            if (input.active() != null) value.setActive(input.active());
            return providerMapping(refresh(providerMappings.saveAndFlush(value)));
        });
    }

    @Transactional(readOnly = true)
    public List<FulfillmentProviderDto> providers() {
        return providers.findAll(ID_ASC).stream().map(this::provider).toList();
    }

    @Transactional(readOnly = true)
    public FulfillmentProviderDto provider(long id) {
        return provider(providers.findById(id).orElseThrow(() -> BusinessException.notFound("履约方不存在")));
    }

    @Transactional
    public IdempotentResult<FulfillmentProviderDto> patchProvider(
            long id, FulfillmentProviderPatch input, String key, CommandContext ctx) {
        requireAny(input.providerName(), input.trackingSlaMinutes(), input.active(), input.config());
        if (input.trackingSlaMinutes() != null && input.trackingSlaMinutes() < 1) {
            throw BusinessException.badRequest("INVALID_TRACKING_SLA", "物流时效必须大于 0 分钟");
        }
        Map<String, Object> validatedConfig = input.config() == null
                ? null
                : validateProviderConfig(input.config());
        // 审计负载不携带 pin 明文：以校验后的投影（敏感键仅存在性标记）替代原始请求体
        Object auditInput = input.config() == null
                ? input
                : new FulfillmentProviderPatch(
                        input.expectedVersion(), input.providerName(), input.trackingSlaMinutes(), input.active(),
                        FulfillmentProviderJdConfig.auditSafe(validatedConfig));
        return writeCatalogMasterData(
                "fulfillment_provider.update", key, Map.of("id", id, "body", auditInput), OK, ctx, () -> {
            FulfillmentProvider value = providers.findById(id)
                    .orElseThrow(() -> BusinessException.notFound("履约方不存在"));
            version(value.getLockVersion(), input.expectedVersion());
            if (input.providerName() != null) value.setProviderName(input.providerName());
            if (input.trackingSlaMinutes() != null) value.setTrackingSlaMinutes(input.trackingSlaMinutes());
            if (input.active() != null) value.setActive(input.active());
            if (validatedConfig != null) {
                Map<String, Object> config = new LinkedHashMap<>(value.getConfig());
                validatedConfig.forEach((configKey, configValue) -> {
                    if (configValue == null) {
                        config.remove(configKey);
                    } else {
                        config.put(configKey, configValue);
                    }
                });
                value.setConfig(config);
            }
            return provider(refresh(providers.saveAndFlush(value)));
        });
    }

    private <T> IdempotentResult<T> write(
            String operation, String key, Object payload, int status, CommandContext ctx, Supplier<T> work) {
        return idempotency.execute(operation, key, payload, status, () -> {
            T result = work.get();
            audit.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(ctx.requestId())
                    .traceId(ctx.traceId())
                    .operator(ctx.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("MasterDataService")
                    .operation(operation)
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(status)
                    .businessCode("SUCCESS"));
            return result;
        });
    }

    private <T> IdempotentResult<T> writeCatalogMasterData(
            String operation, String key, Object payload, int status, CommandContext ctx, Supplier<T> work) {
        return write(operation, key, payload, status, ctx, () -> {
            catalogLock.lockForMasterDataWrite();
            return work.get();
        });
    }

    private <T> T refresh(T value) {
        entityManager.refresh(value);
        return value;
    }

    private MasterDataRecord customer(Customer value) {
        return record(value.getId(), value.getCustomerCode(), value.getCustomerName(),
                value.getStatus() == CustomerStatus.ACTIVE, value.getLockVersion(), value.getProfile(), value);
    }

    private MasterDataRecord category(Category value) {
        return record(value.getId(), value.getCategoryCode(), value.getCategoryName(), value.isActive(),
                value.getLockVersion(), map("parent_id", value.getParentId()), value);
    }

    private MasterDataRecord product(Product value) {
        Map<String, Object> attributes = map(
                "category_id", id(value.getCategoryId()),
                "description", value.getDescription());
        attributes.put("ingredients", value.getIngredients());
        attributes.put("tags", value.getTags());
        attributes.put("listed_from", value.getListedFrom() == null ? null : value.getListedFrom().toString());
        attributes.put("listed_until", value.getListedUntil() == null ? null : value.getListedUntil().toString());
        attributes.put("lead_time_hours", value.getLeadTimeHours());
        attributes.put("main_image_ref", value.getMainImageRef());
        return record(value.getId(), value.getProductCode(), value.getProductName(), value.isActive(),
                value.getLockVersion(), attributes, value);
    }

    private MasterDataRecord sku(Sku value) {
        return sku(value, jdEmgNo(value.getId()));
    }

    private MasterDataRecord sku(Sku value, String jdEmgNo) {
        Product product = products.findById(value.getProductId()).orElse(null);
        Map<String, Object> attributes = map(
                "product_id", id(value.getProductId()),
                "category_id", product == null ? null : id(product.getCategoryId()),
                "provider_id", id(value.getFulfillmentProviderId()),
                "specification", value.getSpecification(),
                "unit", value.getUnit(),
                "barcode", value.getBarcode());
        attributes.put("purchase_price", SkuCommercialPrice.text(value.getPurchasePrice()));
        attributes.put("retail_price", SkuCommercialPrice.text(value.getRetailPrice()));
        attributes.put("margin", marginText(value.getRetailPrice(), value.getPurchasePrice()));
        attributes.put("jd_emg_no", jdEmgNo);
        if (product != null) {
            attributes.put("product_version", product.getLockVersion());
            attributes.put("product_tags", product.getTags());
            attributes.put("product_ingredients", product.getIngredients());
            attributes.put("product_listed_from",
                    product.getListedFrom() == null ? null : product.getListedFrom().toString());
            attributes.put("product_listed_until",
                    product.getListedUntil() == null ? null : product.getListedUntil().toString());
            attributes.put("product_lead_time_hours", product.getLeadTimeHours());
            attributes.put("product_main_image_ref", product.getMainImageRef());
        }
        return record(value.getId(), value.getSkuCode(), product == null ? value.getSkuCode() : product.getProductName(),
                value.isActive(), value.getLockVersion(), attributes, value);
    }

    /** 单个 SKU 的京东 EMG 编号（单条读取/写后投影用）。 */
    private String jdEmgNo(long skuId) {
        List<ProviderSkuRepository.JdProviderSkuCode> rows =
                providerMappings.findJdProviderSkuCodes(List.of(skuId));
        return rows.isEmpty() ? null : rows.getFirst().getProviderSkuCode();
    }

    /** 批量 SKU 的京东 EMG 编号；无京东映射的 SKU 不出现在结果中。 */
    private Map<Long, String> jdEmgCodes(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> codes = new LinkedHashMap<>();
        for (ProviderSkuRepository.JdProviderSkuCode row : providerMappings.findJdProviderSkuCodes(skuIds)) {
            codes.putIfAbsent(row.getSkuId(), row.getProviderSkuCode());
        }
        return codes;
    }

    private MasterDataRecord sourceMapping(SourceChannelSku value) {
        String code = value.getSourceChannel() + ":" + value.getSourceSkuRef();
        String name = value.getSourceProductName() == null ? value.getSourceSkuRef() : value.getSourceProductName();
        return record(value.getId(), code, name, value.isActive(), value.getLockVersion(),
                map("source_channel", value.getSourceChannel(), "source_sku_ref", value.getSourceSkuRef(),
                        "sku_id", id(value.getSkuId()), "quantity_multiplier",
                        value.getQuantityMultiplier() == null ? null : value.getQuantityMultiplier().toPlainString()), value);
    }

    private MasterDataRecord providerMapping(ProviderSku value) {
        String name = value.getExternalCodes().get("provider_sku_name") instanceof String text
                ? text : value.getProviderSkuCode();
        return record(value.getId(), value.getProviderSkuCode(), name, value.isActive(), value.getLockVersion(),
                map("provider_id", id(value.getFulfillmentProviderId()), "sku_id", id(value.getSkuId()),
                        "provider_sku_code", value.getProviderSkuCode(), "provider_sku_name", name,
                        "merchant_sku_code", value.getMerchantSkuCode(), "jd_pieces_per_unit",
                        decimalText(value.getExternalCodes().get("jd_pieces_per_unit"))), value);
    }

    private FulfillmentProviderDto provider(FulfillmentProvider value) {
        Map<String, Object> jdConfig = ProviderType.JD_WAREHOUSE.equals(value.getProviderType())
                ? FulfillmentProviderJdConfig.status(value.getConfig())
                : Map.of();
        return new FulfillmentProviderDto(id(value.getId()), value.getProviderCode(), value.getProviderName(),
                value.getProviderType().name(), value.getTrackingSlaMinutes(), value.isActive(), value.getLockVersion(),
                jdConfig, wecomGroupChatId(value.getConfig()), wecomReminderIntervalMinutes(value.getConfig()));
    }

    private SkuDetail skuDetail(Sku value) {
        Product product = products.findById(value.getProductId()).orElse(null);
        FulfillmentProvider provider = providers.findById(value.getFulfillmentProviderId()).orElse(null);
        return new SkuDetail(
                id(value.getId()),
                value.getSkuCode(),
                id(value.getProductId()),
                product == null ? null : product.getProductCode(),
                product == null ? null : product.getProductName(),
                product == null ? null : id(product.getCategoryId()),
                value.getSpecification(),
                value.getUnit(),
                value.getBarcode(),
                SkuCommercialPrice.text(value.getPurchasePrice()),
                SkuCommercialPrice.text(value.getRetailPrice()),
                value.isActive(),
                id(value.getFulfillmentProviderId()),
                provider == null ? null : provider.getProviderCode(),
                provider == null ? null : provider.getProviderName(),
                provider == null ? null : provider.getProviderType().name(),
                value.getCreatedAt(),
                value.getUpdatedAt());
    }

    private ProviderSkuDetail providerSkuDetail(ProviderSku value) {
        Sku sku = skus.findById(value.getSkuId()).orElse(null);
        FulfillmentProvider provider = providers.findById(value.getFulfillmentProviderId()).orElse(null);
        Map<String, Object> external = value.getExternalCodes();
        String name = external.get("provider_sku_name") instanceof String text ? text : null;
        return new ProviderSkuDetail(
                id(value.getId()),
                id(value.getFulfillmentProviderId()),
                provider == null ? null : provider.getProviderCode(),
                provider == null ? null : provider.getProviderName(),
                id(value.getSkuId()),
                sku == null ? null : sku.getSkuCode(),
                value.getProviderSkuCode(),
                value.getMerchantSkuCode(),
                value.isActive(),
                name,
                decimalText(external.get("jd_pieces_per_unit")));
    }

    private MasterDataRecord record(
            Long id, String code, String name, boolean active, Long version, Map<String, Object> attributes,
            cn.zimu.fulfillment.common.jpa.AuditableEntity value) {
        return new MasterDataRecord(id(id), code, name, active, version, attributes, value.getCreatedAt(), value.getUpdatedAt());
    }

    private PageRequest page(int page, int size) {
        return PageRequest.of(page, size, ID_ASC);
    }

    private void requireCategory(long id) {
        if (!categories.existsById(id)) throw BusinessException.notFound("品类不存在");
    }

    private void requireProduct(long id) {
        if (!products.existsById(id)) throw BusinessException.notFound("商品不存在");
    }

    private void requireProvider(long id) {
        if (!providers.existsById(id)) throw BusinessException.notFound("履约方不存在");
    }

    private Sku requireSku(long id) {
        return skus.findById(id).orElseThrow(() -> BusinessException.notFound("SKU 不存在"));
    }

    private static void version(Long actual, Long expected) {
        if (!actual.equals(expected)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "数据已被其他请求修改，请刷新后重试");
        }
    }

    private static void requireAny(Object... values) {
        for (Object value : values) if (value != null) return;
        throw BusinessException.badRequest("PATCH_EMPTY", "至少需要修改一个业务字段");
    }

    /**
     * 履约方 config 合并校验（Issue #83/#84）：京东标识键走 {@link FulfillmentProviderJdConfig}，
     * 企微群 chatid 与回传提醒间隔走 {@link FulfillmentProviderWecomConfig}；两者之外的键由京东
     * 契约以未知键拒绝。每个键族只由各自的契约模块解析，不在此处重复实现键规则。
     */
    private static Map<String, Object> validateProviderConfig(Map<String, Object> patch) {
        Map<String, Object> jdPatch = new LinkedHashMap<>();
        Object wecomGroupChatId = null;
        boolean hasWecomGroupChatId = false;
        Object wecomReminderInterval = null;
        boolean hasWecomReminderInterval = false;
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (FulfillmentProviderWecomConfig.GROUP_CHAT_ID_KEY.equals(entry.getKey())) {
                wecomGroupChatId = entry.getValue();
                hasWecomGroupChatId = true;
            } else if (FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY.equals(entry.getKey())) {
                wecomReminderInterval = entry.getValue();
                hasWecomReminderInterval = true;
            } else {
                jdPatch.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> validated = new LinkedHashMap<>(FulfillmentProviderJdConfig.validate(jdPatch));
        if (hasWecomGroupChatId) {
            validated.put(
                    FulfillmentProviderWecomConfig.GROUP_CHAT_ID_KEY,
                    FulfillmentProviderWecomConfig.validate(wecomGroupChatId));
        }
        if (hasWecomReminderInterval) {
            validated.put(
                    FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY,
                    FulfillmentProviderWecomConfig.validateReminderInterval(wecomReminderInterval));
        }
        return validated;
    }

    /** 对外投影：只回显符合写入规则的已登记企微群 chatid；未配置/非法存量值一律投影为 null。 */
    private static String wecomGroupChatId(Map<String, Object> config) {
        Object value = config == null ? null : config.get(FulfillmentProviderWecomConfig.GROUP_CHAT_ID_KEY);
        return FulfillmentProviderWecomConfig.normalizeStored(value);
    }

    /** 对外投影：回传提醒间隔分钟；未配置/非法存量值投影为 null（前端按 SLA 默认展示）。 */
    private static Integer wecomReminderIntervalMinutes(Map<String, Object> config) {
        try {
            return FulfillmentProviderWecomConfig.validateReminderInterval(
                    config == null ? null : config.get(FulfillmentProviderWecomConfig.REMINDER_INTERVAL_KEY));
        } catch (BusinessException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** SKU 单位毛利 = 零售价 - 进货价；任一 SKU 价格缺失则视为未定价（null）。 */
    private static String marginText(BigDecimal retailPrice, BigDecimal purchasePrice) {
        if (retailPrice == null || purchasePrice == null) return null;
        return SkuCommercialPrice.text(retailPrice.subtract(purchasePrice));
    }

    /** 上市周期日期：YYYY-MM-DD，非法格式报字段错误。 */
    private static LocalDate parseListedDate(String raw, String field) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException exception) {
            throw invalidArchiveField(field, "日期格式必须为 YYYY-MM-DD");
        }
    }

    private static void requireListingOrder(LocalDate listedFrom, LocalDate listedUntil) {
        if (listedFrom != null && listedUntil != null && listedFrom.isAfter(listedUntil)) {
            throw invalidArchiveField("listed_from", "上市周期起始日期不能晚于结束日期");
        }
    }

    /** 标签归一化：去首尾空白、去重、空列表视为未填写（null）。 */
    private static List<String> normalizeTags(List<String> tags, String field) {
        if (tags == null) return null;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                throw invalidArchiveField(field, "商品标签不能为 null");
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) normalized.add(trimmed);
        }
        return normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    private static BusinessException invalidArchiveField(String field, String message) {
        return new BusinessException(
                400,
                "INVALID_PRODUCT_ARCHIVE_FIELD",
                "商品档案字段无效",
                List.of(new FieldErrorItem(field, "Pattern", message)),
                Map.of());
    }

    private static Map<String, Object> customerProfile(String departmentCode, String contactName, String contactPhone) {
        return map("department_code", departmentCode, "contact_name", contactName, "contact_phone", contactPhone);
    }

    private static Map<String, Object> providerMappingDetails(String name, String jdPiecesPerUnit) {
        return map(
                "provider_sku_name", name,
                "jd_pieces_per_unit", jdPiecesPerUnit == null ? null : new BigDecimal(jdPiecesPerUnit));
    }

    private static Map<String, Object> skuPatchPayload(long id, SkuPatch input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", input.expectedVersion());
        putNullable(body, "specification", input.specification());
        putNullable(body, "unit", input.unit());
        putNullable(body, "barcode", input.barcode());
        putNullable(body, "active", input.active());
        if (input.purchasePricePresent()) body.put("purchase_price", input.purchasePrice());
        if (input.retailPricePresent()) body.put("retail_price", input.retailPrice());
        return Map.of("id", id, "body", body);
    }

    /** 商品更新审计载荷：只记录显式出现的字段（含显式 null 清空）。 */
    private static Map<String, Object> productPatchPayload(long id, ProductPatch input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", input.expectedVersion());
        putNullable(body, "product_name", input.productName());
        putNullable(body, "category_id", input.categoryId());
        putNullable(body, "active", input.active());
        if (input.ingredientsPresent()) body.put("ingredients", input.ingredients());
        if (input.tagsPresent()) body.put("tags", input.tags());
        if (input.listedFromPresent()) body.put("listed_from", input.listedFrom());
        if (input.listedUntilPresent()) body.put("listed_until", input.listedUntil());
        if (input.leadTimeHoursPresent()) body.put("lead_time_hours", input.leadTimeHours());
        if (input.mainImageRefPresent()) body.put("main_image_ref", input.mainImageRef());
        return Map.of("id", id, "body", body);
    }

    private static String decimalText(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            return value.toString();
        }
    }

    private static void putNullable(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i + 1] != null) result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    private static String id(Long value) {
        return value == null ? null : value.toString();
    }
}
