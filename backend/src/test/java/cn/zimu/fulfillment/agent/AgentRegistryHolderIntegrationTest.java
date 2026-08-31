package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * meta-agent-platform-impl 票 02：注册表 DB 真源 + {@link AgentRegistryHolder} 换实例验收。
 *
 * <p>真实 PostgreSQL + 完整应用启动后，测试内直接改 {@code agent_definitions} 行模拟
 * 「草稿确认」：v1 retired + v2 active。断言 {@code holder.reload()} 后运行路径（holder
 * 当前实例）无需重启即感知新版本，运行条件 {@code status='active' AND enabled=true} 判定
 * 正确，且 {@link AgentRegistryChangeAuditor} 对版本生命周期产生 ACTIVATED/RETIRED 审计
 * （复用既有 agent.registry.changed 通道）。
 *
 * <p>三个测试共享一个启动上下文与同一数据库，状态变更按 {@link Order} 串行推进
 * （1 启动态 → 2 版本确认 → 3 启停正交），避免相互污染。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentRegistryHolderIntegrationTest extends AgentTestcontainersBase {

    private static final String DATA_QUERY_WHITELIST_JSON =
            "[\"list_procurement_tickets\",\"get_procurement_ticket\",\"list_procurement_receipts\","
                    + "\"search_skus\",\"get_sku\",\"list_provider_skus\",\"get_inventory_overview\","
                    + "\"get_inventory_detail\",\"list_products\",\"list_categories\","
                    + "\"list_fulfillment_providers\",\"list_interpretations\",\"list_message_media\"]";

    private static AgentRegistryHolder holder;

    @BeforeAll
    static void resolveHolder() {
        holder = context.getBean(AgentRegistryHolder.class);
    }

    @Test
    @Order(1)
    void startupLoadsActiveDefinitionsFromDb() {
        assertThat(holder.current().bySlug("data-query-agent")).isNotNull();
        assertThat(holder.current().bySlug("data-query-agent").version()).isEqualTo(1);
        assertThat(holder.current().isEnabled("data-query-agent")).isTrue();
        assertThat(holder.current().slugs())
                .containsExactlyInAnyOrder(
                        "procurement-price-agent", "data-query-agent", "intent-recognition", "meta-agent",
                        "source-sync-reviewer", "fulfillment-file-agent", "customer-followup-agent",
                        "bundle-composer-agent");
    }

    @Test
    @Order(2)
    void confirmingNewVersionSwapsHolderWithoutRestartAndAuditsLifecycle() {
        // 模拟草稿确认：v1 下线（retired）+ v2 激活（active）。先 retire 再 activate，
        // 部分唯一索引保证每 slug 至多一个 active。
        jdbc.update("UPDATE app.agent_definitions SET status = 'retired' "
                + "WHERE agent_slug = 'data-query-agent' AND version = 1");
        jdbc.update(
                "INSERT INTO app.agent_definitions ("
                        + "agent_slug, name, description, system_prompt, prompt_version, model_ref, "
                        + "enabled, version, status, activated_by, activated_at, allow_write, "
                        + "guard_exemptions, output_schema, tool_whitelist) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', 'human-confirmer', CURRENT_TIMESTAMP, "
                        + "false, '[]'::jsonb, NULL, ?::jsonb)",
                "data-query-agent",
                "数据查询",
                "自然语言只读数据查询：订单/采购/SKU 价格/库存/主数据",
                "你是数据查询助手（只读）。",
                "data-query-v1",
                "app.agent",
                true,
                2,
                DATA_QUERY_WHITELIST_JSON);

        assertThat(holder.current().bySlug("data-query-agent").version()).isEqualTo(1);

        AgentRegistry after = holder.reload();

        // holder 换实例：运行路径无需重启即感知新版本
        assertThat(holder.current()).isSameAs(after);
        assertThat(after.bySlug("data-query-agent")).isNotNull();
        assertThat(after.bySlug("data-query-agent").version()).isEqualTo(2);
        assertThat(after.bySlug("data-query-agent").status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(after.isEnabled("data-query-agent")).isTrue();

        // 版本生命周期审计（复用 agent.registry.changed 通道）
        List<String> lifecycleKinds = jdbc.queryForList(
                "SELECT business_code FROM app.audit_logs "
                        + "WHERE operation = 'agent.registry.changed' "
                        + "AND request_payload ->> 'agent_slug' = 'data-query-agent' "
                        + "AND business_code IN ('AGENT_REGISTRY_RETIRED', 'AGENT_REGISTRY_ACTIVATED') "
                        + "ORDER BY id",
                String.class);
        assertThat(lifecycleKinds)
                .as("v1→v2 必须产生 RETIRED + ACTIVATED 审计事件")
                .containsExactly(
                        AgentRegistryChangeAuditor.Kinds.RETIRED,
                        AgentRegistryChangeAuditor.Kinds.ACTIVATED);
    }

    @Test
    @Order(3)
    void enabledToggleAfterSwapIsVisibleWithoutRestart() {
        // enabled（运维启停）与 status（版本生命周期）正交：active 但 enabled=false → 运行条件不满足
        jdbc.update("UPDATE app.agent_definitions SET enabled = false "
                + "WHERE agent_slug = 'intent-recognition' AND version = 1");
        assertThat(holder.current().isEnabled("intent-recognition")).isTrue();

        AgentRegistry after = holder.reload();

        assertThat(after.bySlug("intent-recognition").status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(after.bySlug("intent-recognition").enabled()).isFalse();
        assertThat(after.isEnabled("intent-recognition")).isFalse();
    }
}
