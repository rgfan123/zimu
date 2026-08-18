package cn.zimu.fulfillment.order;

import java.util.Map;

/**
 * 创建运营告警的命令。主体四选一（orderId/orderLineId/fulfillmentId/shipmentId）至少一个非空，
 * 与 app.operational_alerts 的 CHECK 约束一致，由 OperationalAlertService.create 校验。
 */
public record CreateOperationalAlertCommand(
        String alertType,
        OperationalAlertSeverity severity,
        Long orderId,
        Long orderLineId,
        Long fulfillmentId,
        Long shipmentId,
        String message,
        Map<String, Object> detail) {}
