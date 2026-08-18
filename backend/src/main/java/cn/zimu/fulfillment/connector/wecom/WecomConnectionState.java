package cn.zimu.fulfillment.connector.wecom;

/**
 * 企业微信长连接状态机（readiness 投影用，非密）。
 *
 * <ul>
 *   <li>DISCONNECTED — 未建连或已断开（含应用关闭后的终态）</li>
 *   <li>CONNECTING — 连接建立中（含订阅响应等待中）</li>
 *   <li>SUBSCRIBED — 连接建立且订阅成功，可收发业务帧</li>
 *   <li>KICKED — 收到 disconnected_event 被新连接抢占，停止自动重连，需人工介入</li>
 *   <li>FAILED — 订阅连续失败达到上限，停止重试</li>
 * </ul>
 */
public enum WecomConnectionState {
    DISCONNECTED,
    CONNECTING,
    SUBSCRIBED,
    KICKED,
    FAILED
}
