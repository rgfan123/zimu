package cn.zimu.fulfillment.connector.zhonghui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsCreateResult;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.GoodsVerifyView;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductImageService;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** #116：真实 PostgreSQL 台账下验证稳定批次意图、逐项对账与过期租约的 fail-closed 语义。 */
@Testcontainers
@SpringBootTest(properties = {
    "app.zhonghui-pms.client-mode=MOCK",
    "app.zhonghui-pms.idempotency-lease=PT40M",
    "app.seed.sample-master-data-enabled=false",
    "spring.data.redis.repositories.enabled=false"
})
class ZhonghuiPmsBatchRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ZhonghuiPmsBatchUploadService service;
    @Autowired private ZhonghuiPmsUploadBatchRepository batches;
    @Autowired private ZhonghuiPmsUploadBatchItemRepository items;
    @Autowired private ZhonghuiPmsProperties properties;
    @Autowired private DataSource dataSource;

    @MockitoBean private ZhonghuiPmsService client;
    @MockitoBean private SkuRepository skus;
    @MockitoBean private ProductRepository products;
    @MockitoBean private ProductImageService productImageService;

    @BeforeEach
    void setUp() {
        when(client.authenticated()).thenReturn(true);
        properties.setClientMode("MOCK");
        properties.setWriteMode("OFF");
        properties.getDefaults().setBrandId(164343);
    }

    @Test
    void sameKeyReusesBatchSkipsSuccessAndReconcilesPendingWithoutCreatingAgain() {
        String key = "recovery-same-key-001";
        ZhonghuiPmsUploadBatch existing = pendingBatch(key, "PMS-RECOVERY-0001", 2);
        pendingItem(existing.getId(), 201L, ZhonghuiPmsUploadBatchItemStatus.SUCCESS,
                "SKU-DONE", "已上传商品", 560000L);
        pendingItem(existing.getId(), 202L, ZhonghuiPmsUploadBatchItemStatus.PENDING,
                null, null, null);
        Sku pendingSku = sku(202L, "SKU-PENDING", 102L, "500g");
        Product pendingProduct = product(102L, "待对账商品");
        when(skus.findById(202L)).thenReturn(java.util.Optional.of(pendingSku));
        when(products.findById(102L)).thenReturn(java.util.Optional.of(pendingProduct));
        when(client.queryGoods("SKU-PENDING", "待对账商品 500g"))
                .thenReturn(new GoodsVerifyView("560002", "待平台审核", "待上架"));

        BatchUploadView result = service.upload(
                new BatchUploadCommand(List.of("201", "202"), null), key).result();

        assertThat(result.batchId()).isEqualTo(existing.getId().toString());
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(batches.findByIdempotencyKey(key)).get()
                .extracting(ZhonghuiPmsUploadBatch::getId)
                .isEqualTo(existing.getId());
        verify(client, never()).createGoods(any());
        verify(client, times(1)).queryGoods("SKU-PENDING", "待对账商品 500g");
        verify(skus, never()).findById(201L);
    }

    @Test
    void unresolvedPendingIntentRequiresReconciliationAndNeverCreates() {
        String key = "recovery-pending-unknown-001";
        ZhonghuiPmsUploadBatch existing = pendingBatch(key, "PMS-RECOVERY-0002", 1);
        pendingItem(existing.getId(), 203L, ZhonghuiPmsUploadBatchItemStatus.PENDING,
                null, null, null);
        Sku pendingSku = sku(203L, "SKU-UNKNOWN", 103L, "1kg");
        Product pendingProduct = product(103L, "未知效果商品");
        when(skus.findById(203L)).thenReturn(java.util.Optional.of(pendingSku));
        when(products.findById(103L)).thenReturn(java.util.Optional.of(pendingProduct));
        when(client.queryGoods("SKU-UNKNOWN", "未知效果商品 1kg")).thenReturn(null);

        assertThatThrownBy(() -> service.upload(
                        new BatchUploadCommand(List.of("203"), null), key))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("RECONCILIATION_REQUIRED"));

        verify(client, never()).createGoods(any());
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class, "zhonghui-pms.batch-upload", key))
                .as("执行期对账未决必须允许 same-key 再次进入 query-only 对账")
                .isEqualTo("FAILED");
        assertThat(batches.findById(existing.getId()).orElseThrow().getStatus())
                .isEqualTo(ZhonghuiPmsUploadBatchStatus.PENDING);
    }

    @Test
    void uncertainCreateResponseRetriesTheSameBatchByQueryOnly() {
        String key = "create-transport-query-recovery-001";
        properties.setClientMode("REAL");
        properties.setWriteMode("ON");
        Sku sku = sku(207L, "SKU-TRANSPORT", 107L, "750g");
        Product product = product(107L, "建品响应丢失商品");
        when(skus.findById(207L)).thenReturn(java.util.Optional.of(sku));
        when(products.findById(107L)).thenReturn(java.util.Optional.of(product));
        when(client.createGoods(any()))
                .thenThrow(new ZhonghuiPmsHttpClient.PmsTransportException("response lost"));

        assertThatThrownBy(() -> service.upload(
                        new BatchUploadCommand(List.of("207"), null), key))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("RECONCILIATION_REQUIRED"));
        ZhonghuiPmsUploadBatch batch = batches.findByIdempotencyKey(key).orElseThrow();
        assertThat(items.findByBatchIdOrderById(batch.getId()))
                .singleElement()
                .extracting(ZhonghuiPmsUploadBatchItem::getStatus)
                .isEqualTo(ZhonghuiPmsUploadBatchItemStatus.PENDING);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class, "zhonghui-pms.batch-upload", key))
                .isEqualTo("FAILED");

        properties.setWriteMode("OFF");
        when(client.queryGoods("SKU-TRANSPORT", "建品响应丢失商品 750g"))
                .thenReturn(new GoodsVerifyView("560007", "待平台审核", "待上架"));
        BatchUploadView recovered = service.upload(
                new BatchUploadCommand(List.of("207"), null), key).result();

        assertThat(recovered.batchId()).isEqualTo(batch.getId().toString());
        assertThat(recovered.succeeded()).isEqualTo(1);
        verify(client, times(1)).createGoods(any());
        verify(client, times(1)).queryGoods("SKU-TRANSPORT", "建品响应丢失商品 750g");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class, "zhonghui-pms.batch-upload", key))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void successfulSameKeyReplayDoesNotRequireTheCurrentPmsSession() {
        String key = "replay-after-session-loss-001";
        properties.setClientMode("REAL");
        properties.setWriteMode("ON");
        Sku sku = sku(206L, "SKU-REPLAY", 106L, "500g");
        Product product = product(106L, "会话失效重放商品");
        when(skus.findById(206L)).thenReturn(java.util.Optional.of(sku));
        when(products.findById(106L)).thenReturn(java.util.Optional.of(product));
        when(client.createGoods(any())).thenReturn(new GoodsCreateResult(true, "OK", ""));

        BatchUploadView first = service.upload(
                new BatchUploadCommand(List.of("206"), null), key).result();
        properties.setWriteMode("OFF");
        when(client.authenticated()).thenReturn(false);

        var replay = service.upload(new BatchUploadCommand(List.of("206"), null), key);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replayedBody().path("batch_id").asText()).isEqualTo(first.batchId());
        verify(client, times(1)).createGoods(any());
    }

    @Test
    void databaseRejectsDuplicateSkuFactsWithinOneBatch() {
        ZhonghuiPmsUploadBatch batch = pendingBatch(
                "database-duplicate-sku-001", "PMS-DUPLICATE-0001", 1);
        pendingItem(batch.getId(), 208L, ZhonghuiPmsUploadBatchItemStatus.PENDING,
                "SKU-DUPLICATE", "重复商品", null);

        assertThatThrownBy(() -> pendingItem(
                        batch.getId(), 208L, ZhonghuiPmsUploadBatchItemStatus.PENDING,
                        "SKU-DUPLICATE", "重复商品", null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void longRunningBatchCannotBeTakenOverAfterItsLeaseExpires() throws Exception {
        String key = "long-running-expiry-001";
        Sku firstSku = sku(204L, "SKU-LONG-1", 104L, "2kg");
        Product firstProduct = product(104L, "长耗时商品一");
        Sku secondSku = sku(205L, "SKU-LONG-2", 105L, "3kg");
        Product secondProduct = product(105L, "长耗时商品二");
        when(skus.findById(204L)).thenReturn(java.util.Optional.of(firstSku));
        when(products.findById(104L)).thenReturn(java.util.Optional.of(firstProduct));
        when(skus.findById(205L)).thenReturn(java.util.Optional.of(secondSku));
        when(products.findById(105L)).thenReturn(java.util.Optional.of(secondProduct));
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        when(client.createGoods(any())).thenAnswer(invocation -> {
            writeStarted.countDown();
            if (!releaseWrite.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release external write");
            }
            return new GoodsCreateResult(true, "OK", "");
        });
        when(client.queryGoods("SKU-LONG-1", "长耗时商品一 2kg"))
                .thenReturn(new GoodsVerifyView("560004", "待平台审核", "待上架"));

        CompletableFuture<?> first = CompletableFuture.supplyAsync(() -> service.upload(
                new BatchUploadCommand(List.of("204", "205"), null), key));
        assertThat(writeStarted.await(10, TimeUnit.SECONDS)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Double remainingLeaseSeconds = jdbc.queryForObject(
                """
                SELECT EXTRACT(EPOCH FROM (lease_expires_at - statement_timestamp()))::double precision
                FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?
                """,
                Double.class, "zhonghui-pms.batch-upload", key);
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getIdempotencyLease())
                .as("20 SKU × 图片/建品/校验三次 HTTP 的最坏响应预算必须小于 scope 租约")
                .isGreaterThan(properties.getRequestTimeout().multipliedBy(3L * 20));
        assertThat(remainingLeaseSeconds)
                .as("中汇 scope 必须使用独立 PT40M 租约，而不是全局 60s")
                .isGreaterThan(39 * 60.0);
        jdbc.update(
                "UPDATE app.idempotency_registry "
                        + "SET lease_expires_at=statement_timestamp()-INTERVAL '1 second' "
                        + "WHERE scope=? AND idempotency_key=?",
                "zhonghui-pms.batch-upload", key);

        try {
            assertThatThrownBy(() -> service.upload(
                            new BatchUploadCommand(List.of("204", "205"), null), key))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.getBusinessCode()).isEqualTo("RECONCILIATION_REQUIRED"));
            verify(client, times(1)).createGoods(any());
            assertThat(batches.findByIdempotencyKey(key)).isPresent();
        } finally {
            releaseWrite.countDown();
        }
        assertThatThrownBy(first::join)
                .hasRootCauseInstanceOf(BusinessException.class)
                .rootCause()
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo("IDEMPOTENCY_CLAIM_LOST");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class, "zhonghui-pms.batch-upload", key))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(batches.findByIdempotencyKey(key)).hasValueSatisfying(batch ->
                assertThat(batch.getStatus()).isEqualTo(ZhonghuiPmsUploadBatchStatus.PENDING));
    }

    private ZhonghuiPmsUploadBatch pendingBatch(String key, String batchNo, int total) {
        ZhonghuiPmsUploadBatch batch = new ZhonghuiPmsUploadBatch();
        batch.setIdempotencyKey(key);
        batch.setBatchNo(batchNo);
        batch.setStatus(ZhonghuiPmsUploadBatchStatus.PENDING);
        batch.setTotal(total);
        batch.setCreatedBy("recovery-test");
        return batches.saveAndFlush(batch);
    }

    private void pendingItem(
            long batchId,
            long skuId,
            ZhonghuiPmsUploadBatchItemStatus status,
            String skuCode,
            String goodsName,
            Long goodsId) {
        ZhonghuiPmsUploadBatchItem item = new ZhonghuiPmsUploadBatchItem();
        item.setBatchId(batchId);
        item.setSkuId(skuId);
        item.setStatus(status);
        item.setSkuCode(skuCode);
        item.setGoodsName(goodsName);
        item.setGoodsId(goodsId);
        item.setBusinessCode(status == ZhonghuiPmsUploadBatchItemStatus.SUCCESS ? "OK" : null);
        item.setMessage(status == ZhonghuiPmsUploadBatchItemStatus.SUCCESS ? "" : null);
        items.saveAndFlush(item);
    }

    private Sku sku(long id, String skuCode, long productId, String specification) {
        Sku sku = mock(Sku.class);
        when(sku.getId()).thenReturn(id);
        when(sku.getSkuCode()).thenReturn(skuCode);
        when(sku.getProductId()).thenReturn(productId);
        when(sku.getSpecification()).thenReturn(specification);
        when(sku.getUnit()).thenReturn("件");
        when(sku.getPurchasePrice()).thenReturn(new BigDecimal("80"));
        when(sku.getRetailPrice()).thenReturn(new BigDecimal("432"));
        when(sku.isActive()).thenReturn(true);
        return sku;
    }

    private Product product(long id, String name) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        when(product.getProductName()).thenReturn(name);
        when(product.isActive()).thenReturn(true);
        when(product.getDescription()).thenReturn("");
        return product;
    }
}
