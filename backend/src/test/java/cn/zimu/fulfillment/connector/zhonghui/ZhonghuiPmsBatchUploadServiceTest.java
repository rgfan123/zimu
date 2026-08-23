package cn.zimu.fulfillment.connector.zhonghui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.Overrides;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateResult;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsVerifyView;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductImageService;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;

/** 从商品档案批量上传到中汇 PMS：字段映射、逐商品收口、幂等意图先落库与回写。 */
class ZhonghuiPmsBatchUploadServiceTest {

    private final AtomicLong itemIds = new AtomicLong();
    private final java.util.Map<Long, ZhonghuiPmsUploadBatchItem> itemStore = new java.util.LinkedHashMap<>();
    private ZhonghuiPmsUploadBatchRepository batches;
    private ZhonghuiPmsUploadBatchItemRepository items;
    private IdempotencyService idempotency;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext("req-test", "trace-test", "operator-test", "operator-test"));
        itemIds.set(0);
        itemStore.clear();
        batches = batchRepository();
        items = itemRepository();
        idempotency = idempotencyService();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void batchUploadMapsArchiveFieldsAndSucceedsPerSku() {
        Sku sku = sku(true, "500g/盒", "盒", "6901234567890", new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.createGoods(any())).thenAnswer(invocation -> {
            ZhonghuiPmsUploadBatchItem intent = itemStore.values().iterator().next();
            assertThat(intent.getStatus()).isEqualTo(ZhonghuiPmsUploadBatchItemStatus.PENDING);
            assertThat(intent.getSkuCode()).isEqualTo("SKU-TP-000001");
            assertThat(intent.getGoodsName()).isEqualTo("子牧羊小腿 500g/盒");
            return new GoodsCreateResult(true, "OK", "");
        });
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, mock(ProductImageService.class));

        BatchUploadView result = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-00000001").result();

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.batchId()).isEqualTo("1");
        assertThat(result.batchNo()).isEqualTo("PMS-00000001");
        assertThat(result.status()).isEqualTo("COMPLETED");
        BatchUploadView.ItemView item = result.items().getFirst();
        assertThat(item.success()).isTrue();
        assertThat(item.skuCode()).isEqualTo("SKU-TP-000001");

        ArgumentCaptor<GoodsCreateCommand> captor = ArgumentCaptor.forClass(GoodsCreateCommand.class);
        verify(client).createGoods(captor.capture());
        GoodsCreateCommand command = captor.getValue();
        assertThat(command.goodsName()).isEqualTo("子牧羊小腿 500g/盒");
        assertThat(command.goodsItem()).isEqualTo("SKU-TP-000001");
        assertThat(command.goodsBar()).isEqualTo("6901234567890");
        assertThat(command.goodsPrice()).isEqualByComparingTo("432");
        assertThat(command.supplyPrice()).isEqualByComparingTo("80");
        assertThat(command.saleUnit()).isEqualTo("盒");
        assertThat(command.specsName()).isEqualTo("500g/盒");
        // 配置默认值注入
        assertThat(command.brandId()).isEqualTo(164343);
        assertThat(command.certificationType()).isEqualTo(2);
        assertThat(command.certificationId()).isEqualTo(56118);
        assertThat(command.thirdId()).isEqualTo(3407);
        assertThat(command.limitAreaTempId()).isEqualTo(2075);
        assertThat(command.goodsTax()).isEqualByComparingTo("9");
        assertThat(command.logisticsCarrier()).isEqualTo("1,20");
        assertThat(command.producingArea()).isEqualTo("新疆");
        // 可暂时作为默认值的字段
        assertThat(command.jdParam()).isEqualTo("[]");
        assertThat(command.attrFlag()).isEqualTo("0");
        assertThat(command.banSaleFlag()).isEqualTo("1");
        assertThat(command.noReasonReturnDay()).isEqualTo(-1);
        assertThat(command.goodsPurchaseMultiplier()).isEqualTo(1);
        assertThat(command.origincountry()).isEqualTo(1);
        assertThat(command.specialisedIds()).isEmpty();
    }

    @Test
    void batchIntentIsPersistedBeforeExternalCreateAndItemsAreWrittenBack() {
        Sku sku = sku(true, "500g/盒", "盒", null, new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.createGoods(any())).thenReturn(new GoodsCreateResult(true, "OK", ""));
        when(client.queryGoods(any(), any())).thenReturn(new GoodsVerifyView("560001", "待平台审核", "待上架"));
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, mock(ProductImageService.class));

        BatchUploadView result = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-00000002").result();

        // §3.5：批次意图（saveAndFlush）必须先于外部创建调用
        InOrder order = inOrder(batches, client);
        order.verify(batches, org.mockito.Mockito.atLeastOnce()).saveAndFlush(any(ZhonghuiPmsUploadBatch.class));
        order.verify(client).createGoods(any());

        // 商品列表校验结果回写：goodsId（十进制字符串）+ 审核/上架状态；无主图时回写 warning
        ZhonghuiPmsUploadBatchItem persisted = itemStore.values().iterator().next();
        assertThat(persisted.getStatus()).isEqualTo(ZhonghuiPmsUploadBatchItemStatus.SUCCESS);
        assertThat(persisted.getGoodsId()).isEqualTo(560001L);
        assertThat(persisted.getPmsStatus()).isEqualTo("待平台审核/待上架");
        assertThat(persisted.getWarning()).contains("缺少主图");
        assertThat(persisted.getSkuCode()).isEqualTo("SKU-TP-000001");
        assertThat(persisted.getBatchId()).isEqualTo(1L);
        assertThat(result.items().getFirst().goodsId()).isEqualTo("560001");
    }

    @Test
    void overridesTakePrecedenceOverConfigDefaults() {
        Sku sku = sku(true, "500g/盒", "盒", null, new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.createGoods(any())).thenReturn(new GoodsCreateResult(true, "OK", ""));
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, mock(ProductImageService.class));

        Overrides overrides = new Overrides(
                999, 3, 777, 888, 9999, new BigDecimal("13"),
                "20", "宁夏", 50, "箱", 2, new BigDecimal("500"), new BigDecimal("100"));
        service.upload(new BatchUploadCommand(List.of(String.valueOf(sku.getId())), overrides), "idem-00000003");

        ArgumentCaptor<GoodsCreateCommand> captor = ArgumentCaptor.forClass(GoodsCreateCommand.class);
        verify(client).createGoods(captor.capture());
        GoodsCreateCommand command = captor.getValue();
        assertThat(command.brandId()).isEqualTo(999);
        assertThat(command.certificationType()).isEqualTo(3);
        assertThat(command.certificationId()).isEqualTo(777);
        assertThat(command.thirdId()).isEqualTo(888);
        assertThat(command.limitAreaTempId()).isEqualTo(9999);
        assertThat(command.goodsTax()).isEqualByComparingTo("13");
        assertThat(command.logisticsCarrier()).isEqualTo("20");
        assertThat(command.producingArea()).isEqualTo("宁夏");
        assertThat(command.goodsNum()).isEqualTo(50);
        assertThat(command.saleUnit()).isEqualTo("箱");
        assertThat(command.origincountry()).isEqualTo(2);
        assertThat(command.goodsPrice()).isEqualByComparingTo("500");
        assertThat(command.supplyPrice()).isEqualByComparingTo("100");
    }

    @Test
    void mainImageIsUploadedAndReferencedInPhotoStrAndDetails() {
        Sku sku = sku(true, "500g/盒", "盒", null, new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, "product-images/abc.png", "产地新疆");
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.uploadImage(any(byte[].class), any()))
                .thenReturn("https://img.zhonghuihaotai.com/file_main.jpeg");
        when(client.createGoods(any())).thenReturn(new GoodsCreateResult(true, "OK", ""));
        ProductImageService imageService = mock(ProductImageService.class);
        when(imageService.read("product-images/abc.png")).thenReturn(new byte[] {1, 2, 3});
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, imageService);

        BatchUploadView result = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-00000004").result();

        ArgumentCaptor<GoodsCreateCommand> captor = ArgumentCaptor.forClass(GoodsCreateCommand.class);
        verify(client).createGoods(captor.capture());
        GoodsCreateCommand command = captor.getValue();
        assertThat(command.photoStr()).isEqualTo("1,https://img.zhonghuihaotai.com/file_main.jpeg");
        assertThat(command.details()).isEqualTo("<p><img src=\"https://img.zhonghuihaotai.com/file_main.jpeg\"></p>");
        // 有主图时不产生 warning
        assertThat(result.items().getFirst().warning()).isNull();
    }

    @Test
    void uncertainCreateTransportResultRequiresReconciliationAndLeavesIntentPending() {
        Sku sku = sku(true, "500g/盒", "盒", null, new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.createGoods(any()))
                .thenThrow(new ZhonghuiPmsHttpClient.PmsTransportException("response lost"));
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, mock(ProductImageService.class));

        assertThatThrownBy(() -> service.upload(
                        new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null),
                        "idem-create-transport-unknown-001"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getBusinessCode()).isEqualTo("RECONCILIATION_REQUIRED"));

        assertThat(itemStore.values()).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(ZhonghuiPmsUploadBatchItemStatus.PENDING);
            assertThat(item.getSkuCode()).isEqualTo("SKU-TP-000001");
            assertThat(item.getGoodsName()).isEqualTo("子牧羊小腿 500g/盒");
        });
    }

    @Test
    void missingRetailPriceFailsItemWithoutAbortingBatchAndKeepsRealSkuCode() {
        Sku sku = sku(true, "500g/盒", "盒", null, new BigDecimal("80"), null);
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, mock(ProductImageService.class));

        BatchUploadView result = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-00000005").result();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.succeeded()).isZero();
        assertThat(result.items().getFirst().businessCode()).isEqualTo("PRICE_MISSING");
        // 失败占位保留真实 SKU 编码（不再用 skuId 顶替）
        assertThat(result.items().getFirst().skuCode()).isEqualTo("SKU-TP-000001");
        assertThat(itemStore.values().iterator().next().getStatus())
                .isEqualTo(ZhonghuiPmsUploadBatchItemStatus.FAILED);
        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    @Test
    void inactiveSkuIsReportedAsFailure() {
        Sku sku = sku(false, "500g/盒", "盒", null, new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, mock(ProductImageService.class));

        BatchUploadView result = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-00000006").result();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.items().getFirst().businessCode()).isEqualTo("INACTIVE_SKU");
    }

    @Test
    void missingSkuIsReportedAsFailureItem() {
        SkuRepository skus = mock(SkuRepository.class);
        when(skus.findById(404L)).thenReturn(Optional.empty());
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        ZhonghuiPmsBatchUploadService service = new ZhonghuiPmsBatchUploadService(
                client, properties(), skus, mock(ProductRepository.class), mock(ProductImageService.class),
                batches, items, idempotency);

        BatchUploadView result = service.upload(new BatchUploadCommand(List.of("404"), null), "idem-00000007").result();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.items().getFirst().message()).contains("SKU 不存在");
    }

    @Test
    void invalidSkuIdRejectsWholeBatchBeforePersisting() {
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        ZhonghuiPmsBatchUploadService service = new ZhonghuiPmsBatchUploadService(
                client, properties(), mock(SkuRepository.class), mock(ProductRepository.class),
                mock(ProductImageService.class), batches, items, idempotency);

        assertThatThrownBy(() -> service.upload(
                new BatchUploadCommand(List.of("not-a-number"), null), "idem-00000008"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SKU 标识非法");
        assertThat(itemStore).isEmpty();
    }

    @Test
    void missingLoginRejectsWholeBatch() {
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(false);
        ZhonghuiPmsBatchUploadService service = new ZhonghuiPmsBatchUploadService(
                client, properties(), mock(SkuRepository.class), mock(ProductRepository.class),
                mock(ProductImageService.class), batches, items, idempotency);

        assertThatThrownBy(() -> service.upload(
                new BatchUploadCommand(List.of("1"), null), "idem-00000009"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PMS 登录");
    }

    @Test
    void realWriteModeOffStillRejectsANewBatchIntent() {
        ZhonghuiPmsProperties properties = properties();
        properties.setClientMode("REAL");
        properties.setWriteMode("OFF");
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        ZhonghuiPmsBatchUploadService service = new ZhonghuiPmsBatchUploadService(
                client, properties, mock(SkuRepository.class), mock(ProductRepository.class),
                mock(ProductImageService.class), batches, items, idempotency);

        assertThatThrownBy(() -> service.upload(
                        new BatchUploadCommand(List.of("201"), null), "idem-new-write-off-001"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(403);
                    assertThat(exception.getBusinessCode()).isEqualTo("ZHONGHUI_PMS_WRITE_MODE_DISABLED");
                });
    }

    @Test
    void emptySkuSelectionIsRejected() {
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        ZhonghuiPmsBatchUploadService service = new ZhonghuiPmsBatchUploadService(
                client, properties(), mock(SkuRepository.class), mock(ProductRepository.class),
                mock(ProductImageService.class), batches, items, idempotency);

        assertThatThrownBy(() -> service.upload(new BatchUploadCommand(List.of(), null), "idem-00000010"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少选择一个");
    }

    @Test
    void duplicateSkuSelectionIsRejectedBeforePersistingAnIntent() {
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        ZhonghuiPmsBatchUploadService service = new ZhonghuiPmsBatchUploadService(
                client, properties(), mock(SkuRepository.class), mock(ProductRepository.class),
                mock(ProductImageService.class), batches, items, idempotency);

        assertThatThrownBy(() -> service.upload(
                        new BatchUploadCommand(List.of("201", "0201"), null), "idem-duplicate-sku-001"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(400);
                    assertThat(exception.getBusinessCode()).isEqualTo("DUPLICATE_SKU_IDS");
                });
        assertThat(itemStore).isEmpty();
    }

    @Test
    void batchDetailReturnsPersistedRows() {
        Sku sku = sku(true, "500g/盒", "盒", null, new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.createGoods(any())).thenReturn(new GoodsCreateResult(true, "OK", ""));
        when(client.queryGoods(any(), any())).thenReturn(new GoodsVerifyView("560001", "待平台审核", "待上架"));
        ZhonghuiPmsBatchUploadService service = service(client, sku, product, mock(ProductImageService.class));
        BatchUploadView uploaded = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-00000011").result();

        var detail = service.batch(Long.parseLong(uploaded.batchId()));

        assertThat(detail.batchNo()).isEqualTo("PMS-00000001");
        assertThat(detail.status()).isEqualTo("COMPLETED");
        assertThat(detail.createdBy()).isEqualTo("operator-test");
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().getFirst().goodsId()).isEqualTo("560001");
        assertThat(detail.items().getFirst().pmsStatus()).isEqualTo("待平台审核/待上架");
    }

    @Test
    void idempotentReplayReturnsFirstResultWithoutRepeatingExternalCreate() {
        Sku sku = sku(true, "500g/盒", "盒", null, new BigDecimal("80"), new BigDecimal("432"));
        Product product = product(sku.getProductId(), "子牧羊小腿", true, null, null);
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.createGoods(any())).thenReturn(new GoodsCreateResult(true, "OK", ""));
        ZhonghuiPmsProperties properties = properties();
        properties.setClientMode("REAL");
        properties.setWriteMode("ON");
        ZhonghuiPmsBatchUploadService service = service(
                client, properties, sku, product, mock(ProductImageService.class));

        BatchUploadView first = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-same-key-01").result();
        properties.setWriteMode("OFF");
        when(client.authenticated()).thenReturn(false);
        // 幂等注册表命中重放：返回首次结果，不重新执行 intent/external/completion
        // （用 doReturn 避免 when() 参数求值触发既有 stub）
        org.mockito.Mockito.doReturn(IdempotentResult.replayed(
                        200, JsonNodeFactory.instance.objectNode().put("batch_id", "1")))
                .when(idempotency).executeWithExternalWriteIntent(
                        anyString(), anyString(), any(), anyInt(), any(), any(), any(), any());

        IdempotentResult<BatchUploadView> replay = service.upload(
                new BatchUploadCommand(List.of(String.valueOf(sku.getId())), null), "idem-same-key-01");

        assertThat(replay.replayed()).isTrue();
        assertThat(first.batchId()).isEqualTo("1");
        verify(client, org.mockito.Mockito.times(1)).createGoods(any());
        verify(client, org.mockito.Mockito.times(1)).authenticated();
    }

    @Test
    void writeModeOffAllowsExistingPendingIntentToUseQueryOnlyRecovery() {
        String key = "idem-query-only-write-off-001";
        ZhonghuiPmsProperties properties = properties();
        properties.setClientMode("REAL");
        properties.setWriteMode("OFF");
        ZhonghuiPmsService client = mock(ZhonghuiPmsService.class);
        when(client.authenticated()).thenReturn(true);
        when(client.queryGoods("SKU-TP-000001", "子牧羊小腿 500g/盒"))
                .thenReturn(new GoodsVerifyView("560001", "待平台审核", "待上架"));
        ZhonghuiPmsUploadBatch existing = new ZhonghuiPmsUploadBatch();
        setId(existing, 1L);
        existing.setIdempotencyKey(key);
        when(batches.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));
        ZhonghuiPmsUploadBatchItem pending = new ZhonghuiPmsUploadBatchItem();
        pending.setBatchId(1L);
        pending.setSkuId(201L);
        pending.setSkuCode("SKU-TP-000001");
        pending.setGoodsName("子牧羊小腿 500g/盒");
        pending.setStatus(ZhonghuiPmsUploadBatchItemStatus.PENDING);
        items.save(pending);
        ZhonghuiPmsBatchUploadService service = new ZhonghuiPmsBatchUploadService(
                client, properties, mock(SkuRepository.class), mock(ProductRepository.class),
                mock(ProductImageService.class), batches, items, idempotency);

        BatchUploadView result = service.upload(
                new BatchUploadCommand(List.of("201"), null), key).result();

        assertThat(result.succeeded()).isEqualTo(1);
        verify(client, org.mockito.Mockito.never()).createGoods(any());
        verify(client).queryGoods("SKU-TP-000001", "子牧羊小腿 500g/盒");
    }

    private ZhonghuiPmsBatchUploadService service(
            ZhonghuiPmsService client, Sku sku, Product product, ProductImageService imageService) {
        return service(client, properties(), sku, product, imageService);
    }

    private ZhonghuiPmsBatchUploadService service(
            ZhonghuiPmsService client,
            ZhonghuiPmsProperties properties,
            Sku sku,
            Product product,
            ProductImageService imageService) {
        SkuRepository skus = mock(SkuRepository.class);
        when(skus.findById(sku.getId())).thenReturn(Optional.of(sku));
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        return new ZhonghuiPmsBatchUploadService(
                client, properties, skus, products, imageService, batches, items, idempotency);
    }

    private ZhonghuiPmsProperties properties() {
        ZhonghuiPmsProperties properties = new ZhonghuiPmsProperties();
        properties.setClientMode("MOCK");
        ZhonghuiPmsProperties.Defaults defaults = properties.getDefaults();
        defaults.setBrandId(164343);
        defaults.setCertificationType(2);
        defaults.setCertificationId(56118);
        defaults.setThirdId(3407);
        defaults.setLimitAreaTempId(2075);
        defaults.setGoodsTax(new BigDecimal("9"));
        defaults.setLogisticsCarrier("1,20");
        defaults.setProducingArea("新疆");
        defaults.setGoodsNum(99);
        defaults.setSaleUnit("件");
        defaults.setOrigincountry(1);
        return properties;
    }

    private ZhonghuiPmsUploadBatchRepository batchRepository() {
        ZhonghuiPmsUploadBatchRepository batches = mock(ZhonghuiPmsUploadBatchRepository.class);
        when(batches.nextBatchNo()).thenReturn(1L);
        when(batches.save(any(ZhonghuiPmsUploadBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batches.saveAndFlush(any(ZhonghuiPmsUploadBatch.class))).thenAnswer(invocation -> {
            ZhonghuiPmsUploadBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                setId(batch, 1L);
            }
            return batch;
        });
        when(batches.findById(anyLong())).thenAnswer(invocation -> {
            ZhonghuiPmsUploadBatch batch = new ZhonghuiPmsUploadBatch();
            setId(batch, invocation.getArgument(0));
            batch.setBatchNo("PMS-00000001");
            batch.setStatus(ZhonghuiPmsUploadBatchStatus.COMPLETED);
            batch.setCreatedBy("operator-test");
            return Optional.of(batch);
        });
        return batches;
    }

    private ZhonghuiPmsUploadBatchItemRepository itemRepository() {
        ZhonghuiPmsUploadBatchItemRepository items = mock(ZhonghuiPmsUploadBatchItemRepository.class);
        when(items.save(any(ZhonghuiPmsUploadBatchItem.class))).thenAnswer(invocation -> {
            ZhonghuiPmsUploadBatchItem item = invocation.getArgument(0);
            if (item.getId() == null) {
                setId(item, itemIds.incrementAndGet());
            }
            itemStore.put(item.getId(), item);
            return item;
        });
        when(items.findById(anyLong())).thenAnswer(invocation ->
                Optional.of(itemStore.get(invocation.getArgument(0))));
        when(items.findByBatchIdOrderById(anyLong())).thenAnswer(invocation ->
                itemStore.values().stream().filter(item ->
                        item.getBatchId().equals(invocation.getArgument(0))).toList());
        when(items.findByBatchIdAndSkuId(anyLong(), anyLong())).thenAnswer(invocation ->
                itemStore.values().stream().filter(item ->
                        item.getBatchId().equals(invocation.getArgument(0))
                                && item.getSkuId().equals(invocation.getArgument(1)))
                        .findFirst());
        return items;
    }

    /** 幂等注册表 mock：按 intent → external → completion 顺序真实执行（重放语义由注册表自身保证）。 */
    @SuppressWarnings("unchecked")
    private IdempotencyService idempotencyService() {
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.executeWithExternalWriteIntent(
                anyString(), anyString(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(this::runExternalWriteFlow);
        return idempotency;
    }

    @SuppressWarnings("unchecked")
    private IdempotentResult<BatchUploadView> runExternalWriteFlow(InvocationOnMock invocation) {
        IdempotencyService.ExternalWriteIntent<Object> intentWork = invocation.getArgument(5);
        IdempotencyService.GuardedExternalWrite<Object, List<BatchUploadView.ItemView>> external =
                invocation.getArgument(6);
        IdempotencyService.ExternalWriteCompletion<Object, List<BatchUploadView.ItemView>, BatchUploadView>
                completion = invocation.getArgument(7);
        Object intent = intentWork.persist();
        List<BatchUploadView.ItemView> externalResult = external.execute(intent, () -> {});
        var outcome = completion.execute(intent, externalResult);
        return IdempotentResult.executed(outcome.result(), 200);
    }

    /** JPA 实体 id 由数据库生成、无 setter；单元测试通过反射注入。 */
    private static void setId(Object entity, long id) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Sku sku(boolean active, String specification, String unit,
            String barcode, BigDecimal purchasePrice, BigDecimal retailPrice) {
        Sku sku = mock(Sku.class);
        when(sku.getId()).thenReturn(201L);
        when(sku.getSkuCode()).thenReturn("SKU-TP-000001");
        when(sku.getProductId()).thenReturn(101L);
        when(sku.getSpecification()).thenReturn(specification);
        when(sku.getUnit()).thenReturn(unit);
        when(sku.getBarcode()).thenReturn(barcode);
        when(sku.getPurchasePrice()).thenReturn(purchasePrice);
        when(sku.getRetailPrice()).thenReturn(retailPrice);
        when(sku.isActive()).thenReturn(active);
        return sku;
    }

    private static Product product(long id, String name, boolean active, String mainImageRef, String description) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        when(product.getProductName()).thenReturn(name);
        when(product.isActive()).thenReturn(active);
        when(product.getMainImageRef()).thenReturn(mainImageRef);
        when(product.getDescription()).thenReturn(description);
        when(product.getIngredients()).thenReturn(null);
        when(product.getRetailPrice()).thenReturn(null);
        when(product.getPurchasePrice()).thenReturn(null);
        return product;
    }
}
