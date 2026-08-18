package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.basicinfo.JDBasicInfoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 京东商品只读事实核验器。新旧 SKU 核对用例共用该解析边界，避免对
 * {@code queryGoodsInfo} 的查询类型、启用状态和响应形状产生漂移。
 */
@Component
public class JdGoodsReadOnlyVerifier {

    /**
     * {@code queryType} 只能取 1。官方定义（快照 2026-08-11）：
     * {@code docs/research/jdl-api-367/json/1610-queryGoodsInfo.json}，inParams 中
     * {@code queryType}（必填，String）remark = 「查询类型，枚举：1-查询全部信息；2-查询商品编号」，
     * 官方请求示例同样用 {@code "queryType":"1"}；HTML 快照
     * {@code docs/research/jdl-api-367/html/1610-queryGoodsInfo.html} 表述一致。
     *
     * <p>此前硬编码为 "2"（误读为「按商品编号查」），实测被京东按字面语义执行——只回商品编号：
     * 审计日志 app.audit_logs id=165 传 {@code queryType="2"}，basicInfo 43 个键中仅 goodsNo/barcode
     * 有值，{@code goodsName/goodsUnit/erpGoodsNo/enableFlag} 全为 null；同一 goodsNo
     * （EMG4418691852262）在 id=173~178 不传 queryType 时返回 {@code enableFlag=2、goodsName=羊腿肉、
     * goodsUnit=提}。空壳 basicInfo 会让下游把 enableFlag=null 判成 GOODS_STATUS_MISSING 而阻断建单。
     */
    static final String GOODS_QUERY_TYPE = "1";

    private final JDBasicInfoService client;

    public JdGoodsReadOnlyVerifier(JDBasicInfoService client) {
        this.client = client;
    }

    /** 外部只读调用必须发生在事务与行锁之外。 */
    public Verification verify(String goodsNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("queryGoodsInfo must run outside a database transaction");
        }
        JdResult response;
        try {
            response = client.queryGoodsInfo(Map.of(
                    "goodsNo", goodsNo,
                    "queryType", GOODS_QUERY_TYPE,
                    "pageSize", 20,
                    "currentPage", 1));
        } catch (RuntimeException exception) {
            return Verification.queryFailed("CLIENT_EXCEPTION", null);
        }
        if (response == null || !response.success()) {
            return Verification.queryFailed(
                    response == null ? "NO_RESPONSE" : text(response.businessCode()),
                    response == null ? null : response.requestId());
        }
        List<Map<String, Object>> goods = goodsList(response.data());
        if (goods.isEmpty()) {
            return Verification.notFound(response.businessCode(), response.requestId());
        }
        Map<String, Object> basicInfo = firstBasicInfo(goods.getFirst());
        return Verification.found(
                response.businessCode(),
                response.requestId(),
                text(basicInfo.get("goodsNo")),
                text(basicInfo.get("erpGoodsNo")),
                text(basicInfo.get("goodsName")),
                integer(basicInfo.get("enableFlag")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> goodsList(Object data) {
        if (!(data instanceof List<?> items)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstBasicInfo(Map<String, Object> goods) {
        Object value = goods.get("basicInfo");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.valueOf(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record Verification(
            boolean querySucceeded,
            boolean found,
            String businessCode,
            String requestId,
            String goodsNo,
            String erpGoodsNo,
            String goodsName,
            Integer enableFlag) {

        public static Verification queryFailed(String businessCode, String requestId) {
            return new Verification(false, false, businessCode, requestId, null, null, null, null);
        }

        public static Verification notFound(String businessCode, String requestId) {
            return new Verification(true, false, businessCode, requestId, null, null, null, null);
        }

        public static Verification found(
                String businessCode,
                String requestId,
                String goodsNo,
                String erpGoodsNo,
                String goodsName,
                Integer enableFlag) {
            return new Verification(
                    true, true, businessCode, requestId, goodsNo, erpGoodsNo, goodsName, enableFlag);
        }
    }
}
