package cn.zimu.fulfillment.fulfillment;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 发货任务候选的确定性解析（票 08/09）。
 *
 * <p>候选范围固定为：履约方为启用第三方（{@code THIRD_PARTY}）、订单行已进入
 * {@code WAITING_PROVIDER}，且任务恰好关联一条已有、尚未回传的 {@code CREATED Shipment/ShipmentItem}，
 * 该 Shipment 也不得还有其他未回传 Item（合票多 Item 留给批量复核）。
 * 系统任务号只在这个范围内按 {@code fulfillment_no} 精确关联；缺任务号时姓名中的
 * {@code *} 作为通配符只与收货人快照匹配，零/多命中不自动关联。
 * 不使用转发人、群聊、昵称、时间或近似文本等隐式权重。
 */
@Component
public class TrackingTaskResolver {

    /** 任务候选事实（草稿展示与确认时重新校验用）。 */
    public record TaskCandidate(
            long taskId,
            String fulfillmentNo,
            long orderId,
            String orderNo,
            long orderLineId,
            long providerId,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            int requestedQuantity,
            int shippedQuantity,
            int cancelledQuantity,
            long shipmentId,
            int instructedQuantity) {

        /** 当前仍待回传的剩余数量。 */
        public int remaining() {
            return requestedQuantity - shippedQuantity - cancelledQuantity;
        }
    }

    private final JdbcTemplate jdbc;

    public TrackingTaskResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 系统任务号 → 范围内唯一命中；范围外存在同名任务时由调用方区分 NOT_FOUND / NOT_APPLICABLE。 */
    public List<TaskCandidate> resolveByTaskNo(String taskNo) {
        return jdbc.query(
                baseSql() + " AND f.fulfillment_no = ?",
                (resultSet, rowNum) -> map(resultSet),
                normalize(taskNo));
    }

    /** 确认时按系统任务号精确读取并锁定候选事实。 */
    public List<TaskCandidate> resolveByTaskNoForUpdate(String taskNo) {
        return jdbc.query(
                baseSql() + " AND f.fulfillment_no = ? FOR UPDATE OF f, s, si",
                (resultSet, rowNum) -> map(resultSet),
                normalize(taskNo));
    }

    /** 任务号在系统里存在但不在候选范围内（非第三方 / 已回传完成 / 非业务数据）。 */
    public boolean existsAnywhere(String taskNo) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN app.orders o ON o.id=ol.order_id
                WHERE f.fulfillment_no=?
                """,
                Long.class,
                normalize(taskNo));
        return count != null && count > 0;
    }

    /** 脱敏姓名（* 通配）→ 收货人快照匹配，返回范围内全部命中；调用方决定唯一/零/多。 */
    public List<TaskCandidate> resolveByName(String maskedName) {
        if (!hasLiteralNameEvidence(maskedName)) {
            return List.of();
        }
        return jdbc.query(
                baseSql() + " AND o.receiver_name LIKE ? ESCAPE '\\'",
                (resultSet, rowNum) -> map(resultSet),
                wildcardPattern(maskedName));
    }

    /**
     * 至少保留一个非空白、非通配符字符才能查询候选池。全 {@code *} 只表示“任意姓名”，
     * 不是可用的脱敏姓名证据，禁止用它自动绑定或枚举整个待回传任务池。
     */
    public static boolean hasLiteralNameEvidence(String maskedName) {
        return maskedName != null
                && maskedName.codePoints().anyMatch(codePoint -> codePoint != '*' && !Character.isWhitespace(codePoint));
    }

    /** 按任务 ID 读取候选事实；不在范围内返回空。 */
    public List<TaskCandidate> byTaskId(long taskId) {
        return jdbc.query(
                baseSql() + " AND f.id = ?",
                (resultSet, rowNum) -> map(resultSet),
                taskId);
    }

    /** 按任务 ID 读取并锁定候选事实（确认时重新校验，串行化同一任务的并发确认）。 */
    public List<TaskCandidate> byTaskIdForUpdate(long taskId) {
        return jdbc.query(
                baseSql() + " AND f.id = ? FOR UPDATE OF f, s, si",
                (resultSet, rowNum) -> map(resultSet),
                taskId);
    }

    private String baseSql() {
        return """
                SELECT f.id fulfillment_id, f.fulfillment_no, o.id order_id, o.order_no, ol.id order_line_id,
                       fp.id provider_id, o.receiver_name, o.receiver_phone, o.receiver_address,
                       f.requested_quantity, f.cumulative_shipped_quantity, f.cancelled_quantity,
                       s.id shipment_id, si.instructed_quantity
                FROM app.fulfillments f
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id
                     AND fp.provider_type='THIRD_PARTY' AND fp.active
                JOIN app.order_lines ol ON ol.id=f.order_line_id AND ol.processing_stage='WAITING_PROVIDER'
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                JOIN app.shipment_items si ON si.fulfillment_id=f.id AND si.shipped_quantity IS NULL
                JOIN app.shipments s ON s.id=si.shipment_id AND s.shipment_status='CREATED'
                WHERE f.cumulative_shipped_quantity + f.cancelled_quantity < f.requested_quantity
                  AND NOT EXISTS (
                      SELECT 1 FROM app.shipment_items sibling
                      WHERE sibling.shipment_id=s.id
                        AND sibling.id<>si.id
                  )
                """;
    }

    private TaskCandidate map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new TaskCandidate(
                resultSet.getLong("fulfillment_id"),
                resultSet.getString("fulfillment_no"),
                resultSet.getLong("order_id"),
                resultSet.getString("order_no"),
                resultSet.getLong("order_line_id"),
                resultSet.getLong("provider_id"),
                resultSet.getString("receiver_name"),
                resultSet.getString("receiver_phone"),
                resultSet.getString("receiver_address"),
                resultSet.getInt("requested_quantity"),
                resultSet.getInt("cumulative_shipped_quantity"),
                resultSet.getInt("cancelled_quantity"),
                resultSet.getLong("shipment_id"),
                resultSet.getInt("instructed_quantity"));
    }

    /** 通配转换：* → %，其余字符按字面转义（反斜杠、%、_）。 */
    static String wildcardPattern(String maskedName) {
        StringBuilder pattern = new StringBuilder();
        for (int index = 0; index < maskedName.length(); index++) {
            char ch = maskedName.charAt(index);
            switch (ch) {
                case '*' -> pattern.append('%');
                case '\\', '%', '_' -> pattern.append('\\').append(ch);
                default -> pattern.append(ch);
            }
        }
        return pattern.toString();
    }

    private static String normalize(String taskNo) {
        return taskNo == null ? "" : taskNo.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
