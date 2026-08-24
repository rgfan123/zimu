package cn.zimu.fulfillment.connector.wecom.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 企微模板卡构造器（wecom-card-review §C/§F）：把协议的物理约束收进一处。
 *
 * <p>存在的理由很实际：卡片 JSON 手搓时，每加一种卡就多一处可能写错的截断长度与按钮上限。
 * 订单草稿卡是第一张手搓卡，再加四张就是五处各错各的。所有长度上限在此集中执行，
 * 超长一律**截断并加省略号**——静默丢字会让读者以为业务号就是那么短。
 *
 * <p>按钮策略：协议允许 6 个，本构造器硬性上限 3 个（§C 的设计裁定，不是协议限制）。
 * 移动端一屏放不下更多，第 4 个按钮从来不会被点。超限直接抛异常——卡片都是静态组合，
 * 单元测试必然先撞上，不会漏到线上。
 *
 * <p>不做的事：本类不判断哪些字段该脱敏。脱敏是业务语义（单聊可见全量、群聊必须脱敏），
 * 由调用方在投影阶段决定；构造器只保证「传进来的东西不会超长、不会超数量」。
 */
public final class WecomCardBuilder {

    /** §F 物理约束。desc 的 30 未见于评审文档，按协议常见值取保守值——截短永远比超长安全。 */
    public static final int MAX_TITLE = 26;
    public static final int MAX_DESC = 30;
    public static final int MAX_SUB_TITLE = 112;
    public static final int MAX_FIELD_KEY = 5;
    public static final int MAX_FIELD_VALUE = 26;
    public static final int MAX_FIELDS = 6;
    public static final int MAX_BUTTON_TEXT = 10;

    /** §C 设计裁定：协议允许 6 个，实用上限 3 个。 */
    public static final int MAX_BUTTONS = 3;

    /** 按钮样式：1 蓝（主操作）/ 2 红（驳回或危险）/ 3 灰（次要）。 */
    public enum ButtonStyle {
        PRIMARY(1),
        DANGER(2),
        SECONDARY(3);

        private final int protocolValue;

        ButtonStyle(int protocolValue) {
            this.protocolValue = protocolValue;
        }

        int protocolValue() {
            return protocolValue;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ELLIPSIS = "…";

    private final String cardType;
    private final WecomTaskId taskId;
    private final List<Field> fields = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private String title;
    private String desc;
    private String subTitle;
    private String cardActionUrl;

    private WecomCardBuilder(String cardType, WecomTaskId taskId) {
        this.cardType = cardType;
        this.taskId = taskId;
    }

    /** 交互卡：带回调按钮，点击回企微事件。 */
    public static WecomCardBuilder buttonInteraction(WecomTaskId taskId) {
        return new WecomCardBuilder("button_interaction", requireTaskId(taskId));
    }

    /**
     * 播报卡：动作已完成的事后通知，不承载回调。
     * task_id 仍然必填——它是这张卡的身份，追溯与去重都要用。
     */
    public static WecomCardBuilder textNotice(WecomTaskId taskId) {
        return new WecomCardBuilder("text_notice", requireTaskId(taskId));
    }

    public WecomCardBuilder title(String value) {
        this.title = truncate(value, MAX_TITLE);
        return this;
    }

    public WecomCardBuilder desc(String value) {
        this.desc = truncate(value, MAX_DESC);
        return this;
    }

    public WecomCardBuilder subTitle(String value) {
        this.subTitle = truncate(value, MAX_SUB_TITLE);
        return this;
    }

    /**
     * 一个字段行。空值跳过而不是渲染空白——卡面上的空行会被读成「这项没查到」，
     * 而实际上是「这项不适用」。要表达「查不到」请显式传入文案。
     */
    public WecomCardBuilder field(String keyname, String value) {
        if (keyname == null || keyname.isBlank() || value == null || value.isBlank()) {
            return this;
        }
        if (fields.size() >= MAX_FIELDS) {
            throw new IllegalStateException(
                    "horizontal_content_list 最多 " + MAX_FIELDS + " 项，请在投影阶段取舍字段");
        }
        fields.add(new Field(truncate(keyname, MAX_FIELD_KEY), truncate(value, MAX_FIELD_VALUE), null));
        return this;
    }

    /** 带跳转的字段行（type=1）：值本身是个链接，用于深链回后台。 */
    public WecomCardBuilder linkField(String keyname, String value, String url) {
        if (url == null || url.isBlank()) {
            return field(keyname, value);
        }
        if (fields.size() >= MAX_FIELDS) {
            throw new IllegalStateException("horizontal_content_list 最多 " + MAX_FIELDS + " 项");
        }
        if (keyname == null || keyname.isBlank() || value == null || value.isBlank()) {
            return this;
        }
        fields.add(new Field(truncate(keyname, MAX_FIELD_KEY), truncate(value, MAX_FIELD_VALUE), url));
        return this;
    }

    /**
     * 回调按钮（type=2）：**只允许零参数且幂等的状态跃迁**（知道了 / 我来处理 / 重试 / 忽略）。
     * 任何需要选客户、选 SKU、填数量的动作一律用 {@link #jumpButton} 深链回后台——
     * 把带参动作压进零参数按钮，等于让人点了才发现做不成。
     */
    public WecomCardBuilder callbackButton(String text, String key, ButtonStyle style) {
        requireButtonRoom();
        if (key == null || !key.matches("^[a-z][a-z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("回调按钮 key 必须匹配 ^[a-z][a-z0-9_]{0,63}$: " + key);
        }
        buttons.add(new Button(truncate(text, MAX_BUTTON_TEXT), key, null, style));
        return this;
    }

    /** 跳转按钮（type=1）：深链回后台，承载一切需要参数的动作。 */
    public WecomCardBuilder jumpButton(String text, String url, ButtonStyle style) {
        requireButtonRoom();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("跳转按钮必须带 url");
        }
        buttons.add(new Button(truncate(text, MAX_BUTTON_TEXT), null, url, style));
        return this;
    }

    /** 整卡点击跳转（card_action）：播报卡没有按钮时的唯一去处。 */
    public WecomCardBuilder cardAction(String url) {
        this.cardActionUrl = url == null || url.isBlank() ? null : url;
        return this;
    }

    public ObjectNode build() {
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("卡片必须有 main_title.title");
        }
        ObjectNode card = JSON.createObjectNode();
        card.put("card_type", cardType);
        ObjectNode mainTitle = card.putObject("main_title").put("title", title);
        if (desc != null) {
            mainTitle.put("desc", desc);
        }
        if (subTitle != null) {
            card.put("sub_title_text", subTitle);
        }
        if (!fields.isEmpty()) {
            ArrayNode list = card.putArray("horizontal_content_list");
            for (Field field : fields) {
                ObjectNode node = list.addObject();
                if (field.url() != null) {
                    node.put("type", 1).put("url", field.url());
                }
                node.put("keyname", field.keyname()).put("value", field.value());
            }
        }
        if (!buttons.isEmpty()) {
            ArrayNode list = card.putArray("button_list");
            for (Button button : buttons) {
                ObjectNode node = list.addObject()
                        .put("text", button.text())
                        .put("style", button.style().protocolValue());
                if (button.key() != null) {
                    node.put("type", 2).put("key", button.key());
                } else {
                    node.put("type", 1).put("url", button.url());
                }
            }
        }
        if (cardActionUrl != null) {
            card.putObject("card_action").put("type", 1).put("url", cardActionUrl);
        }
        card.put("task_id", taskId.value());
        return card;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void requireButtonRoom() {
        if (buttons.size() >= MAX_BUTTONS) {
            throw new IllegalStateException(
                    "按钮最多 " + MAX_BUTTONS + " 个（§C 裁定）：第 4 个按钮在移动端不会被点到");
        }
    }

    private static WecomTaskId requireTaskId(WecomTaskId taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("卡片必须带 task_id（它是版本断言，见 WecomTaskId）");
        }
        return taskId;
    }

    /**
     * 按**码点**而非 char 截断：卡面上的 emoji 是代理对，按 char 切会切出半个字符，
     * 企微侧渲染成乱码。截断后追加省略号，让读者知道这里被截过。
     */
    static String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints <= maxChars) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, maxChars - 1);
        return trimmed.substring(0, end) + ELLIPSIS;
    }

    private record Field(String keyname, String value, String url) {}

    private record Button(String text, String key, String url, ButtonStyle style) {}
}
