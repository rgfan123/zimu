package cn.zimu.fulfillment.connector.jd.order;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdPiiProjection;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 京东 ISC 订单查询域只读作业面：出库单号分页、调整单、销毁单、异常单、采购单、
 * 加工单、作业关联、配送时效与同城轨迹。全部为 GET 查询，不触发任何写操作。
 */
@RestController
@RequestMapping("/api/v1/jd-order")
public class JdOrderController {

    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int EXPORT_PAGE_SIZE = 1000;
    /** antd 常见的空格分隔时间格式（yyyy-MM-dd HH:mm:ss），SDK 的 Date 字段只认 ISO-8601。 */
    private static final Pattern SPACE_SEPARATED_DATETIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");
    /** ISO-8601 带时分秒（yyyy-MM-ddTHH:mm:ss），SDK 原生格式，原样透传。 */
    private static final Pattern ISO_DATETIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$");
    /** 纯日期（yyyy-MM-dd），SDK 可解析，原样透传。 */
    private static final Pattern DATE_ONLY =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final JdOrderService service;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public JdOrderController(
            JdOrderService service, AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.service = service;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/outbound-order-nos")
    public JdResult outboundOrderNos(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "start_finish_date", required = false) String startFinishDate,
            @RequestParam(name = "end_finish_date", required = false) String endFinishDate,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "order_type", required = false) String orderType,
            @RequestParam(name = "shop_no", required = false) String shopNo,
            @RequestParam(name = "current_page", required = false) String currentPage,
            @RequestParam(name = "page_size", required = false) String pageSize) {
        return JdPiiProjection.redactPersonalData(service.queryOrderNosByPage(
                orderNosCommand(startDate, endDate, startFinishDate, endFinishDate,
                        status, orderType, shopNo, currentPage, pageSize)));
    }

    @GetMapping("/outbound-order-nos/export")
    public ResponseEntity<byte[]> exportOutboundOrderNos(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "start_finish_date", required = false) String startFinishDate,
            @RequestParam(name = "end_finish_date", required = false) String endFinishDate,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "order_type", required = false) String orderType,
            @RequestParam(name = "shop_no", required = false) String shopNo) {
        Map<String, Object> command = orderNosCommand(
                startDate, endDate, startFinishDate, endFinishDate,
                status, orderType, shopNo, null, String.valueOf(EXPORT_PAGE_SIZE));
        JdResult result = service.queryOrderNosByPage(command);
        if (!result.success()) {
            auditExport(command, result, 502, 0, null);
            return errorResponse(result);
        }
        List<Map<String, Object>> rows = extractOrderNosRows(result.data());
        String fileName = "jd-outbound-order-nos-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".xlsx";
        byte[] workbook = orderNosWorkbook(rows);
        auditExport(command, result, 200, rows.size(), fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + fileName)
                .body(workbook);
    }

    @GetMapping("/adjustments")
    public JdResult adjustments(
            @RequestParam(name = "adjustment_no", required = false) String adjustmentNo,
            @RequestParam(name = "erp_adjustment_no", required = false) String erpAdjustmentNo,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "biz_type", required = false) String bizType) {
        JdResult invalidParam = invalidTimeParam(startTime);
        if (invalidParam != null) {
            return invalidParam;
        }
        invalidParam = invalidTimeParam(endTime);
        if (invalidParam != null) {
            return invalidParam;
        }
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "adjustmentNo", adjustmentNo);
        put(command, "erpAdjustmentNo", erpAdjustmentNo);
        put(command, "startTime", normalizeTime(startTime));
        put(command, "endTime", normalizeTime(endTime));
        putInt(command, "status", status);
        putInt(command, "bizType", bizType);
        return JdPiiProjection.redactPersonalData(service.queryAdjustment(command));
    }

    @GetMapping("/destroy-orders")
    public JdResult destroyOrders(
            @RequestParam(name = "destroy_no", required = false) String destroyNo,
            @RequestParam(name = "erp_destroy_no", required = false) String erpDestroyNo,
            @RequestParam(name = "destroy_item_list_flag", required = false) String destroyItemListFlag,
            @RequestParam(name = "destroy_batch_item_list_flag", required = false) String destroyBatchItemListFlag,
            @RequestParam(name = "return_destroy_data_flag", required = false) String returnDestroyDataFlag) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "destroyNo", destroyNo);
        put(command, "erpDestroyNo", erpDestroyNo);
        putInt(command, "destroyItemListFlag", destroyItemListFlag);
        putInt(command, "destroyBatchItemListFlag", destroyBatchItemListFlag);
        putInt(command, "returnDestroyDataFlag", returnDestroyDataFlag);
        return JdPiiProjection.redactPersonalData(service.queryDestroy(command));
    }

    @GetMapping("/exceptions")
    public JdResult exceptions(
            @RequestParam(name = "order_type", required = false) String orderType,
            @RequestParam(name = "biz_type", required = false) String bizType,
            @RequestParam(name = "erp_order_no", required = false) String erpOrderNo,
            @RequestParam(name = "order_no", required = false) String orderNo,
            @RequestParam(name = "exception_code", required = false) String exceptionCode,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "current_page", required = false) String currentPage,
            @RequestParam(name = "page_size", required = false) String pageSize) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "orderType", orderType);
        put(command, "bizType", bizType);
        putList(command, "erpOrderNoList", erpOrderNo);
        putList(command, "orderNoList", orderNo);
        put(command, "exceptionCode", exceptionCode);
        put(command, "startDate", startDate);
        put(command, "endDate", endDate);
        putInt(command, "currentPage", currentPage);
        putInt(command, "pageSize", pageSize);
        return JdPiiProjection.redactPersonalData(service.queryException(command));
    }

    @GetMapping("/purchase-orders")
    public JdResult purchaseOrders(
            @RequestParam(name = "purchase_no", required = false) String purchaseNo,
            @RequestParam(name = "erp_purchase_no", required = false) String erpPurchaseNo,
            @RequestParam(name = "batch_purchase_no", required = false) String batchPurchaseNo,
            @RequestParam(name = "purchase_item_flag", required = false) String purchaseItemFlag,
            @RequestParam(name = "quality_inspection_item_flag", required = false) String qualityInspectionItemFlag,
            @RequestParam(name = "quality_inspection_err_item_flag", required = false) String qualityInspectionErrItemFlag,
            @RequestParam(name = "purchase_bat_attr_flag", required = false) String purchaseBatAttrFlag,
            @RequestParam(name = "purchase_item_reject_flag", required = false) String purchaseItemRejectFlag,
            @RequestParam(name = "serial_no_model_flag", required = false) String serialNoModelFlag,
            @RequestParam(name = "purchase_book_flag", required = false) String purchaseBookFlag) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "purchaseNo", purchaseNo);
        put(command, "erpPurchaseNo", erpPurchaseNo);
        put(command, "batchPurchaseNo", batchPurchaseNo);
        putInt(command, "purchaseItemFlag", purchaseItemFlag);
        putInt(command, "qualityInspectionItemFlag", qualityInspectionItemFlag);
        putInt(command, "qualityInspectionErrItemFlag", qualityInspectionErrItemFlag);
        putInt(command, "purchaseBatAttrFlag", purchaseBatAttrFlag);
        putInt(command, "purchaseItemRejectFlag", purchaseItemRejectFlag);
        putInt(command, "serialNoModelFlag", serialNoModelFlag);
        putInt(command, "purchaseBookFlag", purchaseBookFlag);
        return JdPiiProjection.redactPersonalData(service.queryPurchase(command));
    }

    @GetMapping("/processed-orders")
    public JdResult processedOrders(
            @RequestParam(name = "processed_no", required = false) String processedNo,
            @RequestParam(name = "erp_processed_no", required = false) String erpProcessedNo) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "processedNo", processedNo);
        put(command, "erpProcessedNo", erpProcessedNo);
        return JdPiiProjection.redactPersonalData(service.queryProcessed(command));
    }

    @GetMapping("/operate-relations")
    public JdResult operateRelations(
            @RequestParam(name = "erp_order_no", required = false) String erpOrderNo,
            @RequestParam(name = "order_type", required = false) String orderType) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "erpOrderNo", erpOrderNo);
        put(command, "orderType", orderType);
        return JdPiiProjection.redactPersonalData(service.queryOperateRelation(command));
    }

    @GetMapping("/delivery-times")
    public JdResult deliveryTimes(
            @RequestParam(name = "waybill_no", required = false) String waybillNo,
            @RequestParam(name = "customer_code", required = false) String customerCode,
            @RequestParam(name = "shunt", required = false) String shunt,
            @RequestParam(name = "dynamic_time_flag", required = false) String dynamicTimeFlag) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "waybillNo", waybillNo);
        put(command, "customerCode", customerCode);
        put(command, "shunt", shunt);
        put(command, "dynamicTimeFlag", dynamicTimeFlag);
        return JdPiiProjection.redactPersonalData(service.queryDeliveryTime(command));
    }

    @GetMapping("/city-tracks")
    public JdResult cityTracks(
            @RequestParam(name = "delivery_no", required = false) String deliveryNo,
            @RequestParam(name = "customer_code", required = false) String customerCode) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "deliveryNo", deliveryNo);
        put(command, "customerCode", customerCode);
        return JdPiiProjection.redactPersonalData(service.queryCityTrack(command));
    }

    private Map<String, Object> orderNosCommand(
            String startDate, String endDate, String startFinishDate, String endFinishDate,
            String status, String orderType, String shopNo, String currentPage, String pageSize) {
        Map<String, Object> command = new LinkedHashMap<>();
        put(command, "startDate", startDate);
        put(command, "endDate", endDate);
        put(command, "startFinishDate", startFinishDate);
        put(command, "endFinishDate", endFinishDate);
        put(command, "status", status);
        put(command, "orderType", orderType);
        put(command, "shopNo", shopNo);
        putInt(command, "currentPage", currentPage);
        putInt(command, "pageSize", pageSize);
        return command;
    }

    private void put(Map<String, Object> command, String camelKey, String value) {
        if (value != null && !value.isBlank()) {
            command.put(camelKey, value);
        }
    }

    private void putInt(Map<String, Object> command, String camelKey, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            command.put(camelKey, Integer.valueOf(value));
        } catch (NumberFormatException ignored) {
            // 非法数值参数不参与请求，避免 SDK 转换失败
        }
    }

    /** 单值转单元素 List：SDK 契约（如 ExceptionOrderQueryRequest）把单号定义为 List 字段，单字符串会被 convertValue 静默丢弃。 */
    private void putList(Map<String, Object> command, String camelKey, String value) {
        if (value != null && !value.isBlank()) {
            command.put(camelKey, List.of(value));
        }
    }

    /** QueryAdjustmentRequest 的 startTime/endTime 是 java.util.Date，Jackson 默认只解析 ISO-8601；空格分隔格式规范化为 T 分隔。 */
    private String normalizeTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return SPACE_SEPARATED_DATETIME.matcher(value).matches() ? value.replace(' ', 'T') : value;
    }

    /** 非法时间参数直接返回参数错误，避免透传到 SDK 后 Date 转换失败变成 502 语义。 */
    private JdResult invalidTimeParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        boolean valid = ISO_DATETIME.matcher(value).matches()
                || SPACE_SEPARATED_DATETIME.matcher(value).matches()
                || DATE_ONLY.matcher(value).matches();
        return valid
                ? null
                : new JdResult(false, "INVALID_PARAM", "时间格式需为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss", null, null);
    }

    /** 从分页信封中提取白名单行；兼容真实 LOP（resultList）与 Mock（result_list 包裹在 response 下）。 */
    private List<Map<String, Object>> extractOrderNosRows(Object data) {
        if (data instanceof Map<?, ?> map) {
            List<Map<String, Object>> rows = resultList(map);
            if (!rows.isEmpty()) {
                return rows;
            }
            Object nested = map.get("response");
            if (nested instanceof Map<?, ?> response) {
                return resultList(response);
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> resultList(Map<?, ?> map) {
        Object list = map.containsKey("resultList") ? map.get("resultList") : map.get("result_list");
        if (list instanceof List<?> rows) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object item : rows) {
                if (item instanceof Map<?, ?> row) {
                    Map<String, Object> safe = new LinkedHashMap<>();
                    row.forEach((key, value) -> safe.put(String.valueOf(key), value));
                    result.add(safe);
                }
            }
            return result;
        }
        return List.of();
    }

    private byte[] orderNosWorkbook(List<Map<String, Object>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("出库单号列表");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("京东单号");
            header.createCell(1).setCellValue("ERP单号");
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                var xlsxRow = sheet.createRow(rowIndex++);
                xlsxRow.createCell(0).setCellValue(cellText(row.get("orderNo"), row.get("order_no")));
                xlsxRow.createCell(1).setCellValue(cellText(row.get("erpOrderNo"), row.get("erp_order_no")));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成出库单号导出文件", exception);
        }
    }

    private String cellText(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null && !candidate.toString().isBlank()) {
                return candidate.toString();
            }
        }
        return "";
    }

    private ResponseEntity<byte[]> errorResponse(JdResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("business_code", result.businessCode());
            if (result.message() != null) {
                body.put("message", result.message());
            }
            body.put("http_status", 502);
            byte[] payload = objectMapper.writeValueAsBytes(body);
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload);
        } catch (IOException exception) {
            return ResponseEntity.status(502).build();
        }
    }

    private void auditExport(Map<String, Object> command, JdResult result, int httpStatus, int rowCount, String fileName) {
        RequestContext context = RequestContext.current();
        auditLogService.record(new AuditLogService.AuditCommand()
                .requestId(context == null ? result.requestId() : context.getRequestId())
                .traceId(context == null ? null : context.getTraceId())
                .operator(context == null || context.getOperator() == null ? "jd-client" : context.getOperator())
                .actorType(AuditActorType.SYSTEM)
                .service("jd.isc")
                .operation("exportOutboundOrderNos")
                .requestPayload(command)
                .responsePayload(Map.of(
                        "row_count", rowCount,
                        "file_name", fileName == null ? "" : fileName,
                        "business_code", result.businessCode() == null ? "" : result.businessCode()))
                .httpStatus(httpStatus)
                .businessCode(result.businessCode()));
    }



}
