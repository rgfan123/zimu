package cn.zimu.fulfillment.recon;

import java.util.List;
import java.util.Map;

/**
 * 出库信息内外事实并排视图（Ticket 01）。
 *
 * <p>针对一笔出库，把系统内部记录的事实（发货/履约/运单/京东集成）与京东侧返回的事实
 * （querySoOrder）按语义对齐后并排返回，并给出逐字段差异判定。页面形态面向运营自查，
 * 不一致只标记不自动处置。
 */
public record OutboundReconView(
        Query query,
        Audit audit,
        InternalSide internal,
        JdSide jd,
        List<Comparison> comparisons,
        int matched_count,
        int mismatch_count) {

    /** 本次查询条件：type ∈ OUTBOUND_ORDER_NO / JD_DELIVERY_NO / ORDER_NO。 */
    public record Query(String type, String value) {}

    /** 审计引用：谁在什么时候查了什么（详细记录见 app.audit_logs）。 */
    public record Audit(String request_id, String operator) {}

    /** 系统内部事实；HTTP 404 时整个视图不返回，因此 present 恒为 true。 */
    public record InternalSide(
            Map<String, Object> summary,
            List<Map<String, Object>> items,
            Map<String, Object> tracking) {}

    /**
     * 京东侧事实。status ∈ OK / NOT_FOUND / UNAVAILABLE：
     * OK 表示查询成功且返回了出库记录；NOT_FOUND 表示查询成功但京东没有这笔出库；
     * UNAVAILABLE 表示查询失败/超时/未配置，此时 summary 与 items 为空，
     * 调用方必须明确标注「京东侧未取到」，不得显示为空值。
     */
    public record JdSide(
            String status,
            String business_code,
            String message,
            String client_mode,
            Map<String, Object> summary,
            List<Map<String, Object>> items) {}

    /**
     * 逐字段对齐结果。state ∈ MATCH / MISMATCH / INTERNAL_ONLY / JD_ONLY / EMPTY /
     * JD_UNAVAILABLE / JD_NOT_FOUND；jd 侧整体不可达或无记录时，每行统一标记，避免把
     * 「没取到」显示成「字段为空」。
     */
    public record Comparison(
            String key,
            String label,
            Object internal_value,
            Object jd_value,
            boolean internal_present,
            boolean jd_present,
            String state,
            String note) {}
}
