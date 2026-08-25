package cn.zimu.fulfillment.connector.jd.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class JdWriteOpsGateTest {

    private static final Map<String, Object> SAMPLE_BODY = Map.of("erpDeliveryNo", "ZM20260813001");

    private record Endpoint(String operation, Function<JdWriteOpsController, ResponseEntity<JdResult>> invocation) {}

    private static final List<Endpoint> ALL_ENDPOINTS = List.of(
            new Endpoint("customerCreate", c -> c.customerCreate(SAMPLE_BODY)),
            new Endpoint("goodsCreate", c -> c.goodsCreate(SAMPLE_BODY)),
            new Endpoint("goodsUpdateBySellerGoodsSign", c -> c.goodsUpdateBySellerGoodsSign(SAMPLE_BODY)),
            new Endpoint("supplierCreate", c -> c.supplierCreate(SAMPLE_BODY)),
            new Endpoint("shopCreate", c -> c.shopCreate(SAMPLE_BODY)),
            new Endpoint("shopGoodsCreate", c -> c.shopGoodsCreate(SAMPLE_BODY)),
            new Endpoint("serialnumberCreate", c -> c.serialnumberCreate(SAMPLE_BODY)),
            new Endpoint("processedCreate", c -> c.processedCreate(SAMPLE_BODY)),
            new Endpoint("logicalinventoryfactorCreate", c -> c.logicalinventoryfactorCreate(SAMPLE_BODY)),
            new Endpoint("boxandserialnumberTransport", c -> c.boxandserialnumberTransport(SAMPLE_BODY)),
            new Endpoint("orderAdjustmentCreate", c -> c.orderAdjustmentCreate(SAMPLE_BODY)),
            new Endpoint("orderDestroyCreate", c -> c.orderDestroyCreate(SAMPLE_BODY)),
            new Endpoint("orderOperateCommandModify", c -> c.orderOperateCommandModify(SAMPLE_BODY)),
            new Endpoint("orderProcessedCreate", c -> c.orderProcessedCreate(SAMPLE_BODY)),
            new Endpoint("orderPurchaseCreate", c -> c.orderPurchaseCreate(SAMPLE_BODY)),
            new Endpoint("orderPurchaseClose", c -> c.orderPurchaseClose(SAMPLE_BODY)),
            new Endpoint("orderReturntosupplierCreate", c -> c.orderReturntosupplierCreate(SAMPLE_BODY)),
            new Endpoint("orderReturntowarehouseCreate", c -> c.orderReturntowarehouseCreate(SAMPLE_BODY)),
            new Endpoint("orderSoCreate", c -> c.orderSoCreate(SAMPLE_BODY)),
            new Endpoint("stockShopstockfixedSet", c -> c.stockShopstockfixedSet(SAMPLE_BODY)));

    @Test
    void writeModeOffRejectsEveryWriteEndpointWith403WithoutReachingTheSeam() {
        JdWriteOpsService service = mock(JdWriteOpsService.class);
        AuditLogRepository repository = auditRepository();
        JdWriteOpsController controller = new JdWriteOpsController(
                service, new AuditLogService(repository, new ObjectMapper(), mock(EntityManager.class)),
                "OFF", "OFF", "MOCK");

        for (Endpoint endpoint : ALL_ENDPOINTS) {
            ResponseEntity<JdResult> response = endpoint.invocation().apply(controller);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().businessCode()).isEqualTo("WRITE_MODE_DISABLED");
            assertThat(response.getBody().message()).isEqualTo("写模式未启用");
            assertThat(response.getBody().requestId()).isNull();
        }

        verifyNoInteractions(service);

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, times(ALL_ENDPOINTS.size())).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).allSatisfy(audit -> {
            assertThat(audit.getService()).isEqualTo("jd.isc");
            assertThat(audit.getHttpStatus()).isEqualTo(403);
            assertThat(audit.getBusinessCode()).isEqualTo("WRITE_MODE_DISABLED");
            assertThat(audit.getOperator()).isEqualTo("unauthenticated");
            assertThat(audit.getOperation()).isIn(ALL_ENDPOINTS.stream().map(Endpoint::operation).toList());
        });
    }

    @Test
    void writeModeOnAllowsMockClientFullChainAndAuditsTheWriteOperation() {
        AuditLogRepository repository = auditRepository();
        AuditLogService auditLogService =
                new AuditLogService(repository, new ObjectMapper(), mock(EntityManager.class));
        JdWriteOpsController controller = new JdWriteOpsController(
                new MockJdWriteOpsClient(auditLogService, "on"), auditLogService,
                "on", "on", "MOCK");

        ResponseEntity<JdResult> response =
                controller.orderPurchaseCreate(Map.of("erpPurchaseNo", "PO-20260813-001"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().businessCode()).isEqualTo("MOCK_SUCCESS");
        assertThat(response.getBody().requestId()).isEqualTo("mock-orderPurchaseCreate");
        assertThat(((Map<?, ?>) response.getBody().data()).get("operation")).isEqualTo("orderPurchaseCreate");

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(auditCaptor.capture());
        AuditLog audit = auditCaptor.getValue();
        assertThat(audit.getService()).isEqualTo("jd.isc");
        assertThat(audit.getOperation()).isEqualTo("orderPurchaseCreate");
        assertThat(audit.getHttpStatus()).isEqualTo(200);
        assertThat(audit.getBusinessCode()).isEqualTo("MOCK_SUCCESS");
        assertThat(audit.getRequestId()).isEqualTo("mock-orderPurchaseCreate");
        assertThat(audit.getRequestPayload()).containsEntry("erpPurchaseNo", "PO-20260813-001");
    }

    @Test
    void shipmentWriteGateDoesNotOpenTheGenericJdWriteSurface() {
        JdWriteOpsService service = mock(JdWriteOpsService.class);
        JdWriteOpsController controller = new JdWriteOpsController(
                service, auditLogService(), "ON", "OFF", "MOCK");

        ResponseEntity<JdResult> response = controller.orderPurchaseCreate(SAMPLE_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().businessCode()).isEqualTo("WRITE_MODE_DISABLED");
        verifyNoInteractions(service);
    }

    @Test
    void realClientModeNeverOpensTheGenericHttpWriteSurface() {
        JdWriteOpsService service = mock(JdWriteOpsService.class);
        JdWriteOpsController controller = new JdWriteOpsController(
                service, auditLogService(), "ON", "ON", "REAL");

        ResponseEntity<JdResult> response = controller.orderPurchaseCreate(SAMPLE_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().businessCode()).isEqualTo("JD_GENERIC_REAL_WRITE_REQUIRES_APPROVED_WORKFLOW");
        verifyNoInteractions(service);
    }

    @Test
    void writeModeOnStillRejectsGenericSoCreateInFavorOfShipmentWorkflow() {
        JdWriteOpsService service = mock(JdWriteOpsService.class);
        AuditLogRepository repository = auditRepository();
        JdWriteOpsController controller = new JdWriteOpsController(
                service, new AuditLogService(repository, new ObjectMapper(), mock(EntityManager.class)),
                "ON", "ON", "MOCK");

        ResponseEntity<JdResult> response = controller.orderSoCreate(SAMPLE_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().businessCode()).isEqualTo("JD_SO_CREATE_REQUIRES_SHIPMENT_WORKFLOW");
        verifyNoInteractions(service);
        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOperation()).isEqualTo("orderSoCreate");
        assertThat(auditCaptor.getValue().getBusinessCode())
                .isEqualTo("JD_SO_CREATE_REQUIRES_SHIPMENT_WORKFLOW");
    }

    @Test
    void missingWriteModeConfigDefaultsToLocked() {
        JdWriteOpsService service = mock(JdWriteOpsService.class);
        JdWriteOpsController controller = new JdWriteOpsController(
                service, auditLogService(), null, null, null);

        ResponseEntity<JdResult> response = controller.orderPurchaseCreate(SAMPLE_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().businessCode()).isEqualTo("WRITE_MODE_DISABLED");
        verifyNoInteractions(service);
    }

    @Test
    void mockClientWriteModeOffRejectsDirectSeamCallWithoutController() {
        AuditLogRepository repository = auditRepository();
        MockJdWriteOpsClient client =
                new MockJdWriteOpsClient(new AuditLogService(repository, new ObjectMapper(), mock(EntityManager.class)), "OFF");

        JdResult result = client.customerCreate(Map.of("customerName", "某客户"));

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("WRITE_MODE_DISABLED");
        assertThat(result.message()).isEqualTo("写模式未启用");
        assertThat(result.data()).isNull();
        verify(repository).save(any());
    }

    @Test
    void realClientWriteModeOffRejectsWithoutTouchingNetwork() throws Exception {
        AuditLogRepository repository = auditRepository();
        ObjectMapper contractMapper = new ObjectMapper();
        JdWriteOpsClient client = new JdWriteOpsClient(
                contractMapper,
                new AuditLogService(repository, new ObjectMapper(), mock(EntityManager.class)),
                "https://api.jdl.com",
                "app-key",
                "app-secret",
                "access-token",
                "merchant-pin",
                "",
                "OFF");

        JdResult result = client.customerCreate(Map.of("customerName", "某客户"));

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("WRITE_MODE_DISABLED");
        assertThat(result.message()).isEqualTo("写模式未启用");
        assertThat(result.data()).isNull();
        verify(repository).save(any());
    }

    private AuditLogRepository auditRepository() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    private AuditLogService auditLogService() {
        return new AuditLogService(auditRepository(), new ObjectMapper(), mock(EntityManager.class));
    }
}
