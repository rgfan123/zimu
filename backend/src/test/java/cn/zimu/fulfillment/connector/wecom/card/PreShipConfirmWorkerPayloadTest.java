package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 确认任务载荷：单卡与整批卡两种前缀，其余一律非法。 */
class PreShipConfirmWorkerPayloadTest {

    @Test
    void 单卡载荷_orderId语义() {
        var payload = PreShipConfirmWorker.Payload.parse("preship:19:3:jry:");
        assertThat(payload.batchScoped()).isFalse();
        assertThat(payload.entityId()).isEqualTo(19);
        assertThat(payload.version()).isEqualTo(3);
        assertThat(payload.actor()).isEqualTo("jry");
    }

    @Test
    void 整批载荷_batchId语义() {
        var payload = PreShipConfirmWorker.Payload.parse("preship-batch:30:7:jry:chat-1");
        assertThat(payload.batchScoped()).isTrue();
        assertThat(payload.entityId()).isEqualTo(30);
        assertThat(payload.version()).isEqualTo(7);
        assertThat(payload.chatId()).isEqualTo("chat-1");
    }

    @Test
    void 其它前缀_一律拒绝() {
        assertThatThrownBy(() -> PreShipConfirmWorker.Payload.parse("shipped:1:2:jry:"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
