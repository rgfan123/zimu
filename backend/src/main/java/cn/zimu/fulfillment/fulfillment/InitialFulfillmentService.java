package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始 Fulfillment 创建服务：订单创建/修订时按已映射订单行 1:1 创建履约单元。
 * 后续履约票在本服务基础上扩展库存判断、Shipment 与采购流转。
 */
@Service
public class InitialFulfillmentService {

    private final FulfillmentRepository fulfillmentRepository;

    public InitialFulfillmentService(FulfillmentRepository fulfillmentRepository) {
        this.fulfillmentRepository = fulfillmentRepository;
    }

    @Transactional
    public Fulfillment create(Order order, OrderLine line) {
        if (line.getFulfillmentProviderId() == null) {
            throw new IllegalStateException("initial fulfillment requires a mapped order line");
        }
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.setFulfillmentNo("FL-" + order.getOrderNo() + "-" + line.getLineNo());
        fulfillment.setOrderLineId(line.getId());
        fulfillment.setFulfillmentProviderId(line.getFulfillmentProviderId());
        fulfillment.setRequestedQuantity(line.getRequestedQuantity());
        fulfillment.setCumulativeShippedQuantity(0);
        fulfillment.setCancelledQuantity(0);
        fulfillment.setShippingProgress(ShippingProgress.NOT_SHIPPED);
        fulfillment.setOutcome(FulfillmentOutcome.IN_PROGRESS);
        return fulfillmentRepository.save(fulfillment);
    }
}
