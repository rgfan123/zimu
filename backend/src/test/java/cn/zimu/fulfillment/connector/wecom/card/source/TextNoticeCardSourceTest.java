package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import org.junit.jupiter.api.Test;

/**
 * {@code text_notice} 的纵深防御契约。
 *
 * <p>在用的三张播报卡已改成 {@code button_interaction}，因此
 * {@link CardDeepLinks#textNoticeAvailable} 目前**没有生产调用方**——本用例就是它的调用方，
 * 保证这条门闩不会在无人使用期间悄悄腐坏。将来任何新增的 {@code text_notice} 卡都必须先过它：
 * 缺配置时渲染必然抛异常，而 Runner 把渲染异常当成可重试失败，表现是每 30 秒空转一次，
 * 谁也看不出根因是少配了一个属性。
 */
class TextNoticeCardSourceTest {

    @Test
    void textNoticeGuardClosesWhenNoDeepLinkBaseIsConfiguredAndOpensWhenItIs() {
        assertThat(new CardDeepLinks("").textNoticeAvailable("demo", 7))
                .as("缺 base-url 是确定性部署事实，必须收口成可诊断的终态而不是重试")
                .isFalse();
        assertThat(new CardDeepLinks("  ").textNoticeAvailable("demo", 7)).isFalse();
        assertThat(new CardDeepLinks("https://zimu.test").textNoticeAvailable("demo", 7)).isTrue();
    }

    /** https-only 不放宽：明文 HTTP 的公网基址不得被当成可用的深链。 */
    @Test
    void plainHttpPublicBaseIsNotAcceptedAsADeepLinkBase() {
        assertThat(new CardDeepLinks("http://zimu.example.com").configured()).isFalse();
        assertThat(new CardDeepLinks("http://localhost:8088").configured())
                .as("回环 http 仍然允许：它没有链路上的第三方")
                .isTrue();
    }

    /** 门闩存在的理由：没有它，text_notice 缺 card_action 时是抛异常而不是收口。 */
    @Test
    void textNoticeWithoutACardActionStillFailsLoudlyAtBuildTime() {
        assertThatThrownBy(() -> WecomCardBuilder
                        .textNotice(WecomTaskId.ofVersion("demo", 7, 1))
                        .title("标题")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text_notice 必须带安全的绝对 HTTP(S) card_action");
    }
}
