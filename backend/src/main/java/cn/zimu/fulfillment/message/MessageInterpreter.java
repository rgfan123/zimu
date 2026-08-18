package cn.zimu.fulfillment.message;

/**
 * 唯一模型接缝。真实模型集成以 @Service/@Component 实现本接口并替换默认 Bean；
 * 更换模型不得影响消息接收、草稿、复核或成单用例。
 */
public interface MessageInterpreter {

    InterpretationResult interpret(InterpretationInput input);
}
