package cn.zimu.fulfillment.fulfillment;

/**
 * 人工录入运单的结果。
 *
 * <p>三种情况必须分得开：<b>刚写进去</b>、<b>重复提交</b>、<b>和已有运单打架</b>。
 * 后两种都不是成功，把它们当成功报会让操作员以为事情办完了。
 *
 * <p>顶层 record 而不是服务的嵌套类型：OpenAPI 生成器按类名出 schema 名，嵌套会生成
 * 一个叫 {@code Outcome} 的泛泛名字，与手写评审契约对不上（契约一致性测试会红）。
 */
public record ManualTrackingOutcome(String status, String trackingNumber, String message) {

    static ManualTrackingOutcome accepted(String trackingNumber) {
        return new ManualTrackingOutcome("ACCEPTED", trackingNumber, "运单已录入");
    }

    static ManualTrackingOutcome replayed(String trackingNumber) {
        return new ManualTrackingOutcome(
                "REPLAYED", trackingNumber, "这张批次已经录过同一个运单号，未重复写入");
    }

    static ManualTrackingOutcome conflict(String trackingNumber, String existing) {
        return new ManualTrackingOutcome(
                "CONFLICT",
                trackingNumber,
                "这张批次已有运单号 " + existing + "，与本次录入的不一致，需人工核对");
    }
}
