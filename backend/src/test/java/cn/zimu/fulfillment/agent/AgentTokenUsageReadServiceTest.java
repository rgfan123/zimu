package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.dto.AgentTokenUsageFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Agent token 汇总可选筛选的快速单元门禁；不启动 Spring/Testcontainers。 */
class AgentTokenUsageReadServiceTest {

    @Test
    void outcomeFilterUsesTheSameRejectedSemanticsAsRunList() {
        Query query = summarize("REJECTED", null);

        assertThat(query.sql())
                .contains("status = 'FAILED' AND error_type = 'PII_GUARDED'")
                .doesNotContain("business_entity_id = ?");
        assertThat(query.params()).containsExactly("LIVE", 100);
    }

    @Test
    void businessEntityIdFilterIsBoundAsAnOptionalWhereParameter() {
        Query query = summarize(null, "ORDER-42");

        assertThat(query.sql())
                .contains("business_entity_id = ?")
                .doesNotContain("AND status = 'SUCCESS'", "AND status = 'FAILED'");
        assertThat(query.params()).containsExactly("LIVE", "ORDER-42", 100);
    }

    @Test
    void omittingNewFiltersPreservesTheExistingAggregateQuery() {
        Query query = summarize(null, null);

        assertThat(query.sql())
                .doesNotContain(
                        "business_entity_id = ?",
                        "AND status = 'SUCCESS'",
                        "AND status = 'FAILED'");
        assertThat(query.params()).containsExactly("LIVE", 100);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Query summarize(String outcome, String businessEntityId) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        AgentTokenUsageReadService service = new AgentTokenUsageReadService(jdbc);
        service.summarize(AgentTokenUsageFilter.of(
                null,
                outcome,
                null,
                null,
                businessEntityId,
                null,
                null,
                "AGENT",
                100));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        return new Query(sql.getValue(), params.getValue());
    }

    private record Query(String sql, Object[] params) {}
}
