package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.ScheduledPullReportCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 定时拉取运行播报卡的来源：从 {@code app.scheduled_pull_runs} 按当前事实渲染。
 *
 * <p>只扫 {@code status <> 'RUNNING' AND problem_count > 0 AND notify_wecom}——收口、有问题、
 * 且这个渠道开着「拉取后推企微」，三者齐了才发卡。
 * V83 为前两条判据建了偏索引 {@code idx_scheduled_pull_runs_notify}。
 * 没问题的运行一张卡都不发：每天两次准点报平安，两周后就没人看了。
 *
 * <p>{@code notify_wecom} 读的是运行行上的列，不是当下的渠道配置（V85）——卡发不发取决于
 * 触发那一刻的配置，事后有人改了开关，也不该让一次历史运行的含义跟着变。
 *
 * <p><b>投影即 PII 边界</b>。运行表本身刻意不存收件人任何字段，但两个 JSONB 摘要里
 * 仍有自由文本：拉取结果的 {@code message}（可能是 {@code "导入失败: " + 异常消息}，
 * 异常消息可能引用某一行的内容）与发货结果的自由描述。本类**逐字段挑选**：
 * 只取渠道名、{@code business_code}、{@code outcome}、{@code reason_codes} 与
 * {@code detail}（后两者由 {@code AutoShipService} 用受控词表拼成），
 * {@code message} 一律不取。卡面因此只有受控词表与计数，可以进群。
 *
 * <p>归类（缺货 / 映射校验 / 京东无答复）不在本类重做——那是 {@code AutoShipReasons}
 * 的职责，结果已经写进 {@code detail}。这里只做拼接与截断，判据只有一处。
 */
@Service
public class ScheduledPullReportCardSource implements WecomBusinessCardSource {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPullReportCardSource.class);

    /** 卡面 sub_title 上限 112 字，构造器会截断；这里先按语义裁剪，避免截在半个原因上。 */
    private static final int MAX_REASON_ITEMS = 6;

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;
    private final ObjectMapper objectMapper;

    public ScheduledPullReportCardSource(
            JdbcTemplate jdbc,
            WecomBusinessCardRouteProperties routes,
            CardDeepLinks links,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
        this.objectMapper = objectMapper;
    }

    @Override
    public String domain() {
        return ScheduledPullReportCard.DOMAIN;
    }

    /**
     * 普通路由（未配 type 时默认 GROUP），与 {@code alert}/{@code batch} 同一做法。
     *
     * <p>不加 {@code preship} 那样的 SINGLE 硬过滤：那道闸是因为卡面与随卡清单带
     * 收件人姓名、手机号与详细地址。本卡的投影里没有任何收件信息，
     * 群里看到的只有渠道名、原因码与计数。
     */
    @Override
    public Optional<Route> route(long entityId) {
        return routes.resolve(domain());
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        List<ScheduledPullReportCard.View> views = jdbc.query(
                """
                SELECT id, lock_version, run_key, slot, shipped_batches,
                       pull_summary::text AS pull_summary, ship_summary::text AS ship_summary
                FROM app.scheduled_pull_runs
                WHERE id = ? AND lock_version = ? AND status <> 'RUNNING' AND problem_count > 0
                  AND notify_wecom
                """,
                (rs, rowNum) -> view(
                        rs.getLong("id"),
                        rs.getLong("lock_version"),
                        rs.getString("run_key"),
                        rs.getString("slot"),
                        rs.getInt("shipped_batches"),
                        rs.getString("pull_summary"),
                        rs.getString("ship_summary")),
                entityId,
                entityVersion);
        return views.isEmpty() ? Optional.empty() : Optional.of(ScheduledPullReportCard.render(views.getFirst()));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        return jdbc.query(
                """
                SELECT r.id, r.lock_version
                FROM app.scheduled_pull_runs r
                LEFT JOIN app.wecom_business_cards c
                       ON c.card_domain = 'scheduled-pull'
                      AND c.entity_id = r.id
                      AND c.entity_version = r.lock_version
                WHERE r.status <> 'RUNNING'
                  AND r.problem_count > 0
                  AND r.notify_wecom
                  AND r.finished_at >= ?
                  AND c.id IS NULL
                ORDER BY r.finished_at
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        ScheduledPullReportCard.DOMAIN, rs.getLong("id"), rs.getLong("lock_version")),
                since,
                limit);
    }

    // ------------------------------------------------------------------
    // 投影：只挑受控字段，自由文本一律不取
    // ------------------------------------------------------------------

    private ScheduledPullReportCard.View view(
            long runId,
            long lockVersion,
            String runKey,
            String slot,
            int shippedBatches,
            String pullSummaryJson,
            String shipSummaryJson) {
        JsonNode pull = readArray(pullSummaryJson);
        JsonNode ship = readArray(shipSummaryJson);

        int pullChannels = pull.size();
        List<String> reasons = new ArrayList<>();
        int pullFailed = 0;
        Set<String> pullCodes = new LinkedHashSet<>();
        for (JsonNode channel : pull) {
            if (!"FAILED".equals(channel.path("status").asText())) {
                continue;
            }
            pullFailed++;
            // 只取渠道名与 business_code；message 是自由文本（可能含异常里引用的订单内容），不取。
            pullCodes.add(channel.path("channel").asText("UNKNOWN")
                    + "(" + channel.path("business_code").asText("UNKNOWN") + ")");
        }
        if (!pullCodes.isEmpty()) {
            reasons.add("拉取失败 " + String.join(", ", pullCodes));
        }

        int blockedBatches = 0;
        Set<String> blockedCodes = new LinkedHashSet<>();
        Set<String> jdDetails = new LinkedHashSet<>();
        for (JsonNode entry : ship) {
            String outcome = entry.path("outcome").asText("");
            if ("SKIPPED_BLOCKED".equals(outcome)) {
                blockedBatches++;
                entry.path("reason_codes").forEach(code -> blockedCodes.add(code.asText()));
                continue;
            }
            if ("SHIPPED".equals(outcome) || "ALREADY_CONFIRMED".equals(outcome)) {
                continue;
            }
            // 其余都是需要人处理的结局；detail 由 AutoShipService 用受控词表拼成，可直接示人。
            String detail = entry.path("detail").asText("");
            jdDetails.add(detail.isBlank() ? outcome : outcome + " " + detail);
        }
        if (blockedBatches > 0) {
            reasons.add(blockedBatches + " 批有阻断行未自动确认"
                    + (blockedCodes.isEmpty() ? "" : "（" + String.join(", ", blockedCodes) + "）"));
        }
        reasons.addAll(jdDetails);

        String reasonSummary = reasons.stream().limit(MAX_REASON_ITEMS).reduce((a, b) -> a + "；" + b).orElse("");
        String jdSummary = jdDetails.stream().findFirst().orElse("");

        return new ScheduledPullReportCard.View(
                runId,
                lockVersion,
                runKey,
                slotLabel(slot),
                pullChannels,
                pullFailed,
                shippedBatches,
                blockedBatches,
                reasonSummary.isBlank() ? "本次运行有需要人处理的事项，详见后台" : reasonSummary,
                jdSummary,
                links.of("/operations"));
    }

    /** 摘要读不出来时按空数组处理：一条畸形 JSON 不该让整张卡发不出去。 */
    private JsonNode readArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isArray() ? node : objectMapper.createArrayNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.warn("定时拉取摘要解析失败，本张卡按空摘要渲染", exception);
            return objectMapper.createArrayNode();
        }
    }

    /**
     * 时段文案。
     *
     * <p>刻意**不写具体时刻**：V85 起每个渠道各自设时间，「早上 09:00」对配了 08:00 的渠道
     * 就是假话。真实时刻在 {@code run_key}（{@code 日期:时段:渠道}）里，卡面 desc 已经带着它。
     */
    private static String slotLabel(String slot) {
        return switch (slot == null ? "" : slot) {
            case "MORNING" -> "早班";
            case "EVENING" -> "晚班";
            case "MANUAL" -> "手动触发";
            default -> "未知时段";
        };
    }
}
