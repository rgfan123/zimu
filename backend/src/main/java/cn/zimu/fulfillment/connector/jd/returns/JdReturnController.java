package cn.zimu.fulfillment.connector.jd.returns;

import cn.zimu.fulfillment.connector.jd.JdPiiProjection;
import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 京东退货退供只读查询面。三个接口均为只读 GET：退货入库单列表 / 详情、退供单查询。
 * 退货单含寄件 / 收件人信息，返回前在 HTTP 边界统一脱敏。
 */
@RestController
@RequestMapping("/api/v1/jd-return")
public class JdReturnController {

    private final JDReturnService service;

    public JdReturnController(JDReturnService service) {
        this.service = service;
    }

    /** 退货入库单列表：条件查询，列表项凭 erp_return_to_warehouse_no 进入详情。 */
    @GetMapping("/rtw-orders")
    public JdResult rtwOrders(
            @RequestParam(name = "return_to_warehouse_no", required = false) String returnToWarehouseNo,
            @RequestParam(name = "erp_return_to_warehouse_no", required = false) String erpReturnToWarehouseNo,
            @RequestParam(name = "delivery_no", required = false) String deliveryNo,
            @RequestParam(name = "out_store_no", required = false) String outStoreNo,
            @RequestParam(name = "return_to_warehouse_details_flag", required = false) Integer returnToWarehouseDetailsFlag,
            @RequestParam(name = "return_to_warehouse_bat_attr_model_flag", required = false) Integer returnToWarehouseBatAttrModelFlag,
            @RequestParam(name = "serial_no_model_flag", required = false) Integer serialNoModelFlag) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "returnToWarehouseNo", returnToWarehouseNo);
        putIfPresent(request, "erpReturnToWarehouseNo", erpReturnToWarehouseNo);
        putIfPresent(request, "deliveryNo", deliveryNo);
        putIfPresent(request, "outStoreNo", outStoreNo);
        putIfPresent(request, "returnToWarehouseDetailsFlag", returnToWarehouseDetailsFlag);
        putIfPresent(request, "returnToWarehouseBatAttrModelFlag", returnToWarehouseBatAttrModelFlag);
        putIfPresent(request, "serialNoModelFlag", serialNoModelFlag);
        return JdPiiProjection.redactPersonalData(service.queryRtwOrderList(request));
    }

    /** 退货入库单详情：默认返回明细、批次属性与序列号。 */
    @GetMapping("/rtw-orders/{erp_return_to_warehouse_no}")
    public JdResult rtwOrderDetail(
            @PathVariable("erp_return_to_warehouse_no") String erpReturnToWarehouseNo,
            @RequestParam(name = "return_to_warehouse_details_flag", required = false) Integer returnToWarehouseDetailsFlag,
            @RequestParam(name = "return_to_warehouse_bat_attr_model_flag", required = false) Integer returnToWarehouseBatAttrModelFlag,
            @RequestParam(name = "serial_no_model_flag", required = false) Integer serialNoModelFlag) {
        return JdPiiProjection.redactPersonalData(service.queryRtwOrderDetail(Map.of(
                "erpReturnToWarehouseNo", erpReturnToWarehouseNo,
                "returnToWarehouseDetailsFlag", returnToWarehouseDetailsFlag == null ? 1 : returnToWarehouseDetailsFlag,
                "returnToWarehouseBatAttrModelFlag",
                        returnToWarehouseBatAttrModelFlag == null ? 1 : returnToWarehouseBatAttrModelFlag,
                "serialNoModelFlag", serialNoModelFlag == null ? 1 : serialNoModelFlag)));
    }

    /** 退供单查询：默认返回明细、批次与序列号。 */
    @GetMapping("/return-to-suppliers/{erp_return_to_supplier_no}")
    public JdResult returnToSupplier(
            @PathVariable("erp_return_to_supplier_no") String erpReturnToSupplierNo,
            @RequestParam(name = "return_to_supplier_detail_flag", required = false) Integer returnToSupplierDetailFlag,
            @RequestParam(name = "return_to_supplier_batch_flag", required = false) Integer returnToSupplierBatchFlag,
            @RequestParam(name = "serial_no_model_flag", required = false) Integer serialNoModelFlag) {
        return JdPiiProjection.redactPersonalData(service.queryReturnToSupplier(Map.of(
                "erpReturnToSupplierNo", erpReturnToSupplierNo,
                "returnToSupplierDetailFlag", returnToSupplierDetailFlag == null ? 1 : returnToSupplierDetailFlag,
                "returnToSupplierBatchFlag", returnToSupplierBatchFlag == null ? 1 : returnToSupplierBatchFlag,
                "serialNoModelFlag", serialNoModelFlag == null ? 1 : serialNoModelFlag)));
    }

    private void putIfPresent(Map<String, Object> request, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            request.put(key, value);
        }
    }



}
