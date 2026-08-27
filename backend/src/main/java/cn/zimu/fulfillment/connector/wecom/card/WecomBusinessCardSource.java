package cn.zimu.fulfillment.connector.wecom.card;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/**
 * 一个业务域的卡片渲染来源（#87/#88 通用投递管道）。
 *
 * <p>投递表**不存卡片正文**：正文含客户名等业务文本，落库等于把 PII 面扩大到第二张表。
 * 发送时回调本接口按**当前事实**重新渲染——顺带保证发出去的一定是最新事实，
 * 而不是入队那一刻的快照（订单草稿卡已用同样的思路做 revision 门禁）。
 */
public interface WecomBusinessCardSource {

    /** 与 {@link WecomTaskId#domain()} 一致的域名，注册表按它路由。 */
    String domain();

    /**
     * 按当前事实渲染卡片。
     *
     * @param entityId      业务实体主键
     * @param entityVersion 入队时的实体版本
     * @return 卡片 JSON；**返回 empty 表示这张卡不该再发了**（实体已处置、已关闭，
     *         或版本已推进），调用方据此落 SUPERSEDED 而不是发一张过期卡出去
     */
    Optional<ObjectNode> render(long entityId, long entityVersion);

    /**
     * 待发卡的实体（本域自己定义「待发」）。
     *
     * <p>**为什么是扫描而不是在创建处调用**：复核事项在代码里有 37 处创建点
     * （14 处裸 SQL + 23 处 JPA），运营告警有 3 处绕过 Service 的裸 INSERT。
     * 逐处接线必漏，而漏掉的那处的表现是「这类事项从来不推送」——没人会发现。
     * 扫描是只读的收口 seam：覆盖所有创建路径，且不可能弄坏业务写。
     *
     * <p>实现必须 LEFT JOIN {@code app.wecom_business_cards} 排除已建卡的实体，
     * 并遵守 {@code since} 下界——不设下界的话，首次开启会把历史积压的全部事项
     * 一次性轰出去。
     *
     * @param since 只看此时间之后创建/更新的实体
     * @param limit 单次扫描上限
     */
    default java.util.List<WecomTaskId> pending(java.time.OffsetDateTime since, int limit) {
        return java.util.List.of();
    }

    /** 该域的会话路由：单聊 userid 或群 chatid。返回 empty 表示未配置，落 SUPERSEDED。 */
    Optional<Route> route(long entityId);

    /**
     * 随卡附件（发卡前逐个投递，如整批确认的明细清单）。与 {@link #render} 同一套
     * 版本纪律：按当前事实即时生成，事实已变时返回空列表。默认为无附件。
     */
    default java.util.List<Attachment> attachments(long entityId, long entityVersion) {
        return java.util.List.of();
    }

    /** 附件：文件名 + 内容 + 媒体类型（决定走文件消息还是图片消息）。 */
    record Attachment(
            String filename, byte[] content, cn.zimu.fulfillment.connector.wecom.WecomMediaType mediaType) {
        public Attachment {
            if (filename == null || filename.isBlank()) {
                throw new IllegalArgumentException("附件文件名不能为空");
            }
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("附件内容不能为空");
            }
            java.util.Objects.requireNonNull(mediaType, "mediaType");
        }
    }

    /** 会话路由。群聊必须脱敏（收件人手机号与详细地址不得进群），由 source 在渲染时保证。 */
    record Route(RouteType type, String chatId) {
        public Route {
            if (chatId == null || chatId.isBlank()) {
                throw new IllegalArgumentException("chatId 不能为空");
            }
        }
    }

    enum RouteType {
        SINGLE,
        GROUP
    }
}
