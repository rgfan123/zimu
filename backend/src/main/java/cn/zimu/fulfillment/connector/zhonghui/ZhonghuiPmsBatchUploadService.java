package cn.zimu.fulfillment.connector.zhonghui;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateResult;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsVerifyView;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductImageService;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 「从商品档案批量上传商品到中汇 PMS」应用层用例：读取本地商品档案（Sku + 商品族 Product），
 * 按配置默认值与批次覆盖值映射为中汇创建商品载荷，逐商品调用中汇 PMS 创建商品，返回逐商品结果。
 *
 * <p>满足 api-contract §3.5「先持久化意图/批次，再由 Adapter 执行并回写结果」与 §3.2 幂等语义：
 * <ul>
 *   <li>经 {@link IdempotencyService#executeWithExternalWriteIntent} 执行：同幂等键+同请求重放首次结果
 *       （不会重复调用 PMS 创建商品）；同键+不同请求 409；</li>
 *   <li>意图（批次 PENDING + 逐商品 PENDING 行）先在独立事务落库，再逐商品执行并回写
 *       SUCCESS/FAILED（含商品列表校验的 goodsId / 审核状态与 warning），批次最后置 COMPLETED；</li>
 *   <li>中途断连遗留 PENDING 行即为可审计/可恢复的意图记录。</li>
 * </ul>
 */
@Service
public class ZhonghuiPmsBatchUploadService {

    private static final String SCOPE = "zhonghui-pms.batch-upload";
    private static final String WARNING_NO_MAIN_IMAGE =
            "商品缺少主图，photoStr/details 为空，PMS 可能拒绝创建";

    private final ZhonghuiPmsService client;
    private final ZhonghuiPmsProperties properties;
    private final SkuRepository skus;
    private final ProductRepository products;
    private final ProductImageService productImageService;
    private final ZhonghuiPmsUploadBatchRepository batchRepository;
    private final ZhonghuiPmsUploadBatchItemRepository itemRepository;
    private final IdempotencyService idempotency;

    public ZhonghuiPmsBatchUploadService(
            ZhonghuiPmsService client,
            ZhonghuiPmsProperties properties,
            SkuRepository skus,
            ProductRepository products,
            ProductImageService productImageService,
            ZhonghuiPmsUploadBatchRepository batchRepository,
            ZhonghuiPmsUploadBatchItemRepository itemRepository,
            IdempotencyService idempotency) {
        this.client = client;
        this.properties = properties;
        this.skus = skus;
        this.products = products;
        this.productImageService = productImageService;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.idempotency = idempotency;
    }

    /**
     * 批量上传：幂等注册表驱动（意图先落库 → 逐商品执行回写 → 批次收尾归档）。
     * 同幂等键+同请求重放首次结果，避免重复调用 PMS 创建商品。
     */
    public IdempotentResult<BatchUploadView> upload(
            BatchUploadCommand command, String idempotencyKey) {
        properties.requireExternalWritesEnabled();
        if (!client.authenticated()) {
            throw BusinessException.unprocessable(
                    "PMS_LOGIN_REQUIRED", "请先完成中汇 PMS 登录（获取验证码后输入验证码）");
        }
        if (command.skuIds() == null || command.skuIds().isEmpty()) {
            throw BusinessException.badRequest("EMPTY_SKU_IDS", "请至少选择一个商品档案 SKU");
        }
        return idempotency.executeWithExternalWriteIntent(
                SCOPE,
                idempotencyKey,
                Map.of(
                        "sku_ids", command.skuIds(),
                        "overrides", command.overrides() == null ? Map.of() : command.overrides(),
                        "client_mode", properties.getClientMode()),
                200,
                () -> createBatchIntent(command),
                batchId -> executeBatch(batchId, command),
                (batchId, items) -> IdempotencyService.ExternalCompletion.succeeded(
                        completeBatch(batchId, items)));
    }

    /** 批次详情（含逐商品结果），用于恢复/审计。 */
    public BatchDetailView batch(long id) {
        ZhonghuiPmsUploadBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("上传批次不存在"));
        List<BatchUploadView.ItemView> items = itemRepository.findByBatchIdOrderById(id).stream()
                .map(this::toItemView)
                .toList();
        return new BatchDetailView(
                String.valueOf(batch.getId()), batch.getBatchNo(), batch.getStatus().name(),
                batch.getTotal(), batch.getSucceeded(), batch.getFailed(),
                batch.getCreatedBy(), batch.getCreatedAt(), batch.getCompletedAt(), items);
    }

    private BatchUploadView.ItemView toItemView(ZhonghuiPmsUploadBatchItem item) {
        return new BatchUploadView.ItemView(
                String.valueOf(item.getSkuId()),
                item.getSkuCode(),
                item.getGoodsName(),
                ZhonghuiPmsUploadBatchItemStatus.SUCCESS == item.getStatus(),
                item.getBusinessCode(),
                item.getMessage(),
                item.getGoodsId() == null ? null : String.valueOf(item.getGoodsId()),
                item.getPmsStatus(),
                item.getWarning());
    }

    /** 幂等意图（独立事务）：批次 PENDING + 逐商品 PENDING 行；返回批次 id。 */
    private Long createBatchIntent(BatchUploadCommand command) {
        ZhonghuiPmsUploadBatch batch = new ZhonghuiPmsUploadBatch();
        batch.setStatus(ZhonghuiPmsUploadBatchStatus.PENDING);
        batch.setTotal(command.skuIds().size());
        RequestContext context = RequestContext.current();
        batch.setCreatedBy(context == null || context.getOperator() == null
                ? "zhonghui-pms" : context.getOperator());
        // batch_no 使用 DB 序列原子流水，保证唯一（列 NOT NULL UNIQUE）。
        batch.setBatchNo("PMS-" + String.format("%08d", batchRepository.nextBatchNo()));
        batch = batchRepository.saveAndFlush(batch);
        for (String skuId : command.skuIds()) {
            ZhonghuiPmsUploadBatchItem item = new ZhonghuiPmsUploadBatchItem();
            item.setBatchId(batch.getId());
            item.setSkuId(parseId(skuId));
            item.setStatus(ZhonghuiPmsUploadBatchItemStatus.PENDING);
            itemRepository.save(item);
        }
        return batch.getId();
    }

    /** 逐商品执行外部创建并回写结果（外部调用不在事务内）。 */
    private List<BatchUploadView.ItemView> executeBatch(Long batchId, BatchUploadCommand command) {
        Overrides overrides = command.overrides() == null ? Overrides.empty() : command.overrides();
        List<BatchUploadView.ItemView> views = new ArrayList<>();
        for (String skuId : command.skuIds()) {
            BatchUploadView.ItemView outcome = uploadOne(skuId, overrides);
            updateItemRow(batchId, parseId(skuId), outcome);
            views.add(outcome);
        }
        return views;
    }

    /** 批次收尾：置 COMPLETED 并写总数，返回对外响应视图。 */
    private BatchUploadView completeBatch(Long batchId, List<BatchUploadView.ItemView> items) {
        ZhonghuiPmsUploadBatch batch = batchRepository.findById(batchId).orElseThrow();
        int succeeded = (int) items.stream().filter(BatchUploadView.ItemView::success).count();
        batch.setSucceeded(succeeded);
        batch.setFailed(items.size() - succeeded);
        batch.setStatus(ZhonghuiPmsUploadBatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.now());
        batch = batchRepository.save(batch);
        return new BatchUploadView(
                String.valueOf(batch.getId()), batch.getBatchNo(), batch.getStatus().name(),
                items.size(), succeeded, items.size() - succeeded, items);
    }

    private BatchUploadView.ItemView uploadOne(String skuId, Overrides overrides) {
        // 失败回写时优先保留已读到的真实编码/名称；读取失败前退化为 skuId 占位。
        String skuCode = skuId;
        String goodsName = skuId;
        try {
            Sku sku = skus.findById(parseId(skuId))
                    .orElseThrow(() -> BusinessException.notFound("SKU 不存在"));
            skuCode = sku.getSkuCode();
            goodsName = sku.getSkuCode();
            Product product = products.findById(sku.getProductId())
                    .orElseThrow(() -> BusinessException.unprocessable("PRODUCT_MISSING", "商品档案缺少对应商品"));
            if (!sku.isActive() || !product.isActive()) {
                throw BusinessException.unprocessable("INACTIVE_SKU", "商品未启用，跳过上传");
            }
            String mainImageUrl = uploadMainImage(product);
            GoodsCreateCommand command = buildCommand(sku, product, mainImageUrl, overrides);
            goodsName = command.goodsName();
            GoodsCreateResult result = client.createGoods(command);
            if (!result.success()) {
                return new BatchUploadView.ItemView(skuId, skuCode, goodsName,
                        false, result.businessCode(), result.message(), null, null,
                        mainImageUrl == null ? WARNING_NO_MAIN_IMAGE : null);
            }
            // 商品列表校验（best-effort）：确认创建并取回 goodsId / 审核状态；校验失败不翻转创建结果。
            GoodsVerifyView verify = verifyGoods(skuCode, goodsName);
            return new BatchUploadView.ItemView(skuId, skuCode, goodsName,
                    true, "OK", "",
                    verify == null ? null : verify.goodsId(),
                    verify == null ? null : joinStatus(verify),
                    mainImageUrl == null ? WARNING_NO_MAIN_IMAGE : null);
        } catch (BusinessException business) {
            return new BatchUploadView.ItemView(skuId, skuCode, goodsName, false,
                    business.getBusinessCode(), business.getMessage(), null, null, null);
        } catch (Exception exception) {
            return new BatchUploadView.ItemView(skuId, skuCode, goodsName, false,
                    "UPLOAD_FAILED", safeMessage(exception), null, null, null);
        }
    }

    private void updateItemRow(Long batchId, long skuId, BatchUploadView.ItemView outcome) {
        itemRepository.findByBatchIdAndSkuId(batchId, skuId).ifPresent(item -> {
            item.setSkuCode(outcome.skuCode());
            item.setGoodsName(outcome.goodsName());
            item.setStatus(outcome.success()
                    ? ZhonghuiPmsUploadBatchItemStatus.SUCCESS
                    : ZhonghuiPmsUploadBatchItemStatus.FAILED);
            item.setBusinessCode(outcome.businessCode());
            item.setMessage(outcome.message());
            item.setGoodsId(outcome.goodsId() == null ? null : Long.parseLong(outcome.goodsId()));
            item.setPmsStatus(outcome.pmsStatus());
            item.setWarning(outcome.warning());
            itemRepository.save(item);
        });
    }

    private GoodsVerifyView verifyGoods(String goodsItem, String goodsName) {
        try {
            return client.queryGoods(goodsItem, goodsName);
        } catch (RuntimeException exception) {
            // 校验失败保持创建成功的结论；创建调用本身已落 AuditLog。
            return null;
        }
    }

    private String joinStatus(GoodsVerifyView verify) {
        List<String> parts = new ArrayList<>();
        if (verify.goodsStaStr() != null && !verify.goodsStaStr().isBlank()) {
            parts.add(verify.goodsStaStr());
        }
        if (verify.goodSaleStaStr() != null && !verify.goodSaleStaStr().isBlank()) {
            parts.add(verify.goodSaleStaStr());
        }
        return String.join("/", parts);
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw BusinessException.badRequest("INVALID_SKU_ID", "SKU 标识非法");
        }
    }

    /** 商品主图 → 中汇公网 URL；无主图返回 null（不阻塞上传，但回写 warning）。 */
    private String uploadMainImage(Product product) {
        String ref = product.getMainImageRef();
        if (ref == null || ref.isBlank()) {
            return null;
        }
        byte[] bytes = productImageService.read(ref);
        return client.uploadImage(bytes, ProductImageService.contentType(ref));
    }

    private GoodsCreateCommand buildCommand(Sku sku, Product product, String mainImageUrl, Overrides overrides) {
        ZhonghuiPmsProperties.Defaults defaults = properties.getDefaults();
        BigDecimal goodsPrice = firstNonNull(
                overrides.goodsPrice(), sku.getRetailPrice(), product.getRetailPrice());
        BigDecimal supplyPrice = firstNonNull(
                overrides.supplyPrice(), sku.getPurchasePrice(), product.getPurchasePrice());
        if (goodsPrice == null) {
            throw BusinessException.unprocessable("PRICE_MISSING", "商品缺少售价（SKU/商品档案零售价均未填写）");
        }
        if (supplyPrice == null) {
            throw BusinessException.unprocessable("PRICE_MISSING", "商品缺少供货价（SKU/商品档案进货价均未填写）");
        }
        String specification = sku.getSpecification() == null ? "" : sku.getSpecification().strip();
        String goodsName = product.getProductName() == null ? "" : product.getProductName().strip();
        if (goodsName.isBlank()) {
            throw BusinessException.unprocessable("NAME_MISSING", "商品名称为空");
        }
        if (!specification.isBlank() && !goodsName.contains(specification)) {
            goodsName = goodsName + " " + specification;
        }
        String description = joinDescription(product.getDescription(), product.getIngredients());
        String details = mainImageUrl == null
                ? (description.isBlank() ? "" : "<p>" + escapeHtml(description) + "</p>")
                : "<p><img src=\"" + mainImageUrl + "\"></p>";
        String saleUnit = firstNonBlank(overrides.saleUnit(),
                blankToNull(sku.getUnit()), defaults.getSaleUnit());
        return new GoodsCreateCommand(
                goodsName,
                firstNonNull(overrides.thirdId(), defaults.getThirdId()),
                description,
                sku.getSkuCode(),
                firstNonNull(overrides.goodsTax(), defaults.getGoodsTax()),
                mainImageUrl == null ? "" : "1," + mainImageUrl,
                details,
                description,
                "[]",
                "0",
                List.of(),
                "1",
                firstNonNull(overrides.limitAreaTempId(), defaults.getLimitAreaTempId()),
                "",
                goodsPrice,
                null,
                firstNonNull(overrides.goodsNum(), defaults.getGoodsNum()),
                supplyPrice,
                sku.getBarcode() == null ? "" : sku.getBarcode(),
                saleUnit,
                specification,
                -1,
                1,
                firstNonNull(overrides.certificationType(), defaults.getCertificationType()),
                firstNonNull(overrides.certificationId(), defaults.getCertificationId()),
                "",
                firstNonNull(overrides.brandId(), defaults.getBrandId()),
                firstNonNull(overrides.logisticsCarrier(), defaults.getLogisticsCarrier()),
                "",
                firstNonNull(overrides.producingArea(), defaults.getProducingArea()),
                List.of(),
                firstNonNull(overrides.origincountry(), defaults.getOrigincountry()));
    }

    private String joinDescription(String description, String ingredients) {
        List<String> parts = new ArrayList<>();
        if (description != null && !description.isBlank()) {
            parts.add(description.strip());
        }
        if (ingredients != null && !ingredients.isBlank()) {
            parts.add("原料：" + ingredients.strip());
        }
        return String.join("；", parts);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String safeMessage(Exception exception) {
        return exception instanceof BusinessException business
                ? business.getMessage()
                : "上传失败，请稍后重试";
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static <T> T firstNonNull(T first, T second, T third) {
        if (first != null) {
            return first;
        }
        return second != null ? second : third;
    }

    private static String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 批量上传请求；overrides 为可选的每批覆盖字段（优先于配置默认值）。 */
    public record BatchUploadCommand(List<String> skuIds, Overrides overrides) {}

    public record Overrides(
            Integer brandId,
            Integer certificationType,
            Integer certificationId,
            Integer thirdId,
            Integer limitAreaTempId,
            BigDecimal goodsTax,
            String logisticsCarrier,
            String producingArea,
            Integer goodsNum,
            String saleUnit,
            Integer origincountry,
            BigDecimal goodsPrice,
            BigDecimal supplyPrice) {

        public static Overrides empty() {
            return new Overrides(null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** 批量上传结果（含批次标识，供后续按批次查询/恢复）；标识符按 §3.1 以十进制字符串传输。 */
    public record BatchUploadView(
            String batchId, String batchNo, String status,
            int total, int succeeded, int failed, List<ItemView> items) {

        public record ItemView(
                String skuId, String skuCode, String goodsName,
                boolean success, String businessCode, String message,
                String goodsId, String pmsStatus, String warning) {}
    }

    /** 批次详情（含创建人/时间与逐商品结果）。 */
    public record BatchDetailView(
            String batchId, String batchNo, String status,
            int total, int succeeded, int failed,
            String createdBy, Instant createdAt, Instant completedAt,
            List<BatchUploadView.ItemView> items) {}
}
