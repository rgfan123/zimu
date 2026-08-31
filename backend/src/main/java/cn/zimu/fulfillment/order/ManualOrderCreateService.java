package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.customer.CustomerSourceRef;
import cn.zimu.fulfillment.customer.CustomerSourceRefRepository;
import cn.zimu.fulfillment.customer.CustomerStatus;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 手工建单（V100 MANUAL 渠道）：把柜台输入适配成标准订单输入，走与所有渠道
 * 完全相同的 {@link OrderCreateService#create}——不复制建单逻辑，只做输入适配。
 *
 * <p>关键裁定：
 * <ul>
 *   <li>客户绑定走 customer_source_refs(MANUAL, customer_code) 幂等落一条引用，
 *       使 create() 的既有绑定判据（渠道+来源客户标识精确命中）自然成立，
 *       建成即带 customer_id、状态 SKU_MAPPED——可直接 fulfillment-routing 生成发货单；</li>
 *   <li>商品按系统 SKU 直选（skuCode 直传），不经来源映射，天然全映射；</li>
 *   <li>结账事实按 UNSPECIFIED+空时间（柜台没有渠道结算事实，V100 已在
 *       orders_settlement_consistency 中与万齐同款豁免）；</li>
 *   <li>来源单号服务端生成（MAN-&lt;幂等键摘要&gt;）：手工单没有外部单号，
 *       让运营编号只会造出撞号与脏数据。</li>
 * </ul>
 */
@Service
public class ManualOrderCreateService {

    private final OrderCreateService orderCreateService;
    private final CustomerRepository customerRepository;
    private final CustomerSourceRefRepository customerSourceRefRepository;
    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;

    public ManualOrderCreateService(
            OrderCreateService orderCreateService,
            CustomerRepository customerRepository,
            CustomerSourceRefRepository customerSourceRefRepository,
            SkuRepository skuRepository,
            ProductRepository productRepository) {
        this.orderCreateService = orderCreateService;
        this.customerRepository = customerRepository;
        this.customerSourceRefRepository = customerSourceRefRepository;
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public IdempotentResult<OrderDetailDto> create(
            ManualOrderCreateWrite write, String idempotencyKey, CommandContext context) {
        Customer customer = requireActiveCustomer(write.customerCode().trim());
        ensureManualCustomerRef(customer);
        List<OrderItemInput> items = new ArrayList<>(write.items().size());
        List<ConfirmedDraftSku> confirmedSkus = new ArrayList<>(write.items().size());
        int lineNo = 1;
        for (ManualOrderCreateWrite.ManualOrderItem item : write.items()) {
            Sku sku = requireActiveSku(item);
            String lineRef = "L" + lineNo;
            items.add(toItemInput(item, sku, lineRef));
            // 与企微草稿确认同一接缝：SKU 由服务端冻结快照，不经来源映射词表。
            confirmedSkus.add(new ConfirmedDraftSku(lineNo, lineRef, sku.getId(), sku.getSkuCode()));
            lineNo++;
        }
        CanonicalOrderInput input = new CanonicalOrderInput(
                SourceChannel.MANUAL,
                sourceRefFor(idempotencyKey),
                null,
                new CustomerInput(
                        customer.getCustomerCode(),
                        customer.getCustomerCode(),
                        customer.getCustomerName()),
                new Receiver(
                        write.receiver().name().trim(),
                        write.receiver().phone().trim(),
                        null,
                        null,
                        null,
                        null,
                        write.receiver().address().trim()),
                items,
                Settlement.unspecifiedSourceFact(),
                // 手工单没有「渠道平台下单时刻」这个事实，如实为 null（created_at 记录真实录入时刻）；
                // 这同时保证幂等载荷确定性——掺 now() 会让同键重放变 409。
                null,
                write.remark(),
                null);
        return orderCreateService.createConfirmedDraft(input, confirmedSkus, idempotencyKey, context);
    }

    private Customer requireActiveCustomer(String customerCode) {
        return customerRepository.findByCustomerCode(customerCode)
                .filter(customer -> customer.getDataScope() == DataScope.BUSINESS
                        && customer.getStatus() == CustomerStatus.ACTIVE)
                .orElseThrow(() -> BusinessException.unprocessable(
                        "MANUAL_ORDER_CUSTOMER_NOT_FOUND",
                        "客户编码未命中启用中的业务客户档案: " + customerCode));
    }

    /**
     * 幂等落 MANUAL 渠道客户引用：不存在则建；存在但指向其他客户按数据异常拒绝
     * ——customer_code 是客户唯一编码，引用漂移只可能来自档案被改写，宁停不猜。
     */
    private void ensureManualCustomerRef(Customer customer) {
        CustomerSourceRef existing = customerSourceRefRepository
                .findBySourceChannelAndSourceCustomerRef(
                        SourceChannel.MANUAL, customer.getCustomerCode())
                .orElse(null);
        if (existing == null) {
            CustomerSourceRef ref = new CustomerSourceRef();
            ref.setCustomerId(customer.getId());
            ref.setSourceChannel(SourceChannel.MANUAL);
            ref.setSourceCustomerRef(customer.getCustomerCode());
            customerSourceRefRepository.save(ref);
            return;
        }
        if (!existing.getCustomerId().equals(customer.getId())) {
            throw BusinessException.conflict(
                    "MANUAL_ORDER_CUSTOMER_REF_CONFLICT",
                    "客户编码的手工渠道引用指向了其他客户档案，请先在客户主数据核对");
        }
    }

    private Sku requireActiveSku(ManualOrderCreateWrite.ManualOrderItem item) {
        long skuId = Long.parseLong(item.skuId());
        return skuRepository.findById(skuId)
                .filter(Sku::isActive)
                .orElseThrow(() -> BusinessException.unprocessable(
                        "MANUAL_ORDER_SKU_NOT_FOUND", "SKU 不存在或已停用: " + item.skuId()));
    }

    private OrderItemInput toItemInput(
            ManualOrderCreateWrite.ManualOrderItem item, Sku sku, String lineRef) {
        Product product = productRepository.findById(sku.getProductId())
                .orElseThrow(() -> BusinessException.unprocessable(
                        "MANUAL_ORDER_SKU_NOT_FOUND", "SKU 缺少商品归属: " + sku.getSkuCode()));
        return new OrderItemInput(
                lineRef,
                cn.zimu.fulfillment.order.domain.LineType.SINGLE,
                sku.getSkuCode(),
                null,
                product.getProductName(),
                sku.getSpecification(),
                sku.getUnit(),
                item.quantity(),
                List.of());
    }

    /**
     * 来源单号 = 幂等键的确定性投影（MAN-<sha256 前 12 位>）：同键重放产生逐字节相同的
     * 载荷（幂等重放才成立），不同键天然不同号；随机号或掺时间都会让重放变 409。
     */
    private static String sourceRefFor(String idempotencyKey) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02X", digest[i]));
            }
            return "MAN-" + hex;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
