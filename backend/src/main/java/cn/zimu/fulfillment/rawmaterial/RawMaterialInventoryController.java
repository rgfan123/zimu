package cn.zimu.fulfillment.rawmaterial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原料库存只读视图（票 09）：把 yuanliaokc 实时结存原样呈现给前端。
 *
 * <p>失败翻译坚持「读不到 ≠ 没有」：四类网关失败各自映射稳定 business_code，
 * HTTP 状态刻意避开 401——对浏览器回 401 会连坐网关 Basic 会话；
 * 上游鉴权失败是本系统的部署问题（凭据过期），不是当前操作人的身份问题。
 */
@RestController
public class RawMaterialInventoryController {

    private final YuanliaokcReadGateway gateway;
    private final ObjectMapper mapper;

    public RawMaterialInventoryController(YuanliaokcReadGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @GetMapping("/api/v1/raw-material-inventory/stock")
    public ObjectNode stock(@RequestParam(required = false) String keyword) {
        List<YuanliaokcStockRow> rows;
        try {
            rows = gateway.stock(keyword);
        } catch (RawMaterialReadException failure) {
            throw translate(failure);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("source", "YUANLIAOKC");
        ArrayNode items = body.putArray("items");
        for (YuanliaokcStockRow row : rows) {
            ObjectNode item = items.addObject();
            item.put("material_id", row.materialId());
            item.put("material_code", row.materialCode());
            item.put("material_name", row.materialName());
            item.put("category", row.category());
            item.put("spec", row.spec());
            item.put("unit", row.unit());
            if (row.pieceCount() == null) {
                item.putNull("piece_count");
            } else {
                item.put("piece_count", row.pieceCount());
            }
            // kg 结存是重量，decimal-string 出口（与本仓价格纪律一致），浮点不入 JSON；
            // 统一 3 位（克级）刻度再去尾零，吸掉上游 float 聚合的长尾噪声。
            item.put("current_kg", kg(row.currentKg()));
            item.put("available_kg", kg(row.availableKg()));
            item.put("frozen_kg", kg(row.frozenKg()));
            item.put("batch_count", row.batchCount());
            item.put("earliest_expiry", row.earliestExpiry());
            item.put("status", row.status());
        }
        return body;
    }

    private static String kg(java.math.BigDecimal value) {
        java.math.BigDecimal scaled = value.setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        if (scaled.scale() < 0) {
            scaled = scaled.setScale(0);
        }
        return scaled.toPlainString();
    }

    private static BusinessException translate(RawMaterialReadException failure) {
        return switch (failure.code()) {
            case RAW_MATERIAL_NOT_CONFIGURED ->
                    new BusinessException(503, "RAW_MATERIAL_NOT_CONFIGURED", "本部署未开放原料库存只读接入");
            case RAW_MATERIAL_UNAVAILABLE ->
                    new BusinessException(503, "RAW_MATERIAL_UNAVAILABLE", "原料库存上游暂不可用，请稍后重试");
            case RAW_MATERIAL_UNAUTHORIZED ->
                    new BusinessException(502, "RAW_MATERIAL_UNAUTHORIZED", "原料库存上游拒绝了本系统的只读凭据");
            case RAW_MATERIAL_CONTRACT_DRIFT ->
                    new BusinessException(502, "RAW_MATERIAL_CONTRACT_DRIFT", "原料库存上游返回结构与约定不一致，已停止解析");
        };
    }
}
