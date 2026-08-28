package cn.zimu.fulfillment.common.persistence;

/**
 * 多实例竞争下的<b>预期并发结果</b>（租约被接管、claim 令牌被轮换、结果已被先到者写入），
 * 不是持久化缺陷。
 *
 * <p><b>为什么不用 {@code IllegalStateException} / {@code IllegalArgumentException}</b>：
 * {@code @Repository} bean 被 Spring 持久化异常翻译代理包裹（本仓库因 spring-data-jpa 在
 * classpath 上，实际翻译器是 {@code EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible}），
 * 逃出代理的 ISE/IAE 会被一律改写成 {@code InvalidDataAccessApiUsageException}——字面意思是
 * 「你把持久化 API 用错了」。一次正常的租约竞争因此在日志与告警里长得像代码缺陷，调用方也
 * 无法按类型区分「租约没了（正常竞争，安静让位）」与「持久化 API 真用错了（必须修）」。
 *
 * <p>本基类直接继承 {@code RuntimeException}：翻译器不认识就不改写，事务回滚语义不变。
 * 各 store 以嵌套子类携带自己的语义与上下文（taskId / batchId / shipmentId…），调用方按子类分流。
 *
 * <p><b>判据</b>：只有「多实例并发下的预期落空」才归入本家族。防御性不变量（如 FOR UPDATE
 * 锁内不可能输的 CAS、插入后行必须存在）保持 ISE——它们一旦触发就是真缺陷，翻译成
 * {@code InvalidDataAccessApiUsageException} 反而语义相称。ADR 0015 记录了本决策。
 */
public abstract class ConcurrencyConflictException extends RuntimeException {

    protected ConcurrencyConflictException(String message) {
        super(message);
    }
}
