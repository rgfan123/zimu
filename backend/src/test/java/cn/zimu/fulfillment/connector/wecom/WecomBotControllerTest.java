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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** 企微机器人管理台账：写入后读回不含明文、留空保持现值、无操作人 401。 */
@org.testcontainers.junit.jupiter.Testcontainers
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class WecomBotControllerTest {

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private static HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "zimu-admin");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void 写入后读回不含明文_留空保持现值() {
        String botId = "aibTESTBOT0000001";
        String secretValue = "S3cr3t-Value-Should-Never-Leak";
        HttpHeaders headers = operatorHeaders();

        // 首次登记：带密钥
        ResponseEntity<String> createRaw = http.exchange(
                "/api/v1/wecom/bots/" + botId, HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "name", "测试机器人",
                        "secret", secretValue,
                        "enabled", true,
                        "note", "首次登记"), headers),
                String.class);
        assertThat(createRaw.getStatusCode().value()).isEqualTo(200);
        assertThat(createRaw.getBody())
                .as("响应体绝不能出现明文密钥")
                .doesNotContain(secretValue);

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/wecom/bots/" + botId, HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "name", "测试机器人",
                        "secret", secretValue,
                        "enabled", true,
                        "note", "首次登记"), headers),
                Map.class);
        assertThat(created.getBody().get("bot_id")).isEqualTo(botId);
        assertThat(created.getBody().get("name")).isEqualTo("测试机器人");
        assertThat(created.getBody().get("secret_configured")).isEqualTo(true);
        assertThat(created.getBody().get("enabled")).isEqualTo(true);
        assertThat(created.getBody().get("note")).isEqualTo("首次登记");
        assertThat(created.getBody()).as("响应体不得含 secret 键").doesNotContainKey("secret");

        // DB 落库确实是这个明文（存法与京东 pin 一致：明文列，只在读侧/审计投影脱敏）
        assertThat(jdbc.queryForObject(
                "SELECT secret FROM app.wecom_bots WHERE bot_id = ?", String.class, botId))
                .isEqualTo(secretValue);

        // 读回列表：同样只回存在性标记
        ResponseEntity<String> listRaw = http.exchange(
                "/api/v1/wecom/bots", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(listRaw.getBody()).as("列表接口绝不能出现明文密钥").doesNotContain(secretValue);

        ResponseEntity<Map> listing = http.exchange(
                "/api/v1/wecom/bots", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        List<Map<String, Object>> bots = (List<Map<String, Object>>) listing.getBody().get("bots");
        assertThat(bots).anySatisfy(bot -> {
            assertThat(bot.get("bot_id")).isEqualTo(botId);
            assertThat(bot.get("secret_configured")).isEqualTo(true);
            assertThat(bot).doesNotContainKey("secret");
        });

        // 二次编辑：secret 留空 + 改名改备注 + 停用——密钥必须保持现值不变
        ResponseEntity<Map> updated = http.exchange(
                "/api/v1/wecom/bots/" + botId, HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "name", "测试机器人（改名）",
                        "secret", "",
                        "enabled", false,
                        "note", "二次编辑"), headers),
                Map.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().get("name")).isEqualTo("测试机器人（改名）");
        assertThat(updated.getBody().get("enabled")).isEqualTo(false);
        assertThat(updated.getBody().get("note")).isEqualTo("二次编辑");
        assertThat(updated.getBody().get("secret_configured"))
                .as("secret 留空必须保持已配置状态，不能被清空")
                .isEqualTo(true);
        assertThat(jdbc.queryForObject(
                "SELECT secret FROM app.wecom_bots WHERE bot_id = ?", String.class, botId))
                .as("留空提交不得覆盖库中原有明文密钥")
                .isEqualTo(secretValue);
    }

    @Test
    void 新建未带密钥_未配置状态_名称必填() {
        String botId = "aibTESTBOT0000002";
        HttpHeaders headers = operatorHeaders();

        ResponseEntity<Map> created = http.exchange(
                "/api/v1/wecom/bots/" + botId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", "无密钥机器人"), headers), Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getBody().get("secret_configured")).isEqualTo(false);
        assertThat(created.getBody().get("enabled"))
                .as("enabled 缺省新建应默认为 true")
                .isEqualTo(true);

        // 名称必填：空串拒绝
        ResponseEntity<Map> blankName = http.exchange(
                "/api/v1/wecom/bots/" + botId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", ""), headers), Map.class);
        assertThat(blankName.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void 无操作人_401() {
        assertThat(http.getForEntity("/api/v1/wecom/bots", Map.class).getStatusCode().value())
                .isEqualTo(401);
        assertThat(http.exchange(
                "/api/v1/wecom/bots/aibTESTBOT0000003", HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", "无操作人")), Map.class)
                .getStatusCode().value())
                .isEqualTo(401);
    }
}
