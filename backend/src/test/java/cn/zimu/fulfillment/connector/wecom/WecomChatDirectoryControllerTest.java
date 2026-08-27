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
    @SuppressWarnings("unchecked")
    void 会话档案可写可读_部分更新_非法输入拒绝() {
        jdbc.update(
                """
                INSERT INTO app.wecom_events (event_type, msgid, aibot_id, chat_id, chat_type, raw_payload)
                VALUES ('template_card_event', 'MSG-POL-1', 'bot', 'wrPOLICYGROUP001', 'group', '{}'::jsonb)
                """);
        jdbc.update(
                """
                INSERT INTO app.agent_definitions
                    (agent_slug, name, description, system_prompt, prompt_version, model_ref,
                     enabled, status, version)
                SELECT 'dir-test-agent', '目录测试 Agent', '测试用', 'prompt', 'v1', 'model',
                       false, 'draft', 1
                WHERE NOT EXISTS (SELECT 1 FROM app.agent_definitions WHERE agent_slug = 'dir-test-agent')
                """);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "zimu-admin");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        // 起名 + 绑 Agent + 收权限，一次写入
        ResponseEntity<Map> put = http.exchange(
                "/api/v1/wecom/chats/wrPOLICYGROUP001/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "reply_mode", "RECEIPTS_ONLY",
                        "display_name", "中汇客户群",
                        "agent_slug", "dir-test-agent"), headers),
                Map.class);
        assertThat(put.getStatusCode().value()).isEqualTo(200);
        assertThat(put.getBody().get("reply_mode")).isEqualTo("RECEIPTS_ONLY");
        assertThat(put.getBody().get("display_name")).isEqualTo("中汇客户群");

        // 部分更新：只改备注名，权限与 Agent 不动
        ResponseEntity<Map> rename = http.exchange(
                "/api/v1/wecom/chats/wrPOLICYGROUP001/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of("display_name", "中汇发货群"), headers), Map.class);
        assertThat(rename.getBody().get("display_name")).isEqualTo("中汇发货群");
        assertThat(rename.getBody().get("reply_mode")).isEqualTo("RECEIPTS_ONLY");
        assertThat(rename.getBody().get("agent_slug")).isEqualTo("dir-test-agent");

        ResponseEntity<Map> listing = http.exchange(
                "/api/v1/wecom/chats", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        List<Map<String, Object>> chats = (List<Map<String, Object>>) listing.getBody().get("chats");
        assertThat(chats).anySatisfy(chat -> {
            assertThat(chat.get("chat_id")).isEqualTo("wrPOLICYGROUP001");
            assertThat(chat.get("display_name")).isEqualTo("中汇发货群");
            assertThat(chat.get("reply_mode")).isEqualTo("RECEIPTS_ONLY");
            assertThat(chat.get("agent_slug")).isEqualTo("dir-test-agent");
        });

        // 非法模式与不存在的 Agent 都拒绝
        assertThat(http.exchange(
                "/api/v1/wecom/chats/wrPOLICYGROUP001/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of("reply_mode", "WHATEVER"), headers), Map.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(http.exchange(
                "/api/v1/wecom/chats/wrPOLICYGROUP001/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of("agent_slug", "no-such-agent"), headers), Map.class)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void 无操作人_401() {
        ResponseEntity<Map> response = http.getForEntity("/api/v1/wecom/chats", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
