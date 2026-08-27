package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 生产迁移历史兼容门禁（组合基线 f0bdb65 = #84 commit 00c0525 + 部署兼容修复 0058936）。
 *
 * <p>真实库已按原编号应用 V40（add_wangqi_source_bundle_mappings）、V41
 * （add_source_attribution_corrections）、V42（add_wanqi_52_source_channel）、V43
 * （mixed_provider_static_bundle_partitions）；commit 10bd599 曾把它们整体改号为
 * V41–V44 并把 V40 让给 widen_async_task_payload，直接部署必然触发 Flyway
 * checksum/description/version 冲突。生产数据库历史不得 repair/改写，正确原则是
 * 「已发布版本号不可改名，新增迁移只可追加」。
 *
 * <p>本测试把该原则固化为门禁：① 先只迁移到 V47（模拟当前真实库）；② 再用完整当前
 * migration set（V1..V64）升级，Flyway validate（默认开启）必须成功且只追加
 * V48（internal_operators，Issue #89）、V49（企微导出 delivery 代际栅栏，Issue #84）、
 * V50（中汇稳定上传意图，Issue #116）、V51（企微业务通知 outbox，Issue #90）与
 * V52（企微订单草稿卡片，Issues #87/#88）、V53（礼包组件删除保护）与 V54（Shipment 来源同步状态机）与 V55（通用业务卡投递，#87/#88）与 V56（履约单据 Agent）；
 * ③ 升级后前 47 行历史
 * 逐行不变，V40–V47 的 version/script/description/checksum 必须与生产已应用序列逐字节
 * 一致——checksum 常量直接取自生产 `flyway_schema_history` 真实行（不按当前迁移文件
 * 重新计算），任何未来的改号/改内容都会让本测试变红。不读真实库、不依赖 mock schema，
 * 纯 Testcontainers + Flyway 现有接缝。
 *
 * <p>冻结范围：本测试钉死 V40–V47 八行生产历史常量（version/script/description/checksum）；
 * V1–V39 不在本测试冻结范围内，它们由 SchemaSnapshotMigrationEquivalenceTest
 * （docs/schema.sql 空库快照与 Flyway 全链结构等价）与「已发布迁移不可变」约定兜底——
 * 前者证明结构等价，后者约束历史不可改写，均非本测试的断言职责。
 *
 * <p>演进约定：未来追加迁移时，把阶段一的模拟目标（当前 47）推进到当时生产所处版本、
 * 同步更新阶段二「只追加」断言——但 V40–V47 常量段是生产不可变序列，永远不得改动；
 * 该段变红即意味着有人再次改号或改了已发布内容。
 */
@Testcontainers
class ProductionMigrationHistoryCompatTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * 生产已应用序列：V40–V47 的 (version, script, description, checksum)。
     *
     * <p>checksum 为 Flyway 11 的 SQL 迁移校验和——逐行读取（剔除行终结符、过滤 BOM）后对
     * 每行 UTF-8 字节做 CRC32（与 flyway-core 11.7.2 ChecksumCalculator 逐字节一致，见
     * {@link #crc32Of(String)}）。常量直接取自生产 `flyway_schema_history` 真实行（V44–V47
     * 为 2026-08-21 从 zimu-fulfillment-postgres-1 读取的权威事实，V40–V43 与生产已应用
     * 序列一致），不按当前迁移文件重新计算——任何未来的改号/改内容都会让校验和偏离常量，
     * 本测试立即变红。
     */
    private static final HistoryRow V40_PRODUCTION = new HistoryRow(
            "40", "V40__add_wangqi_source_bundle_mappings.sql",
            "add wangqi source bundle mappings", 3161793612L);
    private static final HistoryRow V41_PRODUCTION = new HistoryRow(
            "41", "V41__add_source_attribution_corrections.sql",
            "add source attribution corrections", 2537126704L);
    private static final HistoryRow V42_PRODUCTION = new HistoryRow(
            "42", "V42__add_wanqi_52_source_channel.sql",
            "add wanqi 52 source channel", 470701147L);
    private static final HistoryRow V43_PRODUCTION = new HistoryRow(
            "43", "V43__mixed_provider_static_bundle_partitions.sql",
            "mixed provider static bundle partitions", 1023805107L);
    private static final HistoryRow V44_PRODUCTION = new HistoryRow(
            "44", "V44__widen_async_task_payload.sql",
            "widen async task payload", 1904086128L);
    private static final HistoryRow V45_PRODUCTION = new HistoryRow(
            "45", "V45__procurement_price_excluded_candidates.sql",
            "procurement price excluded candidates", 3249626052L);
    private static final HistoryRow V46_PRODUCTION = new HistoryRow(
            "46", "V46__wecom_export_outbound_send.sql",
            "wecom export outbound send", 3215994199L);
    private static final HistoryRow V47_PRODUCTION = new HistoryRow(
            "47", "V47__wecom_export_alert_scoping.sql",
            "wecom export alert scoping", 3193798455L);

    @Test
    void v47DatabaseUpgradesByAppendingOnlyV48ThroughV66() throws Exception {
        // 阶段一：模拟当前真实库——只迁移到 V47（V40–V47 与生产已应用历史逐字节一致）。
        flyway(MigrationVersion.fromVersion("47")).migrate();

        List<HistoryRow> historyBefore = readHistory();
        assertThat(historyBefore)
                .as("模拟真实库：迁移到 V47 后应恰有 47 条历史")
                .hasSize(47);
        assertThat(historyBefore.subList(39, 47))
                .as("V40–V47 必须保持生产已应用序列（version/script/description/checksum 不可再被改号/改内容）")
                .containsExactly(
                        V40_PRODUCTION, V41_PRODUCTION, V42_PRODUCTION, V43_PRODUCTION,
                        V44_PRODUCTION, V45_PRODUCTION, V46_PRODUCTION, V47_PRODUCTION);

        seedV47MultiGenerationDeliveryHistory();

        // 阶段二：完整当前 migration set（V1..V66）升级——Flyway validate 默认开启，
        // V40–V47 校验通过后只追加 V48–V66，任何 repair/改写历史都会在此失败。
        flyway(null).migrate();

        List<HistoryRow> historyAfter = readHistory();
        assertThat(historyAfter)
                .as("完整升级后应恰有 66 条历史")
                .hasSize(66);
        assertThat(historyAfter.subList(0, 47))
                .as("完整升级不得改写/repair 任何已应用历史")
                .isEqualTo(historyBefore);
        // V48–V66 按当前文件计算校验和，与 Flyway 阶段二
        // 真实写入 flyway_schema_history 的校验和互证（前 47 行 isEqualTo(historyBefore) 已保证
        // V40–V47 未被改写）。
        assertThat(historyAfter.subList(47, 66))
                .as("升级只追加 V48–V66，其中 V58 保留已上线复核认领，V65 冻结结构化执行意图，V66 新增来源订单附件任务")
                .containsExactly(
                        new HistoryRow("48", "V48__internal_operators.sql",
                                "internal operators",
                                crc32Of("V48__internal_operators.sql")),
                        new HistoryRow("49", "V49__wecom_export_delivery_generation_fencing.sql",
                                "wecom export delivery generation fencing",
                                crc32Of("V49__wecom_export_delivery_generation_fencing.sql")),
                        new HistoryRow("50", "V50__zhonghui_pms_stable_upload_intent.sql",
                                "zhonghui pms stable upload intent",
                                crc32Of("V50__zhonghui_pms_stable_upload_intent.sql")),
                        new HistoryRow("51", "V51__wecom_business_notification_outbox.sql",
                                "wecom business notification outbox",
                                crc32Of("V51__wecom_business_notification_outbox.sql")),
                        new HistoryRow("52", "V52__wecom_order_draft_cards.sql",
                                "wecom order draft cards",
                                crc32Of("V52__wecom_order_draft_cards.sql")),
                        new HistoryRow("53", "V53__protect_static_bundle_item_deletes.sql",
                                "protect static bundle item deletes",
                                crc32Of("V53__protect_static_bundle_item_deletes.sql")),
                        new HistoryRow("54", "V54__shipment_source_sync.sql",
                                "shipment source sync",
                                crc32Of("V54__shipment_source_sync.sql")),
                        new HistoryRow("55", "V55__wecom_business_cards.sql",
                                "wecom business cards",
                                crc32Of("V55__wecom_business_cards.sql")),
                        new HistoryRow("56", "V56__fulfillment_file_agent.sql",
                                "fulfillment file agent",
                                crc32Of("V56__fulfillment_file_agent.sql")),
                        new HistoryRow("57", "V57__source_return_wecom_delivery.sql",
                                "source return wecom delivery",
                                crc32Of("V57__source_return_wecom_delivery.sql")),
                        new HistoryRow("58", "V58__review_case_claim.sql",
                                "review case claim",
                                crc32Of("V58__review_case_claim.sql")),
                        new HistoryRow("59", "V59__business_followups.sql",
                                "business followups",
                                crc32Of("V59__business_followups.sql")),
                        new HistoryRow("60", "V60__kehuzx_followup_drafts.sql",
                                "kehuzx followup drafts",
                                crc32Of("V60__kehuzx_followup_drafts.sql")),
                        new HistoryRow("61", "V61__business_followup_approvals.sql",
                                "business followup approvals",
                                crc32Of("V61__business_followup_approvals.sql")),
                        new HistoryRow("62", "V62__business_followup_assignments.sql",
                                "business followup assignments",
                                crc32Of("V62__business_followup_assignments.sql")),
                        new HistoryRow("63", "V63__kehuzx_customer_assignment_trace.sql",
                                "kehuzx customer assignment trace",
                                crc32Of("V63__kehuzx_customer_assignment_trace.sql")),
                        new HistoryRow("64", "V64__kehuzx_customer_create_assignment.sql",
                                "kehuzx customer create assignment",
                                crc32Of("V64__kehuzx_customer_create_assignment.sql")),
                        new HistoryRow("65", "V65__business_followup_execution_intent.sql",
                                "business followup execution intent",
                                crc32Of("V65__business_followup_execution_intent.sql")),
                        new HistoryRow("66", "V66__source_order_intake_jobs.sql",
                                "source order intake jobs",
                                crc32Of("V66__source_order_intake_jobs.sql")));

        // 结构事实：V44/V45 沿用既有断言；V46/V47 用真实结构（非仅同文件 crc）证明生效；
        // 后续断言覆盖内部运营人员、delivery 代际、中汇稳定意图、业务通知、草稿卡片、
        // Shipment 来源同步状态机与客户跟进结构；完整 V48–V66 顺序由上方历史断言锁定。
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            // V44：async_tasks.payload_ref 已为 text。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'app' AND table_name = 'async_tasks' AND column_name = 'payload_ref'
                    """)))
                    .as("V44 生效后 async_tasks.payload_ref 必须为 text")
                    .isEqualTo("text");
            // V45：procurement-price-agent v1 退役、v2 生效、冻结 12 例 CONFIRMED 评测用例。
            assertThat(single(statement.executeQuery(
                    "SELECT status FROM app.agent_definitions "
                            + "WHERE agent_slug='procurement-price-agent' AND version=1")))
                    .as("V45：procurement-price-agent v1 已退役（前向转移）")
                    .isEqualTo("retired");
            assertThat(single(statement.executeQuery(
                    "SELECT status FROM app.agent_definitions "
                            + "WHERE agent_slug='procurement-price-agent' AND version=2")))
                    .as("V45：procurement-price-agent v2 为当前生效版本")
                    .isEqualTo("active");
            assertThat(single(statement.executeQuery(
                    "SELECT count(*) FROM app.agent_eval_cases "
                            + "WHERE agent_slug='procurement-price-agent' AND agent_version=2 "
                            + "AND status='CONFIRMED'")))
                    .as("V45：procurement-price-agent v2 冻结 12 例 CONFIRMED 评测用例")
                    .isEqualTo("12");
            // V46：两张企微导出表必须存在（状态表 + delivery 证据表）。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'app'
                      AND table_name IN ('fulfillment_export_wecom_states',
                                         'fulfillment_export_wecom_deliveries')
                    """)))
                    .as("V46 生效后两张企微导出表必须存在")
                    .isEqualTo("2");
            // V47：两个 active unique indexes 必须存在、唯一、有效且带隔离谓词；
            // 定义来自 pg_get_indexdef，等价于 SQL 文件中的定义（非仅同文件 crc 自证）。
            assertPartialUniqueIndex(statement, "uq_operational_alert_active_subject",
                    "V47：非企微导出告警仍按 (alert_type, order/order_line/fulfillment/shipment) 唯一，"
                            + "谓词排除（<>）企微导出类型",
                    "CREATE UNIQUE INDEX",
                    "COALESCE(order_id", "COALESCE(order_line_id",
                    "COALESCE(fulfillment_id", "COALESCE(shipment_id",
                    "<> 'FULFILLMENT_EXPORT_WECOM'", "'OPEN'", "'ACKNOWLEDGED'");
            assertPartialUniqueIndex(statement, "uq_operational_alert_active_wecom_export",
                    "V47：企微导出告警按 (alert_type, shipment_id, detail->>'export_id') 唯一，"
                            + "谓词限定（=）企微导出类型且只锁 OPEN/ACKNOWLEDGED",
                    "CREATE UNIQUE INDEX", "COALESCE(shipment_id", "export_id",
                    "= 'FULFILLMENT_EXPORT_WECOM'", "'OPEN'", "'ACKNOWLEDGED'");
            // V48：内部运营人员表（Issue #89）必须存在且列齐备；企微 userid 唯一索引必须为
            // active 的唯一 partial index（未绑定行为 NULL，不互相冲突）。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'app' AND table_name = 'internal_operators'
                      AND column_name IN ('id', 'display_name', 'responsible_team',
                                          'wecom_userid', 'active', 'lock_version',
                                          'created_at', 'updated_at')
                    """)))
                    .as("V48 生效后 internal_operators 必须包含全部 8 个字段")
                    .isEqualTo("8");
            assertPartialUniqueIndex(statement, "uq_internal_operators_wecom_userid",
                    "V48：企微 userid 唯一索引必须是 active 的唯一 partial index（未绑定不冲突）",
                    "CREATE UNIQUE INDEX", "wecom_userid", "IS NOT NULL");
            assertIndex(statement, "idx_internal_operators_team_active",
                    "V48：责任团队 + 启用状态索引支撑解析查询",
                    "responsible_team", "active");
            // V49：delivery 必须带非空代际，状态约束必须接受 SUPERSEDED。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'app'
                      AND table_name = 'fulfillment_export_wecom_deliveries'
                      AND column_name = 'initial_generation'
                    """)))
                    .as("V49 生效后 initial_generation 必须非空")
                    .isEqualTo("NO");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conname = 'fulfillment_export_wecom_deliveries_status_check'
                    """)))
                    .as("V49 生效后 delivery 状态约束必须允许 SUPERSEDED")
                    .contains("SUPERSEDED");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT initial_generation
                    FROM app.fulfillment_export_wecom_deliveries
                    WHERE id = 900002
                    """)))
                    .as("V49 必须把存量 reminder 绑定到其创建时已有的 INITIAL 代际，而非升级时最新代际")
                    .isEqualTo("1");
            // V50：同一个 HTTP 幂等键只能绑定一个中汇上传批次，且存量批次已完成非空回填。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'app'
                      AND table_name = 'zhonghui_pms_upload_batches'
                      AND column_name = 'idempotency_key'
                    """)))
                    .as("V50 生效后中汇批次 idempotency_key 必须非空")
                    .isEqualTo("NO");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM pg_constraint con
                    JOIN pg_class rel ON rel.oid = con.conrelid
                    JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                    WHERE ns.nspname = 'app'
                      AND rel.relname = 'zhonghui_pms_upload_batches'
                      AND con.contype = 'u'
                      AND pg_get_constraintdef(con.oid) LIKE 'UNIQUE (idempotency_key)%'
                    """)))
                    .as("V50 生效后同幂等键只能绑定一个中汇上传批次")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM pg_constraint con
                    JOIN pg_class rel ON rel.oid = con.conrelid
                    JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                    WHERE ns.nspname = 'app'
                      AND rel.relname = 'zhonghui_pms_upload_batch_items'
                      AND con.contype = 'u'
                      AND pg_get_constraintdef(con.oid) LIKE 'UNIQUE (batch_id, sku_id)%'
                    """)))
                    .as("V50 生效后同一中汇批次内每个 SKU 只能有一条外部写事实")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    "SELECT idempotency_key FROM app.zhonghui_pms_upload_batches WHERE id = 900004")))
                    .as("V50 必须为存量中汇批次生成稳定、非空且不会冒充真实 HTTP key 的引用")
                    .isEqualTo("legacy-zhonghui-batch-900004");
            // V51：业务通知事实、批次、逐收件人 fence 与持久运营告警投影四张表齐备。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema='app' AND table_name IN (
                        'wecom_notification_batches', 'wecom_notification_items',
                        'wecom_notification_deliveries', 'wecom_notification_alerts')
                    """)))
                    .as("V51 必须完整创建通知 outbox、delivery fence 与 durable alert projection")
                    .isEqualTo("4");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.triggers
                    WHERE trigger_schema='app' AND trigger_name IN (
                        'trg_review_case_wecom_notification', 'trg_order_event_wecom_notification')
                    """)))
                    .as("V51 必须在复核事项与订单事件事务内捕获通知事实")
                    .isEqualTo("2");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM pg_catalog.pg_constraint
                    WHERE connamespace='app'::regnamespace
                      AND conrelid='app.wecom_notification_alerts'::regclass
                      AND contype='u'
                    """)))
                    .as("V51 告警投影必须同时按 alert_key 与 delivery/item 稳定去重")
                    .isEqualTo("2");
            // V52：草稿结账事实、卡片 outbox、事件 claim/update/fallback 观测与首次事实保护齐备。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='order_drafts'
                      AND column_name='settlement_time'
                    """)))
                    .as("V52 必须给订单草稿增加 settlement_time 事实")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema='app' AND table_name='wecom_order_draft_cards'
                    """)))
                    .as("V52 必须创建持久化企微订单草稿卡片 outbox")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='wecom_events'
                      AND column_name IN ('processing_claim_token', 'processing_attempt',
                                          'update_status', 'fallback_status', 'order_draft_id')
                    """)))
                    .as("V52 必须保存 fenced attempt 与 update/fallback 独立观测")
                    .isEqualTo("5");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='wecom_order_draft_cards'
                      AND column_name IN ('route_type', 'chat_id')
                    """)))
                    .as("V52 必须同时保存发送路由类型与目标，防止 single/group 标识碰撞")
                    .isEqualTo("2");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.triggers
                    WHERE trigger_schema='app'
                      AND trigger_name='trg_wecom_card_event_first_facts'
                    """)))
                    .as("V52 必须以数据库触发器保护卡片事件首次事实")
                    .isEqualTo("1");
            // V53：复用 shipment_syncs，持久化可重放意图并以双向 Shipment 锁隔离在线/文件回传。
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='shipment_syncs'
                      AND column_name IN (
                          'intent_key', 'platform_intent_key', 'check_hash', 'artifact_hash',
                          'source_line_ref', 'carrier_code', 'tracking_number', 'intent_started_at',
                          'effect_started_at', 'lock_version')
                    """)))
                    .as("V53 必须完整持久化 Shipment 来源回传意图、外部 effect 栅栏与 CAS 版本")
                    .isEqualTo("10");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid='app.shipment_syncs'::regclass
                      AND conname='shipment_syncs_sync_status_check'
                    """)))
                    .as("V53 必须让 shipment_syncs 覆盖完整来源同步状态机")
                    .contains("PENDING", "SYNCING", "SYNCED", "SYNC_FAILED", "RECONCILIATION_REQUIRED");
            assertThat(single(statement.executeQuery(
                    "SELECT sync_status FROM app.shipment_syncs WHERE id=900005")))
                    .as("V53 扩展状态与列时必须保留存量 Shipment 来源同步事实")
                    .isEqualTo("PENDING");
            assertThat(single(statement.executeQuery(
                    "SELECT lock_version FROM app.shipment_syncs WHERE id=900005")))
                    .as("存量 projection 必须获得可 CAS 的初始版本")
                    .isEqualTo("0");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema='app' AND table_name LIKE 'shipment_sync%'
                    """)))
                    .as("V53 只扩展既有 shipment_syncs，不创建重复来源同步表")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(DISTINCT trigger_name) FROM information_schema.triggers
                    WHERE trigger_schema='app' AND trigger_name IN (
                        'trg_shipment_source_sync_mutex',
                        'trg_source_return_export_push_mutex',
                        'trg_source_return_export_append_only')
                    """)))
                    .as("V53 必须同时守住在线 claim、文件 fallback claim 与文件窄更新")
                    .isEqualTo("3");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM app.agent_definitions
                    WHERE agent_slug='source-sync-reviewer' AND version=1 AND status='active'
                      AND enabled AND NOT allow_write
                      AND guard_exemptions='[]'::jsonb
                      AND tool_whitelist='["check_shipment_source_sync"]'::jsonb
                    """)))
                    .as("V53 必须只播种一个无 PII 豁免、无写权限的建议型 reviewer")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema='app' AND table_name IN (
                        'kehuzx_read_evidence', 'kehuzx_read_failures',
                        'business_followup_draft_versions')
                    """)))
                    .as("V60 必须同时持久化远端读取证据与版本化草稿")
                    .isEqualTo("3");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM app.agent_definitions
                    WHERE agent_slug='customer-followup-agent' AND version=1
                      AND status='active' AND enabled AND NOT allow_write
                      AND tool_whitelist <@ '[
                        "kehuzx_search_customers", "kehuzx_get_customer_detail",
                        "kehuzx_search_demands", "kehuzx_search_orders",
                        "kehuzx_get_order_detail"
                      ]'::jsonb
                      AND jsonb_array_length(tool_whitelist)=5
                    """)))
                    .as("V60 客户跟进 Agent 只能看到五个命名空间化 Kehuzx 只读工具")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='business_followups'
                      AND column_name IN (
                        'designated_reviewer_operator_id',
                        'current_confirmed_draft_version'
                      )
                    """)))
                    .as("V61 必须把指定 +1 与已确认草稿版本作为可约束事实")
                    .isEqualTo("2");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema='app' AND table_name='business_followup_approvals'
                    """)))
                    .as("V61 必须持久化绑定草稿版本的人工决定")
                    .isEqualTo("1");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='business_followup_approvals'
                      AND column_name IN (
                        'application_status', 'application_failure_code', 'applied_at'
                      )
                    """)))
                    .as("V61 必须单独持久化 Approval 的应用结果")
                    .isEqualTo("3");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='wecom_events'
                      AND column_name IN (
                        'business_followup_id', 'business_followup_draft_version',
                        'business_followup_approval_id'
                      )
                    """)))
                    .as("V61 企微事件必须串联跟进、草稿版本与 Approval")
                    .isEqualTo("3");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='business_followup_assignments'
                      AND column_name IN (
                        'followup_id', 'draft_version', 'approval_id', 'agent_run_id',
                        'task_type', 'logical_target', 'assignee_type', 'assignee_ref',
                        'status', 'due_at', 'priority', 'idempotency_key',
                        'execution_task_key', 'request_id', 'external_entity_type',
                        'external_entity_id', 'result_code'
                      )
                    """)))
                    .as("V62 必须持久化可追溯且可独立执行的 Assignment 契约")
                    .isEqualTo("17");
            assertThat(single(statement.executeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='app' AND table_name='business_followup_assignments'
                      AND column_name='payload_hash'
                    """)))
                    .as("V63 必须持久化确定性 Kehuzx payload hash")
                    .isEqualTo("1");
        }
    }

    @Test
    void v61MigratesProtocolTaskIdsAndFencesApprovalFacts() throws Exception {
        String database = "v61_business_followup_approvals";
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String databaseUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getFirstMappedPort() + "/" + database;
        flyway(databaseUrl, MigrationVersion.fromVersion("60")).migrate();

        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO app.wecom_business_cards
                        (id, card_domain, entity_id, entity_version, task_id, route_type, chat_id,
                         status, request_id, acknowledged_at)
                    VALUES
                        (960001, 'review', 42, 0, 'review:42:v0', 'SINGLE', 'operator-chat',
                         'PENDING', NULL, NULL),
                        (960008, 'review', 45, 0, 'review:45:v0', 'SINGLE', 'operator-chat',
                         'SENT', 'sent-request', CURRENT_TIMESTAMP)
                    """);
            statement.execute("SET session_replication_role = replica");
            try {
                statement.executeUpdate(
                        """
                        INSERT INTO app.business_followups
                            (id, followup_no, message_submission_id, employee_draft, created_by,
                             stage, processing_status, current_draft_version)
                        VALUES
                            (960002, 'BF-0000960002', 960003, '请跟进 KH-260826-001',
                             'operator-a', 'DRAFT_READY', 'SUCCEEDED', 1)
                        """);
                statement.executeUpdate(
                        """
                        INSERT INTO app.business_followup_draft_versions
                            (followup_id, version, source_revision, status, agent_run_id,
                             agent_slug, agent_version, content, zimu_source_summary,
                             kehuzx_source_summary)
                        VALUES
                            (960002, 1, 1, 'DRAFT', 'run-v60-migration',
                             'customer-followup-agent', 1, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)
                        """);
                statement.executeUpdate(
                        """
                        INSERT INTO app.order_drafts
                            (id, draft_no, submission_id, source_order_no, status, revision)
                        VALUES
                            (960004, 'OD-V60-TASK-ID', 960003, 'SOURCE-V60-TASK-ID', 'OPEN', 7),
                            (960006, 'OD-V60-SENT-ID', 960003, 'SOURCE-V60-SENT-ID', 'OPEN', 3)
                        """);
                statement.executeUpdate(
                        """
                        INSERT INTO app.wecom_order_draft_cards
                            (id, order_draft_id, draft_revision, task_id, route_type, chat_id,
                             status, request_id, acknowledged_at)
                        VALUES
                            (960005, 960004, 7, 'order-draft:960004', 'SINGLE', 'operator-chat',
                             'PENDING', NULL, NULL),
                            (960007, 960006, 3, 'order-draft:960006', 'SINGLE', 'operator-chat',
                             'SENT', 'sent-order-request', CURRENT_TIMESTAMP)
                        """);
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }

        flyway(databaseUrl, null).migrate();

        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(single(statement.executeQuery(
                    "SELECT task_id FROM app.wecom_business_cards WHERE id=960001")))
                    .as("明确未触网的存量通用卡必须换成随机授权 task_id")
                    .matches("review_42_v0_[0-9a-f]{32}");
            assertThat(single(statement.executeQuery(
                    "SELECT task_id FROM app.wecom_business_cards WHERE id=960008")))
                    .as("已外发通用卡的 correlation id 必须原样保留")
                    .isEqualTo("review:45:v0");
            assertThat(single(statement.executeQuery(
                    "SELECT task_id FROM app.wecom_order_draft_cards WHERE id=960005")))
                    .as("明确未触网的存量订单草稿卡必须换成随机授权 task_id")
                    .matches("order-draft_960004_v7_[0-9a-f]{32}");
            assertThat(single(statement.executeQuery(
                    "SELECT task_id FROM app.wecom_order_draft_cards WHERE id=960007")))
                    .as("已外发订单草稿卡的 correlation id 必须原样保留")
                    .isEqualTo("order-draft:960006");
            assertThat(single(statement.executeQuery(
                    "SELECT status FROM app.business_followup_draft_versions "
                            + "WHERE followup_id=960002 AND version=1")))
                    .as("存量 DRAFT 必须前向迁移为 READY，不覆盖版本内容")
                    .isEqualTo("READY");

            assertThatThrownBy(() -> statement.executeUpdate(
                    """
                    INSERT INTO app.wecom_business_cards
                        (card_domain, entity_id, entity_version, task_id, route_type, chat_id)
                    VALUES ('review', 43, 0, 'review:43:v0', 'SINGLE', 'operator-chat')
                    """))
                    .as("新冒号 task_id 必须被精确约束拒绝")
                    .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    """
                    INSERT INTO app.wecom_business_cards
                        (card_domain, entity_id, entity_version, task_id, route_type, chat_id)
                    VALUES ('review', 44, 0, 'review_44_v0_extra', 'SINGLE', 'operator-chat')
                    """))
                    .as("下划线 task_id 也必须完整匹配 domain/id/marker 契约")
                    .isInstanceOf(Exception.class);
            statement.executeUpdate(
                    """
                    INSERT INTO app.wecom_business_cards
                        (card_domain, entity_id, entity_version, task_id, route_type, chat_id)
                    VALUES (
                        'review', 46, 0,
                        'review_46_v0_0123456789abcdef0123456789abcdef',
                        'SINGLE', 'operator-chat'
                    )
                    """);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE app.wecom_order_draft_cards SET task_id='order-draft:960004' WHERE id=960005"))
                    .as("订单草稿卡不能重新写入含冒号的 task_id")
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void v50RejectsHistoricalDuplicateSkuFactsWithoutDeletingExternalWriteEvidence() throws Exception {
        String database = "v50_zhonghui_duplicate_guard";
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String databaseUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getFirstMappedPort() + "/" + database;
        flyway(databaseUrl, MigrationVersion.fromVersion("49")).migrate();
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO app.zhonghui_pms_upload_batches
                        (id, batch_no, status, total, succeeded, failed, created_by)
                    VALUES (900005, 'PMS-DUPLICATE-900005', 'PENDING', 2, 0, 0, 'migration-test')
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO app.zhonghui_pms_upload_batch_items
                        (batch_id, sku_id, status)
                    VALUES (900005, 700001, 'PENDING'), (900005, 700001, 'FAILED')
                    """);
        }

        assertThatThrownBy(() -> flyway(databaseUrl, null).migrate())
                .hasStackTraceContaining(
                        "V50 blocked: duplicate Zhonghui batch SKU facts require manual reconciliation");

        try (Connection connection = DriverManager.getConnection(
                databaseUrl, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            assertThat(single(statement.executeQuery(
                    "SELECT count(*) FROM app.zhonghui_pms_upload_batch_items "
                            + "WHERE batch_id = 900005 AND sku_id = 700001")))
                    .as("V50 拒绝升级时不得静默删除任何可能对应外部写的历史证据")
                    .isEqualTo("2");
        }
    }

    /**
     * 在 V47 形状中种入「gen1 initial → gen1 reminder → gen2 initial」历史和一个存量中汇批次。
     * 外键父表不属于本迁移门禁关注点，因此仅在当前连接关闭 FK trigger 后写入最小 delivery 事实；CHECK 约束
     * 仍正常执行。gen2 故意使用更早的 created_at（模拟长事务时间反序），V49 必须只按 identity
     * id 顺序恢复 reminder 插入时的代际 1；V50 必须为中汇存量行回填稳定 legacy 引用。
     */
    private void seedV47MultiGenerationDeliveryHistory() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                statement.executeUpdate(
                        """
                        INSERT INTO app.fulfillment_export_wecom_deliveries
                            (id, export_id, kind, sequence, status, stage, request_id, ack_sent_at,
                             created_at, updated_at)
                        VALUES
                            (900001, 9000, 'INITIAL', 1, 'SENT', 'FINALIZED', 'req-gen1',
                             TIMESTAMPTZ '2026-08-20 01:00:00+08',
                             TIMESTAMPTZ '2026-08-20 01:00:00+08',
                             TIMESTAMPTZ '2026-08-20 01:00:00+08'),
                            (900002, 9000, 'REMINDER', 1, 'SENDING', 'SEND', NULL, NULL,
                             TIMESTAMPTZ '2026-08-20 02:00:00+08',
                             TIMESTAMPTZ '2026-08-20 02:00:00+08'),
                            (900003, 9000, 'INITIAL', 2, 'SENT', 'FINALIZED', 'req-gen2',
                             TIMESTAMPTZ '2026-08-20 00:30:00+08',
                             TIMESTAMPTZ '2026-08-20 00:30:00+08',
                             TIMESTAMPTZ '2026-08-20 00:30:00+08')
                        """);
                statement.executeUpdate(
                        """
                        INSERT INTO app.zhonghui_pms_upload_batches
                            (id, batch_no, status, total, succeeded, failed, created_by)
                        VALUES
                            (900004, 'PMS-LEGACY-900004', 'PENDING', 1, 0, 0, 'migration-test')
                        """);
                statement.executeUpdate(
                        """
                        INSERT INTO app.shipment_syncs
                            (id, shipment_id, source_channel, sync_status, attempt_count)
                        VALUES
                            (900005, 900006, 'JUFUBAO', 'PENDING', 0)
                        """);
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
    }

    /**
     * BOM 定向测试：UTF-8 BOM 解码为首字符 U+FEFF，必须按 Flyway BomFilter 语义剔除、
     * 不参与校验和；空内容/仅 BOM 内容校验和为 0。
     */
    @Test
    void crc32OfMatchesFlywayBomFilterSemantics() {
        String sql = "SELECT 1;\nSELECT 2;\r\n";
        assertThat(crc32OfLines("\uFEFF" + sql))
                .as("首行 BOM 必须被剔除，不影响校验和")
                .isEqualTo(crc32OfLines(sql));
        assertThat(crc32OfLines("")).isZero();
        assertThat(crc32OfLines("\uFEFF")).isZero();
    }

    // ------------------------------------------------------------------
    // 历史行提取与工具
    // ------------------------------------------------------------------

    private record HistoryRow(String version, String script, String description, long checksum) {}

    private static List<HistoryRow> readHistory() throws Exception {
        List<HistoryRow> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        """
                        SELECT version, script, description, checksum
                        FROM flyway_schema_history
                        ORDER BY installed_rank
                        """)) {
            while (result.next()) {
                rows.add(new HistoryRow(
                        result.getString("version"),
                        result.getString("script"),
                        result.getString("description"),
                        Integer.toUnsignedLong(result.getInt("checksum"))));
            }
        }
        return rows;
    }

    /**
     * 复刻 Flyway 11（flyway-core 11.7.2）ChecksumCalculator 的 SQL 迁移校验和：
     * BufferedReader 逐行读取（剔除行终结符），每行经 BomFilter 语义剔除首字符 BOM 后，
     * 对 UTF-8 字节累加 CRC32。本迁移集无占位符、无内容改写，故结果与 Flyway 逐字节一致；
     * 且阶段二断言用 Flyway 真实写入 flyway_schema_history 的校验和与之互证。
     */
    private static long crc32Of(String migrationFile) throws Exception {
        return crc32OfLines(Files.readString(
                Path.of("src", "main", "resources", "db", "migration", migrationFile)));
    }

    /** 逐行 CRC32 核心；每行按 Flyway BomFilter.FilterBomFromString 语义剔除首字符 BOM。 */
    private static long crc32OfLines(String content) {
        CRC32 crc32 = new CRC32();
        for (String line : content.split("\\R", -1)) {
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }
            crc32.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return crc32.getValue();
    }

    private static Flyway flyway(MigrationVersion target) {
        return flyway(postgres.getJdbcUrl(), target);
    }

    private static Flyway flyway(String jdbcUrl, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String single(ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).as("查询应恰好返回一行").isFalse();
            return value;
        }
    }

    private record IndexState(String definition, boolean unique, boolean valid, boolean hasPredicate) {}

    private static IndexState indexState(Statement statement, String indexName) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT pg_get_indexdef(i.indexrelid), i.indisunique, i.indisvalid, "
                        + "i.indpred IS NOT NULL "
                        + "FROM pg_catalog.pg_index i "
                        + "JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'app' AND c.relname = '" + indexName + "'")) {
            assertThat(result.next()).as("索引 %s 必须存在", indexName).isTrue();
            IndexState state = new IndexState(
                    result.getString(1),
                    result.getBoolean(2),
                    result.getBoolean(3),
                    result.getBoolean(4));
            assertThat(result.next()).as("索引 %s 查询应恰好返回一行", indexName).isFalse();
            return state;
        }
    }

    /** 断言某索引为 active 的唯一 partial index，且其定义包含给定谓词/列片段。 */
    private static void assertPartialUniqueIndex(
            Statement statement, String indexName, String factLabel, String... definitionFragments)
            throws Exception {
        IndexState state = indexState(statement, indexName);
        assertThat(state.unique())
                .as(factLabel + "：%s 必须为唯一索引", indexName)
                .isTrue();
        assertThat(state.valid())
                .as(factLabel + "：%s 必须 active（indisvalid）", indexName)
                .isTrue();
        assertThat(state.hasPredicate())
                .as(factLabel + "：%s 必须为 partial index", indexName)
                .isTrue();
        assertThat(state.definition())
                .as(factLabel)
                .contains(definitionFragments);
    }

    /** 断言某索引存在且 active（非唯一普通索引），定义包含给定列片段。 */
    private static void assertIndex(
            Statement statement, String indexName, String factLabel, String... definitionFragments)
            throws Exception {
        IndexState state = indexState(statement, indexName);
        assertThat(state.valid())
                .as(factLabel + "：%s 必须 active（indisvalid）", indexName)
                .isTrue();
        assertThat(state.definition())
                .as(factLabel)
                .contains(definitionFragments);
    }
}
