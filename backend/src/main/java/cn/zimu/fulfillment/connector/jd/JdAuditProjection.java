package cn.zimu.fulfillment.connector.jd;

import java.util.Map;

/**
 * ISC 调用写入审计前的载荷投影。
 *
 * <p>{@link JdIscGateway} 只负责「记什么时候、记哪次调用」，「记多少」属于各接口自己的
 * 数据政策：出库单查询/建单的响应含收件人、账号与自由文本，必须收敛成定长引用与计数
 * （见 {@link JdWarehouseAuditProjection}）；其余只读接口沿用原样记录。
 *
 * <p>默认实现即「原样记录」，与收编前 6 个客户端的行为逐位一致；
 * 让非 warehouse 接口也走白名单摘要是独立的数据政策变更，不在传输内核收编范围内
 * （票 03 已记录为后续项）。
 */
public interface JdAuditProjection {

    /** 原样记录：收编前多数客户端的行为。 */
    JdAuditProjection FULL = new JdAuditProjection() {};

    /** 请求载荷投影。 */
    default Object request(String operation, Map<String, Object> command) {
        return command == null ? Map.of() : command;
    }

    /** 响应载荷投影。 */
    default Object response(String operation, JdResult result) {
        return result;
    }

    /** 写入审计的 requestId（可做长度/控制字符收敛）。 */
    default String requestId(String value) {
        return value;
    }

    /** 写入审计的业务码（可做长度/控制字符收敛）。 */
    default String businessCode(String value) {
        return value;
    }
}
