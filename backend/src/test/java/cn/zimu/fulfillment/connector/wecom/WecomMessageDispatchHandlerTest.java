package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 企业微信长连接接收链路：消息帧 → {@link MessageSubmissionService} + 「已接收」回执 + 图片下载；
 * 事件帧按决策留档或忽略。消息幂等由 {@code channel_messages} ON CONFLICT 保证（已有链路测试覆盖），
 * 本测试聚焦帧映射与回执/媒体编排。
 */
@Testcontainers
@SpringBootTest
class WecomMessageDispatchHandlerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void wecomConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
    }

    @Autowired private WecomMessageDispatchHandler handler;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private MessageSubmissionService submissionService;
    @MockitoBean private WecomConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM app.wecom_events");
        jdbc.update("DELETE FROM app.message_submissions");
        jdbc.update("DELETE FROM app.channel_messages");
        when(connectionManager.respond(any(), any())).thenReturn(true);
    }

    @Test
    void groupTextMessageMapsFieldsAndSendsReceipt() {
        when(submissionService.submit(any())).thenReturn(1L);

        handler.onFrame("aibot_msg_callback", textFrame("MSG-1", "group", "chat-1", "user-1", "今天发货三件"));

        ArgumentCaptor<ChannelMessageCommand> captor = ArgumentCaptor.forClass(ChannelMessageCommand.class);
        verify(submissionService).submit(captor.capture());
        ChannelMessageCommand command = captor.getValue();
        assertThat(command.corpId()).isEqualTo("bot-1");
        assertThat(command.connectionId()).isEqualTo(WecomMessageDispatchHandler.CONNECTION_ID);
        assertThat(command.botId()).isEqualTo("bot-1");
        assertThat(command.messageId()).isEqualTo("MSG-1");
        assertThat(command.chatId()).isEqualTo("chat-1");
        assertThat(command.chatType()).isEqualTo("group");
        assertThat(command.senderUserId()).isEqualTo("user-1");
        assertThat(command.messageType()).isEqualTo("text");
        assertThat(command.content()).isEqualTo("今天发货三件");
        assertThat(command.rawPayload().path("cmd").asText()).isEqualTo("aibot_msg_callback");

        ArgumentCaptor<JsonNode> receipt = ArgumentCaptor.forClass(JsonNode.class);
        verify(connectionManager).respond(eq("REQ-1"), receipt.capture());
        assertThat(receipt.getValue().path("msgtype").asText()).isEqualTo("text");
        assertThat(receipt.getValue().path("text").path("content").asText()).isEqualTo("已接收");
    }

    @Test
    void singleChatWithoutChatIdKeepsEmptyChatId() {
        when(submissionService.submit(any())).thenReturn(1L);

        handler.onFrame("aibot_msg_callback", textFrame("MSG-2", "single", null, "user-2", "帮我加一单"));

        ArgumentCaptor<ChannelMessageCommand> captor = ArgumentCaptor.forClass(ChannelMessageCommand.class);
        verify(submissionService).submit(captor.capture());
        // 单聊无 chatid：表约束非空，用发送人构造稳定会话标识
        assertThat(captor.getValue().chatId()).isEqualTo("single:user-2");
        assertThat(captor.getValue().chatType()).isEqualTo("single");
    }

    @Test
    void mixedMessageExtractsTextAndKeepsMediaInRawPayload() {
        when(submissionService.submit(any())).thenReturn(1L);

        handler.onFrame(
                "aibot_msg_callback",
                json(
                        "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"REQ-3\"},\"body\":{"
                                + "\"msgid\":\"MSG-3\",\"aibotid\":\"bot-1\",\"chatid\":\"chat-3\","
                                + "\"chattype\":\"group\",\"from\":{\"userid\":\"user-3\"},\"msgtype\":\"mixed\","
                                + "\"mixed\":{\"items\":["
                                + "{\"msgtype\":\"text\",\"content\":\"客户要两个\"},"
                                + "{\"msgtype\":\"image\",\"url\":\"https://media.example/a\",\"aeskey\":\"aes-a\"},"
                                + "{\"msgtype\":\"text\",\"content\":\"蓝色款\"},"
                                + "{\"msgtype\":\"image\",\"url\":\"https://media.example/b\",\"aeskey\":\"aes-b\"}"
                                + "]}}}"));

        ArgumentCaptor<ChannelMessageCommand> commandCaptor = ArgumentCaptor.forClass(ChannelMessageCommand.class);
        verify(submissionService).submit(commandCaptor.capture());
        assertThat(commandCaptor.getValue().content()).isEqualTo("客户要两个 蓝色款");
        // 媒体只保留在原始载荷，下载由解释任务负责（07 票：回调不等待下载）
        assertThat(commandCaptor.getValue().rawPayload().path("body").path("mixed").path("items")).hasSize(4);
    }

    @Test
    void imageMessageKeepsMediaInRawPayloadWithoutDownload() {
        when(submissionService.submit(any())).thenReturn(1L);

        handler.onFrame(
                "aibot_msg_callback",
                json(
                        "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"REQ-4\"},\"body\":{"
                                + "\"msgid\":\"MSG-4\",\"aibotid\":\"bot-1\",\"chattype\":\"single\","
                                + "\"from\":{\"userid\":\"user-4\"},\"msgtype\":\"image\","
                                + "\"image\":{\"url\":\"https://media.example/c\",\"aeskey\":\"aes-c\"}}}"));

        ArgumentCaptor<ChannelMessageCommand> commandCaptor = ArgumentCaptor.forClass(ChannelMessageCommand.class);
        verify(submissionService).submit(commandCaptor.capture());
        assertThat(commandCaptor.getValue().content()).isEmpty();
        assertThat(commandCaptor.getValue().rawPayload().path("body").path("image").path("url").asText())
                .isEqualTo("https://media.example/c");
    }

    @Test
    void voiceMessagePersistsWithoutMediaDownload() {
        when(submissionService.submit(any())).thenReturn(1L);

        handler.onFrame(
                "aibot_msg_callback",
                json(
                        "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"REQ-5\"},\"body\":{"
                                + "\"msgid\":\"MSG-5\",\"aibotid\":\"bot-1\",\"chattype\":\"single\","
                                + "\"from\":{\"userid\":\"user-5\"},\"msgtype\":\"voice\","
                                + "\"voice\":{\"url\":\"https://media.example/v\",\"aeskey\":\"aes-v\"}}}"));

        verify(submissionService).submit(any());
        verify(connectionManager).respond(eq("REQ-5"), any());
    }

    @Test
    void singleFileMessagePreservesDedicatedDownloadEvidenceInRawPayload() {
        when(submissionService.submit(any())).thenReturn(1L);

        handler.onFrame(
                "aibot_msg_callback",
                json(
                        "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"REQ-FILE\"},\"body\":{"
                                + "\"msgid\":\"MSG-FILE\",\"aibotid\":\"bot-1\",\"chattype\":\"single\","
                                + "\"from\":{\"userid\":\"user-file\"},\"msgtype\":\"file\","
                                + "\"file\":{\"url\":\"https://media.example/tracking\","
                                + "\"aeskey\":\"file-aes-key\",\"filename\":\"tracking.xlsx\"}}}"));

        ArgumentCaptor<ChannelMessageCommand> commandCaptor = ArgumentCaptor.forClass(ChannelMessageCommand.class);
        verify(submissionService).submit(commandCaptor.capture());
        ChannelMessageCommand command = commandCaptor.getValue();
        assertThat(command.messageType()).isEqualTo("file");
        assertThat(command.chatType()).isEqualTo("single");
        assertThat(command.content()).isEmpty();
        assertThat(command.rawPayload().path("body").path("file").path("url").asText())
                .isEqualTo("https://media.example/tracking");
        verify(connectionManager).respond(eq("REQ-FILE"), any());
    }

    @Test
    void receiptIsRetriedOnceWhenFirstAttemptFails() {
        when(submissionService.submit(any())).thenReturn(1L);
        when(connectionManager.respond(eq("REQ-1"), any())).thenReturn(false, true);

        handler.onFrame("aibot_msg_callback", textFrame("MSG-6", "group", "chat-6", "user-6", "补发一单"));

        verify(connectionManager, times(2)).respond(eq("REQ-1"), any());
    }

    @Test
    void receiptStopsAfterRetryAndNeverThrows() {
        when(submissionService.submit(any())).thenReturn(1L);
        when(connectionManager.respond(eq("REQ-1"), any())).thenReturn(false, false);

        handler.onFrame("aibot_msg_callback", textFrame("MSG-7", "group", "chat-7", "user-7", "加购"));

        verify(connectionManager, times(2)).respond(eq("REQ-1"), any());
    }

    @Test
    void submitFailureSkipsReceipt() {
        when(submissionService.submit(any())).thenThrow(new IllegalStateException("db down"));

        handler.onFrame("aibot_msg_callback", textFrame("MSG-8", "group", "chat-8", "user-8", "下单"));

        verify(connectionManager, never()).respond(any(), any());
    }

    @Test
    void enterChatEventIsPersisted() {
        handler.onFrame(
                "aibot_event_callback",
                json(
                        "{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\"REQ-9\"},\"body\":{"
                                + "\"msgid\":\"EVT-1\",\"create_time\":1700000000,\"aibotid\":\"bot-1\","
                                + "\"chattype\":\"single\",\"from\":{\"userid\":\"user-9\"},"
                                + "\"msgtype\":\"event\",\"event\":{\"eventtype\":\"enter_chat\"}}}"));

        Long count = jdbc.queryForObject("SELECT count(*) FROM app.wecom_events WHERE event_type = 'enter_chat'", Long.class);
        assertThat(count).isEqualTo(1);
        verify(connectionManager, never()).respond(any(), any());
    }

    @Test
    void disconnectedEventIsPersisted() {
        handler.onFrame(
                "aibot_event_callback",
                json(
                        "{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\"REQ-10\"},\"body\":{"
                                + "\"msgid\":\"EVT-2\",\"create_time\":1700000001,\"aibotid\":\"bot-1\","
                                + "\"msgtype\":\"event\",\"event\":{\"eventtype\":\"disconnected_event\"}}}"));

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM app.wecom_events WHERE event_type = 'disconnected_event'", Long.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void templateCardAndFeedbackEventsAreIgnored() {
        handler.onFrame(
                "aibot_event_callback",
                json(
                        "{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\"REQ-11\"},\"body\":{"
                                + "\"msgid\":\"EVT-3\",\"aibotid\":\"bot-1\",\"msgtype\":\"event\","
                                + "\"event\":{\"eventtype\":\"template_card_event\"}}}"));
        handler.onFrame(
                "aibot_event_callback",
                json(
                        "{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\"REQ-12\"},\"body\":{"
                                + "\"msgid\":\"EVT-4\",\"aibotid\":\"bot-1\",\"msgtype\":\"event\","
                                + "\"event\":{\"eventtype\":\"feedback_event\"}}}"));

        Long count = jdbc.queryForObject("SELECT count(*) FROM app.wecom_events", Long.class);
        assertThat(count).isZero();
    }

    @Test
    void duplicateEventIsPersistedOnce() {
        String frame = "{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\"REQ-13\"},\"body\":{"
                + "\"msgid\":\"EVT-5\",\"aibotid\":\"bot-1\",\"msgtype\":\"event\","
                + "\"event\":{\"eventtype\":\"enter_chat\"}}}";
        handler.onFrame("aibot_event_callback", json(frame));
        handler.onFrame("aibot_event_callback", json(frame));

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM app.wecom_events WHERE event_type = 'enter_chat' AND msgid = 'EVT-5'", Long.class);
        assertThat(count).isEqualTo(1);
    }

    private JsonNode textFrame(String messageId, String chatType, String chatId, String sender, String content) {
        return json(
                "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"REQ-1\"},\"body\":{"
                        + "\"msgid\":\"" + messageId + "\",\"aibotid\":\"bot-1\","
                        + (chatId == null ? "" : "\"chatid\":\"" + chatId + "\",")
                        + "\"chattype\":\"" + chatType + "\",\"from\":{\"userid\":\"" + sender + "\"},"
                        + "\"msgtype\":\"text\",\"text\":{\"content\":\"" + content + "\"}}}");
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid test frame", ex);
        }
    }
}
