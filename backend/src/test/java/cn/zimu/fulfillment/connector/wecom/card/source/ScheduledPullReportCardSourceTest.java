package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.connector.wecom.card.ScheduledPullReportCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 定时拉取播报卡的扫描、投影与 PII 边界。
 *
 * <p>这张卡是本特性里唯一会进群的产物，所以最重要的断言不是「渲染得好看」，
 * 而是「摘要里的自由文本一个字都没进卡面」。
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.wecom-business-card.routes.scheduled-pull.type=GROUP",
    "app.wecom-business-card.routes.scheduled-pull.chat-id=test-ops-group"
})
class ScheduledPullReportCardSourceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired ScheduledPullReportCardSource source;

    // ------------------------------------------------------------------
    // 扫描：只在有事要办时打扰人
    // ------------------------------------------------------------------

    @Test
    void onlyFinishedRunsWithProblemsAreScanned() {
        long withProblem = seedRun("P1", "COMPLETED", 2, 1, "[]", "[]");
        long clean = seedRun("P2", "COMPLETED", 0, 3, "[]", "[]");
        long running = seedRunning("P3");

        var pending = source.pending(OffsetDateTime.now().minusDays(1), 50).stream()
                .map(taskId -> taskId.entityId())
                .toList();

        assertThat(pending).contains(withProblem);
        // 每天两次准点报平安，两周之后就没人再看这张卡了——而那正是出事的那天。
        assertThat(pending).doesNotContain(clean);
        // 还在跑的运行没有结论可播报。
        assertThat(pending).doesNotContain(running);
    }

    @Test
    void aRunIsNotScannedTwiceOnceACardExistsForThatVersion() {
        long runId = seedRun("ONCE", "COMPLETED", 1, 0, "[]", "[]");
        jdbc.update(
                """
                INSERT INTO app.wecom_business_cards
                    (task_id, card_domain, entity_id, entity_version, route_type, chat_id, status)
                VALUES ('scheduled-pull_' || ? || '_v1_' || md5('x'), 'scheduled-pull', ?, 1,
                        'GROUP', 'test-ops-group', 'PENDING')
                """,
                runId, runId);

        assertThat(source.pending(OffsetDateTime.now().minusDays(1), 50).stream()
                        .map(taskId -> taskId.entityId())
                        .toList())
                .doesNotContain(runId);
    }

    @Test
    void aRunThatNoLongerQualifiesRendersNothingRatherThanAStaleCard() {
        long clean = seedRun("STALE", "COMPLETED", 0, 1, "[]", "[]");

        assertThat(source.render(clean, 1)).isEmpty();
    }

    @Test
    void aVersionThatHasMovedOnRendersNothing() {
        long runId = seedRun("VER", "COMPLETED", 1, 0, "[]", "[]");

        assertThat(source.render(runId, 99)).isEmpty();
    }

    // ------------------------------------------------------------------
    // PII 边界：这张卡会进群
    // ------------------------------------------------------------------

    @Test
    void freeTextFromThePullSummaryNeverReachesTheCard() {
        // 拉取结果的 message 可能是「导入失败: <异常消息>」，而异常消息可能引用某一行的内容。
        String pull = """
                [{"channel":"FEIXIANG","status":"FAILED","business_code":"SCRIPT_FAILED",
                  "message":"导入失败: 收件人 张三 13800000000 北京市朝阳区某路 1 号 解析异常",
                  "batch_no":"B-1"}]
                """;
        long runId = seedRun("PII", "COMPLETED", 1, 0, pull, "[]");

        String card = card(runId).toString();

        assertThat(card).doesNotContain("张三").doesNotContain("13800000000").doesNotContain("北京市朝阳区");
        // 渠道名与受控词表的业务码照常带出来——不带就等于什么都没播报。
        assertThat(card).contains("FEIXIANG").contains("SCRIPT_FAILED");
    }

    @Test
    void theCardCarriesNoReceiverFieldsAtAll() {
        long runId = seedRun("CLEAN", "COMPLETED", 1, 1, """
                [{"channel":"CAISHIXIAN","status":"OK","business_code":"OK","message":"已拉取","batch_no":"B-2"}]
                """, """
                [{"batch_id":"7","batch_no":"B-7","channel":"CAISHIXIAN","outcome":"SKIPPED_BLOCKED",
                  "reason_codes":["PROVIDER_SKU_MAPPING_REQUIRED"],"detail":"阻断 1 行，待处理 4 行"}]
                """);

        String card = card(runId).toString();

        assertThat(card)
                .doesNotContain("receiver")
                .doesNotContain("收货人")
                .doesNotContain("收件人");
    }

    // ------------------------------------------------------------------
    // 投影：区分类型，不笼统报失败
    // ------------------------------------------------------------------

    @Test
    void blockedBatchesAndPullFailuresAreReportedAsDifferentThings() {
        long runId = seedRun("MIX", "COMPLETED", 2, 1, """
                [{"channel":"FEIXIANG","status":"FAILED","business_code":"SCRIPT_FAILED","message":"登录失败"},
                 {"channel":"JUFUBAO","status":"OK","business_code":"OK","message":"已拉取"}]
                """, """
                [{"batch_id":"7","batch_no":"B-7","channel":"FEIXIANG","outcome":"SKIPPED_BLOCKED",
                  "reason_codes":["PROVIDER_SKU_MAPPING_REQUIRED"],"detail":"阻断 1 行，待处理 4 行"}]
                """);

        ObjectNode card = card(runId);
        String subTitle = card.path("sub_title_text").asText();

        assertThat(subTitle)
                .contains("拉取失败 FEIXIANG(SCRIPT_FAILED)")
                .contains("1 批有阻断行未自动确认")
                .contains("PROVIDER_SKU_MAPPING_REQUIRED");
        // 拉取一行要同时写出成功与失败数：只报失败数会让「全没跑」看起来像「零失败」。
        assertThat(card.toString()).contains("失败 1 / 共 2");
    }

    @Test
    void stockShortageStaysDistinctFromMappingProblemsOnTheCard() {
        long runId = seedRun("JD", "COMPLETED", 1, 1, "[]", """
                [{"batch_id":"9","batch_no":"B-9","channel":"FEIXIANG","outcome":"SHIPPED_WITH_JD_FAILURES",
                  "reason_codes":["STOCK_INSUFFICIENT:JD_STOCK_INSUFFICIENT","SKU_MAPPING:MAPPING_MISSING"],
                  "detail":"缺货: JD_STOCK_INSUFFICIENT; 映射校验: MAPPING_MISSING(疑似误报)"}]
                """);

        String card = card(runId).toString();

        assertThat(card).contains("缺货: JD_STOCK_INSUFFICIENT");
        assertThat(card).contains("映射校验: MAPPING_MISSING(疑似误报)");
    }

    @Test
    void aSuccessfullyShippedBatchIsNotListedAsAProblem() {
        long runId = seedRun("QUIET", "COMPLETED", 1, 2, """
                [{"channel":"FEIXIANG","status":"FAILED","business_code":"SCRIPT_FAILED","message":"登录失败"}]
                """, """
                [{"batch_id":"1","batch_no":"B-1","outcome":"SHIPPED","reason_codes":[],"detail":""},
                 {"batch_id":"2","batch_no":"B-2","outcome":"ALREADY_CONFIRMED","reason_codes":[],
                  "detail":"本日已确认，未重复建单"}]
                """);

        String subTitle = card(runId).path("sub_title_text").asText();

        assertThat(subTitle).contains("拉取失败");
        assertThat(subTitle).doesNotContain("SHIPPED").doesNotContain("ALREADY_CONFIRMED");
    }

    @Test
    void slotIsSpelledOutInPlainChinese() {
        long runId = seedRun("SLOT", "COMPLETED", 1, 0, "[]", "[]");

        assertThat(card(runId).toString()).contains("早上 09:00");
    }

    @Test
    void theDatabaseItselfRefusesANonArraySummary() {
        // V83 的 CHECK 保证两个摘要一定是数组，所以 source 里的「读不出就当空数组」
        // 是纵深防御而不是主防线。把这条约束钉在测试里：有人放宽它时，
        // 得先在这里看到「放宽之后卡片投影要自己扛畸形 JSON」。
        assertThatThrownBy(() -> seedRunRaw("BADJSON", "COMPLETED", 1, 0, "\"not-an-array\"", "[]"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pull_summary");
    }

    @Test
    void anEmptySummaryStillProducesASendableCard() {
        // 有问题但两个摘要都空（例如编排自身炸了）：卡还是要发得出去，
        // 否则出问题的当天恰好没人被通知。
        long runId = seedRun("EMPTY", "COMPLETED", 1, 0, "[]", "[]");

        ObjectNode card = card(runId);

        assertThat(card.path("main_title").path("title").asText()).isNotBlank();
        assertThat(card.path("sub_title_text").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // 路由：可以进群，依据是卡面无收件信息
    // ------------------------------------------------------------------

    @Test
    void theCardIsAllowedIntoAGroupUnlikeThePreshipFamily() {
        long runId = seedRun("ROUTE", "COMPLETED", 1, 0, "[]", "[]");

        Optional<WecomBusinessCardSource.Route> route = source.route(runId);

        // preship / preship-batch / shipped 三张卡在 source 里硬过滤 SINGLE，因为卡面带
        // 收货人手机号与详细地址。本卡的投影里没有任何收件字段，故按 alert/batch 的做法走普通路由。
        assertThat(route).isPresent();
        assertThat(route.get().type()).isEqualTo(WecomBusinessCardSource.RouteType.GROUP);
        assertThat(route.get().chatId()).isEqualTo("test-ops-group");
    }

    @Test
    void theDomainIsAProtocolLegalTaskIdPrefix() {
        // 企微 task_id 只允许数字、字母、_-@；域名里出现下划线或冒号会让带按钮的卡被平台拒收。
        assertThat(ScheduledPullReportCard.DOMAIN).matches("^[a-z][a-z-]{0,31}$");
        assertThat(source.domain()).isEqualTo(ScheduledPullReportCard.DOMAIN);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private ObjectNode card(long runId) {
        return source.render(runId, 1).orElseThrow(() -> new AssertionError("运行 " + runId + " 应当渲染出卡片"));
    }

    private long seedRun(
            String suffix, String status, int problems, int shipped, String pullJson, String shipJson) {
        return seedRunRaw(suffix, status, problems, shipped, pullJson, shipJson);
    }

    private long seedRunRaw(
            String suffix, String status, int problems, int shipped, String pullJson, String shipJson) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.scheduled_pull_runs
                    (run_key, slot, run_date, status, pull_summary, ship_summary,
                     problem_count, shipped_batches, lock_version, finished_at)
                VALUES ('2026-08-28:MORNING:' || ?, 'MORNING', DATE '2026-08-28', ?, ?::jsonb, ?::jsonb,
                        ?, ?, 1, now())
                RETURNING id
                """,
                Long.class, suffix, status, pullJson, shipJson, problems, shipped);
    }

    private long seedRunning(String suffix) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.scheduled_pull_runs (run_key, slot, run_date, status, problem_count)
                VALUES ('2026-08-28:EVENING:' || ?, 'EVENING', DATE '2026-08-28', 'RUNNING', 5)
                RETURNING id
                """,
                Long.class, suffix);
    }
}
