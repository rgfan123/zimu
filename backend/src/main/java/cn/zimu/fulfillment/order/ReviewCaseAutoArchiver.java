package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复核事项自动归档：**事实已经替人回答了的问题，不再追着人要答案**。
 *
 * <p>2026-08-27 生产实证：工作台挂着 16 张「事实上已完成」的事项——12 张
 * 运单回填待复核（订单早已 SHIPPED、运单在库）、4 张京东建单预检未通过
 * （出库单早已 SUBMITTED 建成）。它们的提问在后续链路里被客观事实回答了，
 * 留着只会让工作台失去「打开就知道该干什么」的意义。
 *
 * <p>归档条件只认<b>可验证的完成证据</b>，不认时间：
 * <ul>
 *   <li>回填待复核 → 订单已发货 <b>且</b> 运单号已入库（案子问的就是回填对不对，
 *       回填的运单已随回传送达渠道，即为已答）；</li>
 *   <li>预检未通过 → 该订单的京东出库单已 SUBMITTED（预检拦的是「能不能建单」，
 *       单已建成，问题失效）。</li>
 * </ul>
 * 条件不满足的一律不动——比如已发货但运单还没回来的订单，案子继续留着。
 */
@Service
public class ReviewCaseAutoArchiver {

    private static final Logger log = LoggerFactory.getLogger(ReviewCaseAutoArchiver.class);

    private final JdbcTemplate jdbc;
    private final AuditLogService audits;

    public ReviewCaseAutoArchiver(JdbcTemplate jdbc, AuditLogService audits) {
        this.jdbc = jdbc;
        this.audits = audits;
    }

    @Scheduled(fixedDelayString = "${app.review-case.auto-archive-ms:60000}")
    @Transactional
    public void sweep() {
        int backfilled = jdbc.update(
                """
                UPDATE app.review_cases rc
                   SET status = 'RESOLVED',
                       resolution = jsonb_build_object(
                           'resolution_type', 'AUTO_ARCHIVED',
                           'rule', 'ORDER_SHIPPED_WITH_TRACKING'),
                       resolved_by = 'system:auto-archiver',
                       resolved_at = now(), updated_at = now()
                 WHERE rc.status = 'OPEN'
                   AND rc.reason_code = 'JD_TRACKING_BACKFILLED_PENDING_REVIEW'
                   AND EXISTS (SELECT 1 FROM app.orders o
                                WHERE o.id = rc.order_id AND o.order_status IN ('SHIPPED', 'DELIVERED'))
                   AND EXISTS (SELECT 1 FROM app.trackings t
                                JOIN app.shipments s ON s.id = t.shipment_id
                                WHERE s.order_id = rc.order_id)
                """);
        int previewMoot = jdbc.update(
                """
                UPDATE app.review_cases rc
                   SET status = 'RESOLVED',
                       resolution = jsonb_build_object(
                           'resolution_type', 'AUTO_ARCHIVED',
                           'rule', 'JD_OUTBOUND_ALREADY_SUBMITTED'),
                       resolved_by = 'system:auto-archiver',
                       resolved_at = now(), updated_at = now()
                 WHERE rc.status = 'OPEN'
                   AND rc.reason_code = 'JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED'
                   AND EXISTS (SELECT 1 FROM app.shipment_jd_outbounds j
                                JOIN app.shipments s ON s.id = j.shipment_id
                                WHERE s.order_id = rc.order_id AND j.sync_status = 'SUBMITTED')
                """);
        if (backfilled + previewMoot > 0) {
            log.info("复核事项自动归档 backfilled={} preview_moot={}", backfilled, previewMoot);
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .operator("system:auto-archiver")
                    .actorType(AuditActorType.SYSTEM)
                    .service("review-case")
                    .operation("review_case.auto_archive")
                    .responsePayload(Map.of(
                            "backfilled_archived", backfilled,
                            "preview_moot_archived", previewMoot))
                    .httpStatus(200)
                    .businessCode("REVIEW_CASE_AUTO_ARCHIVED")
                    .latencyMs(0));
        }
    }
}
