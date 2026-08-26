package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 发货前确认卡的回调判定。
 *
 * <p>这条路径的输出是**建真单**，所以每一条"不执行"的分支都比"执行"的分支更值得测：
 * 认不出人、点了旧卡、按钮不认识——任何一条漏掉，代价都是替人建了一张不该建的京东单。
 */
class PreShipConfirmInteractionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingTaskStore tasks;
    private StubJdbc jdbc;
    private PreShipConfirmInteractionService service;

    @BeforeEach
    void setUp() {
        tasks = new RecordingTaskStore();
        jdbc = new StubJdbc(1L);
        service = new PreShipConfirmInteractionService(jdbc, tasks);
    }

    private static ObjectNode frame(String taskId, String buttonKey, String actor) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode body = frame.putObject("body");
        body.put("msgid", "MSG-1");
        body.put("chatid", "");
        body.putObject("from").put("userid", actor);
        ObjectNode event = body.putObject("event");
        event.put("eventtype", "template_card_event");
        event.put("taskid", taskId);
        event.put("eventkey", buttonKey);
        return frame;
    }

    @Test
    void 确认按钮排出建单任务_并当场回一句确定的话() {
        var outcome = service.handle(
                frame("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.businessCode()).isEqualTo("PRESHIP_CONFIRM_ACCEPTED");
        assertThat(tasks.enqueued).hasSize(1);
        assertThat(tasks.enqueued.getFirst().taskType())
                .isEqualTo(PreShipConfirmInteractionService.TASK_TYPE);
        assertThat(tasks.enqueued.getFirst().payloadRef()).startsWith("preship:4:1:jry");
    }

    @Test
    void 点旧卡直接判版本冲突_不建单() {
        // 卡上是 v0，库里已经是 v1：这张卡描述的事实已经不成立了
        var outcome = service.handle(
                frame("preship_4_v0", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 认不出点击人就不建单_建单必须有审计主体() {
        var outcome = service.handle(
                frame("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, ""));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_ACTOR_REQUIRED");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 驳回不排任务也不产生外部写() {
        var outcome = service.handle(
                frame("preship_4_v1", PreShipConfirmCard.REJECT_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("PRESHIP_REJECTED");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 不认识的按钮一律不执行() {
        var outcome = service.handle(frame("preship_4_v1", "preship_delete_everything", "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_BUTTON_UNKNOWN");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 别的域的卡片不归本服务管() {
        var outcome = service.handle(frame("alert_4_v1", "acknowledge", "jry"));

        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_TASK_ID_INVALID");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 冒号旧格式的task_id解析不出来_按无法识别处理() {
        var outcome = service.handle(
                frame("preship:4:v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_TASK_ID_INVALID");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 订单不存在时不建单() {
        service = new PreShipConfirmInteractionService(new StubJdbc(null), tasks);

        var outcome = service.handle(
                frame("preship_9_v0", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.businessCode()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 幂等键含msgid_企微重推同一事件不会排出第二个建单任务() {
        var first = service.handle(
                frame("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));
        var second = service.handle(
                frame("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        // 两次都受理，但幂等键相同——收敛由 async_tasks 的唯一约束承担，
        // 这里断言的是"键确实一样"，而不是服务自己去记状态
        assertThat(tasks.enqueued).hasSize(2);
        assertThat(tasks.enqueued.get(0).idempotencyKey())
                .isEqualTo(tasks.enqueued.get(1).idempotencyKey())
                .contains("MSG-1");
    }

    // ------------------------------------------------------------------

    private record Enqueued(String taskType, String payloadRef, String idempotencyKey) {}

    private static final class RecordingTaskStore extends AsyncTaskStore {
        private final List<Enqueued> enqueued = new java.util.ArrayList<>();

        RecordingTaskStore() {
            super(null);
        }

        @Override
        public void enqueue(String taskType, String payloadRef, String idempotencyKey, int maxAttempts) {
            enqueued.add(new Enqueued(taskType, payloadRef, idempotencyKey));
        }
    }

    /** 只回答"这个订单当前是第几版"；null 表示订单不存在。 */
    private static final class StubJdbc extends JdbcTemplate {
        private final Long lockVersion;

        StubJdbc(Long lockVersion) {
            this.lockVersion = lockVersion;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (lockVersion == null) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) List.of(lockVersion);
            return result;
        }
    }
}
