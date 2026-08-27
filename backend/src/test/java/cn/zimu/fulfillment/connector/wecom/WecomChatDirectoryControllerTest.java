package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** 会话目录：群来自事件表、单聊来自运营人员表；无操作人 401。 */
@org.testcontainers.junit.jupiter.Testcontainers
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class WecomChatDirectoryControllerTest {

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void 群取自事件表_单聊取自运营人员表() {
        jdbc.update(
                """
                INSERT INTO app.wecom_events (event_type, msgid, aibot_id, chat_id, chat_type, raw_payload)
                VALUES ('template_card_event', 'MSG-DIR-1', 'bot', 'wrTESTGROUPID001', 'group', '{}'::jsonb),
                       ('template_card_event', 'MSG-DIR-2', 'bot', 'wrTESTGROUPID001', 'group', '{}'::jsonb)
                """);
        jdbc.update(
                """
                INSERT INTO app.internal_operators (display_name, responsible_team, wecom_userid, active)
                VALUES ('目录测试员', 'ORDER_OPS', 'dir-test-user', true)
                """);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "zimu-admin");
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/wecom/chats", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> chats = (List<Map<String, Object>>) response.getBody().get("chats");
        assertThat(chats)
                .anySatisfy(chat -> {
                    assertThat(chat.get("chat_id")).isEqualTo("wrTESTGROUPID001");
                    assertThat(chat.get("chat_type")).isEqualTo("group");
                    assertThat(((Number) chat.get("event_count")).longValue()).isEqualTo(2L);
                    assertThat(chat.get("last_seen_at")).isNotNull();
                })
                .anySatisfy(chat -> {
                    assertThat(chat.get("chat_id")).isEqualTo("dir-test-user");
                    assertThat(chat.get("chat_type")).isEqualTo("single");
                    assertThat(chat.get("label")).isEqualTo("目录测试员");
                });
    }

    @Test
    void 无操作人_401() {
        ResponseEntity<Map> response = http.getForEntity("/api/v1/wecom/chats", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
