package cn.zimu.fulfillment.connector.jd.write;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 京东 ISC 写作业面（创建/取消/修改/关闭/设置/绑定类）。
 *
 * <p>默认锁死：{@code app.jd.write-mode} 未配置或为 OFF 时，所有写端点返回
 * HTTP 403 + 业务码 {@code WRITE_MODE_DISABLED} + 中文消息「写模式未启用」，
 * 不触达 seam、不产生任何外部调用；仅显式配置为 ON 时才放行到受审计的
 * {@link JdWriteOpsService}。被拦截的写尝试同样落审计（操作人、请求摘要、结果）。
 * {@code order/so-create} 是例外：即使写模式开启，通用入口也始终拒绝，必须通过受授权、幂等与库存门禁保护的
 * Shipment 业务入口。前端只为该 Shipment 入口提供业务操作面。
 * 其余通用写 HTTP 面还需独立 {@code app.jd.generic-http-write-mode=ON}，并且只允许本地
 * Mock 验证；REAL 模式始终拒绝，真实写操作必须由逐业务审批的纵切入口承接。
 */
@RestController
@RequestMapping("/api/v1/jd-write")
public class JdWriteOpsController {

    private static final String BLOCKED_CODE = "WRITE_MODE_DISABLED";
    private static final String BLOCKED_MESSAGE = "写模式未启用";
    private static final String SO_CREATE_WORKFLOW_CODE = "JD_SO_CREATE_REQUIRES_SHIPMENT_WORKFLOW";
    private static final String SO_CREATE_WORKFLOW_MESSAGE =
            "京东出库建单必须通过 Shipment 业务流程执行授权、幂等与实时库存门禁";
    private static final String GENERIC_REAL_WRITE_BLOCKED_CODE =
            "JD_GENERIC_REAL_WRITE_REQUIRES_APPROVED_WORKFLOW";
    private static final String GENERIC_REAL_WRITE_BLOCKED_MESSAGE =
            "真实京东写操作必须通过具备授权、幂等与恢复策略的业务流程执行";

    private final JdWriteOpsService service;
    private final AuditLogService auditLogService;
    private final String writeMode;
    private final String genericHttpWriteMode;
    private final String clientMode;

    public JdWriteOpsController(
            JdWriteOpsService service,
            AuditLogService auditLogService,
            @Value("${app.jd.write-mode:OFF}") String writeMode,
            @Value("${app.jd.generic-http-write-mode:OFF}") String genericHttpWriteMode,
            @Value("${app.jd.client-mode:MOCK}") String clientMode) {
        this.service = service;
        this.auditLogService = auditLogService;
        this.writeMode = writeMode;
        this.genericHttpWriteMode = genericHttpWriteMode;
        this.clientMode = clientMode;
    }

    @PostMapping("/basicinfo/customer-create")
    public ResponseEntity<JdResult> customerCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("customerCreate", command, () -> service.customerCreate(command));
    }

    @PostMapping("/basicinfo/goods-create")
    public ResponseEntity<JdResult> goodsCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("goodsCreate", command, () -> service.goodsCreate(command));
    }

    @PostMapping("/basicinfo/goods-update-by-seller-goods-sign")
    public ResponseEntity<JdResult> goodsUpdateBySellerGoodsSign(@RequestBody(required = false) Map<String, Object> command) {
        return write("goodsUpdateBySellerGoodsSign", command, () -> service.goodsUpdateBySellerGoodsSign(command));
    }

    @PostMapping("/basicinfo/supplier-create")
    public ResponseEntity<JdResult> supplierCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("supplierCreate", command, () -> service.supplierCreate(command));
    }

    @PostMapping("/basicinfo/shop-create")
    public ResponseEntity<JdResult> shopCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("shopCreate", command, () -> service.shopCreate(command));
    }

    @PostMapping("/basicinfo/shop-goods-create")
    public ResponseEntity<JdResult> shopGoodsCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("shopGoodsCreate", command, () -> service.shopGoodsCreate(command));
    }

    @PostMapping("/basicinfo/serialnumber-create")
    public ResponseEntity<JdResult> serialnumberCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("serialnumberCreate", command, () -> service.serialnumberCreate(command));
    }

    @PostMapping("/basicinfo/processed-create")
    public ResponseEntity<JdResult> processedCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("processedCreate", command, () -> service.processedCreate(command));
    }

    @PostMapping("/basicinfo/logicalinventoryfactor-create")
    public ResponseEntity<JdResult> logicalinventoryfactorCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("logicalinventoryfactorCreate", command, () -> service.logicalinventoryfactorCreate(command));
    }

    @PostMapping("/basicinfo/boxandserialnumber-transport")
    public ResponseEntity<JdResult> boxandserialnumberTransport(@RequestBody(required = false) Map<String, Object> command) {
        return write("boxandserialnumberTransport", command, () -> service.boxandserialnumberTransport(command));
    }

    @PostMapping("/order/adjustment-create")
    public ResponseEntity<JdResult> orderAdjustmentCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderAdjustmentCreate", command, () -> service.orderAdjustmentCreate(command));
    }

    @PostMapping("/order/destroy-create")
    public ResponseEntity<JdResult> orderDestroyCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderDestroyCreate", command, () -> service.orderDestroyCreate(command));
    }

    @PostMapping("/order/operate-command-modify")
    public ResponseEntity<JdResult> orderOperateCommandModify(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderOperateCommandModify", command, () -> service.orderOperateCommandModify(command));
    }

    @PostMapping("/order/processed-create")
    public ResponseEntity<JdResult> orderProcessedCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderProcessedCreate", command, () -> service.orderProcessedCreate(command));
    }

    @PostMapping("/order/purchase-create")
    public ResponseEntity<JdResult> orderPurchaseCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderPurchaseCreate", command, () -> service.orderPurchaseCreate(command));
    }

    @PostMapping("/order/purchase-close")
    public ResponseEntity<JdResult> orderPurchaseClose(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderPurchaseClose", command, () -> service.orderPurchaseClose(command));
    }

    @PostMapping("/order/returntosupplier-create")
    public ResponseEntity<JdResult> orderReturntosupplierCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderReturntosupplierCreate", command, () -> service.orderReturntosupplierCreate(command));
    }

    @PostMapping("/order/returntowarehouse-create")
    public ResponseEntity<JdResult> orderReturntowarehouseCreate(@RequestBody(required = false) Map<String, Object> command) {
        return write("orderReturntowarehouseCreate", command, () -> service.orderReturntowarehouseCreate(command));
    }

    @PostMapping("/order/so-create")
    public ResponseEntity<JdResult> orderSoCreate(@RequestBody(required = false) Map<String, Object> command) {
        JdResult blocked = new JdResult(
                false,
                writeEnabled() ? SO_CREATE_WORKFLOW_CODE : BLOCKED_CODE,
                writeEnabled() ? SO_CREATE_WORKFLOW_MESSAGE : BLOCKED_MESSAGE,
                null,
                null);
        auditBlocked("orderSoCreate", command, blocked);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(blocked);
    }

    @PostMapping("/stock/shopstockfixed-set")
    public ResponseEntity<JdResult> stockShopstockfixedSet(@RequestBody(required = false) Map<String, Object> command) {
        return write("stockShopstockfixedSet", command, () -> service.stockShopstockfixedSet(command));
    }

    private ResponseEntity<JdResult> write(
            String operation, Map<String, Object> command, Supplier<JdResult> invocation) {
        if (!writeEnabled() || !genericHttpWriteEnabled()) {
            JdResult blocked = new JdResult(false, BLOCKED_CODE, BLOCKED_MESSAGE, null, null);
            auditBlocked(operation, command, blocked);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(blocked);
        }
        if (realClientMode()) {
            JdResult blocked = new JdResult(
                    false, GENERIC_REAL_WRITE_BLOCKED_CODE, GENERIC_REAL_WRITE_BLOCKED_MESSAGE, null, null);
            auditBlocked(operation, command, blocked);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(blocked);
        }
        return ResponseEntity.ok(invocation.get());
    }

    private boolean writeEnabled() {
        return "ON".equalsIgnoreCase(writeMode == null ? "" : writeMode.trim());
    }

    private boolean genericHttpWriteEnabled() {
        return "ON".equalsIgnoreCase(genericHttpWriteMode == null ? "" : genericHttpWriteMode.trim());
    }

    private boolean realClientMode() {
        return "REAL".equalsIgnoreCase(clientMode == null ? "" : clientMode.trim());
    }

    private void auditBlocked(String operation, Map<String, Object> command, JdResult result) {
        RequestContext context = RequestContext.current();
        auditLogService.record(new AuditLogService.AuditCommand()
                .requestId(context == null ? result.requestId() : context.getRequestId())
                .traceId(context == null ? null : context.getTraceId())
                .operator(context == null || context.getAuthenticatedOperator() == null
                        ? "unauthenticated" : context.getAuthenticatedOperator())
                .actorType(AuditActorType.SYSTEM)
                .service("jd.isc")
                .operation(operation)
                .requestPayload(command == null ? Map.of() : command)
                .responsePayload(result)
                .httpStatus(HttpStatus.FORBIDDEN.value())
                .businessCode(result.businessCode())
                .latencyMs(0));
    }
}
