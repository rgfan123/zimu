package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
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
class WecomBusinessCardInteractionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingTaskStore tasks;
    private StubJdbc jdbc;
    private StubCardStore cards;
    private WecomBusinessCardInteractionService service;

    @BeforeEach
    void setUp() {
        tasks = new RecordingTaskStore();
        jdbc = new StubJdbc(1L);
        cards = new StubCardStore();
        service = new WecomBusinessCardInteractionService(jdbc, tasks, cards);
    }

    /**
     * 真实点击帧的形状（2026-08-26 生产实证，wecom_events#10 原始载荷）：
     * task_id 与 event_key **嵌在 body.event.template_card_event 里**，不是平铺在 body.event 上。
     * 此前测试按平铺构造，于是「测试全绿、线上点了没反应」——测试自己在测错的协议。
     */
    private static ObjectNode frame(String taskId, String buttonKey, String actor) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode body = frame.putObject("body");
        body.put("msgid", "MSG-1");
        body.put("chatid", "");
        body.putObject("from").put("userid", actor);
        ObjectNode event = body.putObject("event");
        event.put("eventtype", "template_card_event");
        ObjectNode card = event.putObject("template_card_event");
        card.put("task_id", taskId);
        card.put("event_key", buttonKey);
        return frame;
    }

    /** 老回调示例里是平铺的；两种都要认，否则换个协议版本就全哑。 */
    private static ObjectNode flatFrame(String taskId, String buttonKey, String actor) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode body = frame.putObject("body");
        body.put("msgid", "MSG-FLAT");
        body.putObject("from").put("userid", actor);
        ObjectNode event = body.putObject("event");
        event.put("eventtype", "template_card_event");
        event.put("task_id", taskId);
        event.put("event_key", buttonKey);
        return frame;
    }

    private static ObjectNode callback(String taskId, String buttonKey, String actor) {
        return frame(WecomTaskId.parse(taskId).orElseThrow().authorize(StubCardStore.AUTH).value(), buttonKey, actor);
    }

    @Test
    void 嵌套与平铺两种帧形状都要认出来() {
        assertThat(WecomBusinessCardInteractionService.taskId(
                        frame("review_11_v0", "claim_review_case", "jry")))
                .isEqualTo("review_11_v0");
        assertThat(WecomBusinessCardInteractionService.buttonKey(
                        frame("review_11_v0", "claim_review_case", "jry")))
                .isEqualTo("claim_review_case");
        assertThat(WecomBusinessCardInteractionService.taskId(
                        flatFrame("preship_4_v1", "preship_confirm", "jry")))
                .isEqualTo("preship_4_v1");
        assertThat(WecomBusinessCardInteractionService.buttonKey(
                        flatFrame("preship_4_v1", "preship_confirm", "jry")))
                .isEqualTo("preship_confirm");
    }

    @Test
    void 生产真实帧必须被判给业务卡处理器() {
        // 这一条守的是 2026-08-26 那次「点了没反应」：帧认不出来就会掉回订单草稿卡
        assertThat(WecomBusinessCardInteractionService.handles(
                        WecomBusinessCardInteractionService.taskId(
                                frame("review_11_v0", "claim_review_case", "jry"))))
                .isTrue();
    }

    @Test
    void 确认按钮排出建单任务_并当场回一句确定的话() {
        var outcome = service.handle(
                callback("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.businessCode()).isEqualTo("PRESHIP_CONFIRM_ACCEPTED");
        assertThat(tasks.enqueued).hasSize(1);
        assertThat(tasks.enqueued.getFirst().taskType())
                .isEqualTo(WecomBusinessCardInteractionService.TASK_TYPE);
        assertThat(tasks.enqueued.getFirst().payloadRef()).startsWith("preship:4:1:jry");
    }

    @Test
    void 点旧卡直接判版本冲突_不建单() {
        // 卡上是 v0，库里已经是 v1：这张卡描述的事实已经不成立了
        var outcome = service.handle(
                callback("preship_4_v0", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 认不出点击人就不建单_建单必须有审计主体() {
        var outcome = service.handle(
                callback("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, ""));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_ACTOR_REQUIRED");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 驳回不排任务也不产生外部写() {
        var outcome = service.handle(
                callback("preship_4_v1", PreShipConfirmCard.REJECT_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("PRESHIP_REJECTED");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 不认识的按钮一律不执行() {
        var outcome = service.handle(callback("preship_4_v1", "preship_delete_everything", "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_BUTTON_UNKNOWN");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 订单草稿卡不归本服务管_留给原处理器() {
        // order-draft 不在 DOMAINS 里：必须让它落回订单草稿卡的处理器，
        // 而不是被本服务当成「无法识别」吞掉
        assertThat(WecomBusinessCardInteractionService.handles("order-draft_7_v1")).isFalse();
        assertThat(WecomBusinessCardInteractionService.handles("preship_4_v1")).isTrue();
        assertThat(WecomBusinessCardInteractionService.handles("review_10_v0")).isTrue();
        assertThat(WecomBusinessCardInteractionService.handles("alert_1_v0")).isTrue();
        // 冒号是 aibot 非法字符：旧格式必须解析不出来
        assertThat(WecomBusinessCardInteractionService.handles("preship:4:v1")).isFalse();
    }

    @Test
    void 冒号旧格式的task_id解析不出来_按无法识别处理() {
        var outcome = service.handle(
                frame("preship:4:v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_TASK_ID_INVALID");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 可猜的逻辑task_id不是回调能力_绝不得触发建单() {
        var outcome = service.handle(
                frame("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_NOT_SENT");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 订单不存在时不建单() {
        service = new WecomBusinessCardInteractionService(new StubJdbc(null), tasks, cards);

        var outcome = service.handle(
                callback("preship_9_v0", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(outcome.businessCode()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(tasks.enqueued).isEmpty();
    }

    @Test
    void 幂等键含msgid_企微重推同一事件不会排出第二个建单任务() {
        var first = service.handle(
                callback("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));
        var second = service.handle(
                callback("preship_4_v1", PreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        // 两次都受理，但幂等键相同——收敛由 async_tasks 的唯一约束承担，
        // 这里断言的是"键确实一样"，而不是服务自己去记状态
        assertThat(tasks.enqueued).hasSize(2);
        assertThat(tasks.enqueued.get(0).idempotencyKey())
                .isEqualTo(tasks.enqueued.get(1).idempotencyKey())
                .contains("MSG-1");
    }

    // ---------- 播报卡的「知道了」 ----------

    /**
     * 三张播报卡改成 button_interaction 后带了按钮，点击必须由本服务认领。
     *
     * <p>域没登记进 {@code DOMAINS} 的话，点击会掉回订单草稿卡处理器报
     * {@code WECOM_CARD_TASK_ID_INVALID}——读者看到的是「无法识别这张卡片」。
     */
    @Test
    void 三个播报域都必须被判给业务卡处理器() {
        assertThat(WecomBusinessCardInteractionService.handles("batch_51_v1")).isTrue();
        assertThat(WecomBusinessCardInteractionService.handles("shipped_4_v1")).isTrue();
        assertThat(WecomBusinessCardInteractionService.handles("followup-result_91_v3")).isTrue();
    }

    @Test
    void 播报卡的知道了_受理但不产生任何业务写() {
        var batch = service.handle(
                callback("batch_51_v1", BatchConfirmedCard.ACKNOWLEDGE_BUTTON_KEY, "jry"));
        var shipped = service.handle(
                callback("shipped_4_v1", ShipmentResultCard.ACKNOWLEDGE_BUTTON_KEY, "jry"));
        var followup = service.handle(callback(
                "followup-result_91_v3",
                BusinessFollowUpResultCard.ACKNOWLEDGE_BUTTON_KEY,
                "jry"));

        assertThat(batch.accepted()).isTrue();
        assertThat(batch.businessCode()).isEqualTo("WECOM_CARD_BROADCAST_ACKNOWLEDGED");
        assertThat(shipped.accepted()).isTrue();
        assertThat(followup.accepted()).isTrue();
        assertThat(tasks.enqueued)
                .as("播报卡的 ack 不排任何任务：动作早已完成，再做一次才是事故")
                .isEmpty();
    }

    /**
     * 播报卡**不做版本断言**：没有状态可改，一次「我看到了」在哪个版本上都成立。
     * 拿版本去卡它，只会让人点一张播报卡收到「这张卡已过期」——纯噪音。
     */
    @Test
    void 播报卡的旧卡也能确认_它没有会过期的动作() {
        var outcome = service.handle(
                callback("batch_51_v0", BatchConfirmedCard.ACKNOWLEDGE_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_BROADCAST_ACKNOWLEDGED");
    }

    /** 按钮 key 按域校验：拿发货结果卡的 key 去点整批确认卡，不认。 */
    @Test
    void 播报卡认错按钮key一律不执行() {
        var outcome = service.handle(
                callback("batch_51_v1", ShipmentResultCard.ACKNOWLEDGE_BUTTON_KEY, "jry"));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.businessCode()).isEqualTo("WECOM_CARD_BUTTON_UNKNOWN");
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

    private static final class StubCardStore extends WecomBusinessCardStore {
        private static final String AUTH = "0123456789abcdef0123456789abcdef";

        StubCardStore() {
            super(null);
        }

        @Override
        public Optional<WecomBusinessCard> findSentByTaskId(String taskId) {
            return WecomTaskId.parse(taskId)
                    .filter(value -> AUTH.equals(value.authorizationRef()))
                    .map(value -> new WecomBusinessCard(
                            1L,
                            value.domain(),
                            value.entityId(),
                            value.version(),
                            taskId,
                            "SINGLE",
                            "jry",
                            "SENT",
                            1));
        }
    }

    /** 只回答"这个订单当前是第几版"；null 表示订单不存在。 */
    @org.junit.jupiter.api.Test
    void 整批确认点击_受理并入队批载荷() {
        var outcome = service.handle(
                callback("preship-batch_30_v1", BatchPreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        org.assertj.core.api.Assertions.assertThat(outcome.accepted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(outcome.businessCode())
                .isEqualTo("PRESHIP_BATCH_CONFIRM_ACCEPTED");
        org.assertj.core.api.Assertions.assertThat(tasks.enqueued).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(tasks.enqueued.getFirst().payloadRef())
                .isEqualTo("preship-batch:30:1:jry:");
    }

    @org.junit.jupiter.api.Test
    void 整批旧卡_版本不符拒绝() {
        // Stub 里当前批版本恒为 1；v0 的卡就是旧卡
        var outcome = service.handle(
                callback("preship-batch_30_v0", BatchPreShipConfirmCard.CONFIRM_BUTTON_KEY, "jry"));

        org.assertj.core.api.Assertions.assertThat(outcome.accepted()).isFalse();
        org.assertj.core.api.Assertions.assertThat(outcome.businessCode()).isEqualTo("VERSION_CONFLICT");
    }

    @org.junit.jupiter.api.Test
    void 整批驳回_不入队任何任务() {
        var outcome = service.handle(
                callback("preship-batch_30_v1", BatchPreShipConfirmCard.REJECT_BUTTON_KEY, "jry"));

        org.assertj.core.api.Assertions.assertThat(outcome.accepted()).isFalse();
        org.assertj.core.api.Assertions.assertThat(outcome.businessCode()).isEqualTo("PRESHIP_BATCH_REJECTED");
        org.assertj.core.api.Assertions.assertThat(tasks.enqueued).isEmpty();
    }

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
