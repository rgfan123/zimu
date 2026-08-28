package cn.zimu.fulfillment.connector.schedule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 自动发货后读回「京东那边到底为什么没建成单」。
 *
 * <p><b>为什么必须回读数据库，而不是接住异常</b>：具体原因在调用链上被压平了两次。
 * {@code ShipmentJdOutboundExecutor} 把所有库存/映射阻断收敛成一个
 * {@code JD_STOCK_CHECK_BLOCKED}；{@code ShipmentJdOutboundService#submitFailure} 再把它
 * 收敛成 {@code JD_SHIPMENT_OUTBOUND_REJECTED}。而
 * {@code SourceBatchConfirmer#submitJdOutboundsForSourceBatch} 的返回值是 void，
 * 连那个压平后的码都拿不到。
 *
 * <p>于是就算原样接住异常，播报出来也只会是「京东建出库单失败」——正是需求里点名不许
 * 出现的那种笼统失败。真缺货与映射没配在这条路径上完全无法区分。
 *
 * <p>具体码唯一幸存的地方是 {@code app.review_cases}（{@code reason_code='JD_STOCK_BLOCKED'}）
 * 的 {@code detail->'blockers'}：那里保留着 {@code JD_STOCK_INSUFFICIENT} 与映射门禁的
 * 内层 {@code mapping_issue_code}。本类只读它，不写任何表。
 *
 * <p><b>PII 边界</b>：只取 {@code code} 与 {@code mapping_issue_code} 两个受控词表字段。
 * 同一个 blocker 里还有 {@code message}（含商品名与数量的自由文本）与 {@code product_name}，
 * 一律不取——本结果会被渲染进企微卡片。
 */
@Component
class AutoShipBlockerReader {

    /**
     * 批次内因京东库存/映射判定被挡下的阻断码。
     *
     * <p>{@code CROSS JOIN LATERAL} 前的 {@code jsonb_typeof} 守卫不是多余的：
     * {@code jsonb_array_elements} 遇到非数组会在运行时抛错，而 detail 的 CHECK 只约束了
     * 顶层是 object，没约束 blockers 一定是数组。播报路径不该因为一条畸形 detail 就整个炸掉。
     */
    private static final String BLOCKER_SQL =
            """
            SELECT DISTINCT blocker->>'code' AS code,
                            blocker->>'mapping_issue_code' AS mapping_issue_code
            FROM app.review_cases rc
            JOIN app.shipments s ON s.id = rc.shipment_id
            JOIN app.raw_import_rows rir ON rir.order_id = s.order_id
            CROSS JOIN LATERAL jsonb_array_elements(rc.detail->'blockers') blocker
            WHERE rir.import_batch_id = ?
              AND rc.status = 'OPEN'
              AND rc.reason_code = 'JD_STOCK_BLOCKED'
              AND jsonb_typeof(rc.detail->'blockers') = 'array'
            """;

    /**
     * 批次内建单失败的发货批次数与失败码。
     *
     * <p>{@code JD_STOCK_CHECK_BLOCKED} 是上面那条 SQL 已经展开过的压平码，这里排除掉，
     * 免得播报里同时出现「缺货」和一个什么都没说的 {@code JD_STOCK_CHECK_BLOCKED}。
     * 其余的码（写模式未开、对账未完成、操作人未授权等）是京东库存判定之外的失败，
     * 必须如实带出来——它们恰恰是最容易被「反正就是失败了」掩盖掉的那类。
     */
    private static final String FAILURE_SQL =
            """
            SELECT COALESCE(sjo.last_error_code, 'UNKNOWN') AS code, count(DISTINCT sjo.shipment_id) AS failed
            FROM app.shipment_jd_outbounds sjo
            JOIN app.shipments s ON s.id = sjo.shipment_id
            JOIN app.raw_import_rows rir ON rir.order_id = s.order_id
            WHERE rir.import_batch_id = ?
              AND sjo.sync_status = 'SYNC_FAILED'
            GROUP BY 1
            """;

    /**
     * 批次里「本该建京东单、却连一条建单痕迹都没有」的发货批次数。
     *
     * <p><b>这条查询是本类最重要的一条</b>。{@code requireAuthorized} 是
     * {@code ShipmentJdOutboundService#submit} 的第一行，它抛 403 时
     * {@code persistSubmitIntent} 还没跑，{@code shipment_jd_outbounds} 里一行都不会写。
     * 只看 {@code sync_status='SYNC_FAILED'} 的话，「操作人没授权、整批一单没建成」
     * 与「一切正常」返回的结果完全一样——自动发货会平静地播报 SHIPPED，
     * 而货一件都没发出去。那正是本特性最该防的事。
     *
     * <p>只统计 {@code JD_WAREHOUSE} 履约方的发货批次：第三方履约走导单文件，
     * 本来就没有京东出库单，把它们算进来会天天误报。
     *
     * <p>{@code SYNC_FAILED} **刻意排除**：那种失败已经由上面两条查询给出了具体原因，
     * 再算一次「未建单」会让同一件事在卡面上出现两遍。这里要抓的是**没有解释**的那些：
     * 压根没有记录（403 / 从未尝试），或停在 {@code NONE}/{@code SUBMITTING} 的半途。
     */
    private static final String NOT_SUBMITTED_SQL =
            """
            SELECT count(DISTINCT s.id) AS not_submitted
            FROM app.shipments s
            JOIN app.fulfillment_providers fp
              ON fp.id = s.fulfillment_provider_id AND fp.provider_type = 'JD_WAREHOUSE'
            JOIN app.raw_import_rows rir ON rir.order_id = s.order_id
            LEFT JOIN app.shipment_jd_outbounds sjo ON sjo.shipment_id = s.id
            WHERE rir.import_batch_id = ?
              AND rir.status = 'ACCEPTED'
              AND (sjo.shipment_id IS NULL OR sjo.sync_status IN ('NONE', 'SUBMITTING'))
            """;

    /** 已在 {@link #BLOCKER_SQL} 里展开成具体原因的压平码，不重复播报。 */
    private static final String FLATTENED_STOCK_CODE = "JD_STOCK_CHECK_BLOCKED";

    private final JdbcTemplate jdbc;

    AutoShipBlockerReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一个批次建单失败的全貌。
     *
     * @param failedShipments      建单失败（SYNC_FAILED）的发货批次数
     * @param notSubmittedShipments 本该建京东单却没有 SUBMITTED 记录的发货批次数
     * @param blockers             库存/映射阻断码（受控词表，无自由文本）
     * @param otherCodes           库存判定之外的失败码（受控词表）
     */
    record Failures(
            int failedShipments,
            int notSubmittedShipments,
            List<Map<String, String>> blockers,
            List<String> otherCodes) {

        static Failures none() {
            return new Failures(0, 0, List.of(), List.of());
        }

        boolean any() {
            return failedShipments > 0
                    || notSubmittedShipments > 0
                    || !blockers.isEmpty()
                    || !otherCodes.isEmpty();
        }

        /** 播报用的归类摘要：未建单、缺货、映射校验、京东无答复各自分开。 */
        String describe() {
            String classified = AutoShipReasons.describe(AutoShipReasons.summarize(withNotSubmitted()));
            if (otherCodes.isEmpty()) {
                return classified;
            }
            String others = AutoShipReasons.Category.OTHER.label() + ": " + String.join(", ", otherCodes);
            return classified.isEmpty() ? others : classified + "; " + others;
        }

        /** 「未建单」参与归类：它是最需要人立刻动手的一类，不该被降格成一句附注。 */
        List<Map<String, String>> withNotSubmitted() {
            if (notSubmittedShipments == 0) {
                return blockers;
            }
            List<Map<String, String>> all = new ArrayList<>();
            all.add(Map.of("code", AutoShipReasons.NOT_SUBMITTED_CODE));
            all.addAll(blockers);
            return List.copyOf(all);
        }
    }

    /** 读一个批次的失败全貌。任何一段查询失败都不该掀翻发货流程，故整体兜底成「读不到」。 */
    Failures of(long batchId) {
        List<Map<String, String>> blockers = jdbc.query(
                BLOCKER_SQL,
                (resultSet, rowNum) -> {
                    Map<String, String> blocker = new LinkedHashMap<>();
                    blocker.put("code", resultSet.getString("code"));
                    String mappingIssue = resultSet.getString("mapping_issue_code");
                    if (mappingIssue != null && !mappingIssue.isBlank()) {
                        blocker.put("mapping_issue_code", mappingIssue);
                    }
                    return Map.copyOf(blocker);
                },
                batchId);

        List<String> otherCodes = new ArrayList<>();
        int failed = 0;
        for (Map<String, Object> row : jdbc.queryForList(FAILURE_SQL, batchId)) {
            String code = String.valueOf(row.get("code"));
            failed += ((Number) row.get("failed")).intValue();
            if (!FLATTENED_STOCK_CODE.equals(code)) {
                otherCodes.add(code);
            }
        }

        Integer notSubmitted = jdbc.queryForObject(NOT_SUBMITTED_SQL, Integer.class, batchId);
        return new Failures(
                failed,
                notSubmitted == null ? 0 : notSubmitted,
                List.copyOf(blockers),
                List.copyOf(otherCodes));
    }
}
