package cn.zimu.fulfillment.agent;

/**
 * 一次意图识别运行的可观测元数据摘要（agent-decision-layer 07 运行桥）。
 *
 * <p>与 {@code message/InterpretationResult} 同构但刻意独立：agent 层不依赖 message 层类型，
 * 由 {@code message/InterpretationService} 在桥接点做字段级投影（错误码已在 message 侧经
 * {@code InterpretationFailureCode.normalize} 归一化）。provider/model/promptVersion 必须经
 * {@link AgentModelMetadataRegistry} 服务端 allowlist 投影后才对外可见；errorCode 为
 * {@code InterpretationFailureCode} 稳定枚举或 null（成功）。
 */
public record IntentRecognitionRunMetadata(
        String provider,
        String model,
        String promptVersion,
        String intent,
        String errorCode) {

    /** 模型调用异常等无结果路径：三元组折叠为 none，意图未知，错误码为给定稳定码。 */
    public static IntentRecognitionRunMetadata failed(String errorCode) {
        return new IntentRecognitionRunMetadata("none", "none", "none", null, errorCode);
    }
}
