package cn.zimu.fulfillment.connector.schedule;

/**
 * 「这一行算不算阻断」的 SQL 判据，镜像自 {@code SourceBatchConfirmReadiness#blockedPredicate}。
 *
 * <p><b>为什么是镜像而不是复用</b>：{@code SourceBatchConfirmReadiness} 与它的
 * {@code blockedPredicate} 都是包私有的（{@code cn.zimu.fulfillment.file}），
 * 那是刻意的边界——确认闸门牵动建 shipment、建京东单、写审计，不该让任意包直接调。
 * 从 {@code connector.schedule} 编译期就引用不到，把它改成 public 又要动 file 包
 * （本次任务的避让区，另有会话在改）。
 *
 * <p><b>镜像的风险与对策</b>：判据分叉时，自动发货会与人工确认闸门给出不同答案，
 * 而分叉的表现是「本该拦下的批次被自动发了出去」——花真钱且无人察觉。三道防线：
 * <ol>
 *   <li>{@code AutoShipBlockedPredicateParityTest}（位于 {@code cn.zimu.fulfillment.file}
 *       测试包，能读到包私有原件）逐字符比对本类与原件的输出。原件一改，构建立刻变红。</li>
 *   <li>{@code AutoShipService} 在确认成功后复核返回的 {@code skipped_rows}：
 *       非空即代表本判据判错了（判成「无阻断」却被闸门跳过了行），立刻记为异常并播报。</li>
 *   <li>{@code AutoShipReadinessTest} 用真库夹具覆盖四种行状态。</li>
 * </ol>
 *
 * <p>真正的修法是把该判据提升为公开端口（如 {@code order} 包里那种最窄接口），
 * 需要动 file 包，留给能碰那个包的人。
 */
public final class AutoShipBlockedPredicate {

    private AutoShipBlockedPredicate() {}

    /**
     * 「定义上无事可做」的行：订单早已入库，或来源侧早已发完。恒不建单，故 order_line_id 恒为 NULL。
     *
     * <p>与真正的阻断行区分：NEED_REVIEW（缺 SKU 映射等）是「本应建单却没建成」，
     * 不在本豁免之列。
     */
    private static final String BENIGN_CODES = "'ORDER_ALREADY_EXISTS', 'SOURCE_ORDER_ALREADY_FULFILLED'";

    /** 表别名前缀：无别名传空串，有别名传 {@code "rir"}。与原件同一套拼法。 */
    private static String prefix(String tableAlias) {
        return tableAlias == null || tableAlias.isBlank() ? "" : tableAlias + ".";
    }

    private static String benignPredicate(String alias) {
        return "(" + alias + "order_line_id IS NULL AND " + alias + "error_code IN (" + BENIGN_CODES + "))";
    }

    /** 阻断口径：非 ACCEPTED、非干净 RECEIVED（候选流水线的就绪行），且排除「定义上无事可做」的行。必须与原件逐字符一致。 */
    public static String blockedPredicate(String tableAlias) {
        String alias = prefix(tableAlias);
        return alias + "status<>'ACCEPTED' AND NOT (" + alias + "status='RECEIVED' AND " + alias
                + "error_code IS NULL) AND NOT " + benignPredicate(alias);
    }
}
