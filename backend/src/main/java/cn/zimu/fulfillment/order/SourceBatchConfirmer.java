package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import java.util.Map;

/**
 * 整批确认的最窄端口：给「不在 file 包里、但需要确认一个来源批次」的调用方用
 * （目前是企微「确认发货」卡的执行面；受信模板 AutomaticRelease 也必须复用本端口，
 * 不得另开绕过来源映射、包装乘数或 SKU readiness 的放行路径）。
 *
 * <p><b>为什么是接口而不是把方法改成 public</b>：{@code SourceImportService.confirm}
 * 是包私有的，那是有意的边界——确认牵动建 shipment、建京东单、写审计，
 * 不该让任意包都能直接调。照 {@link ReadySourceBatchExporter} 已建立的写法开一个窄口，
 * 暴露的就只有这两个动作。
 *
 * <p><b>两个方法必须成对调用</b>，且顺序固定：先 {@code confirm}，
 * 仅当它**不是幂等重放**时才 {@code submitJdOutbounds}。重放时再调一次
 * 等于对同一批货发起第二次外部建单——幂等键能挡住，但不该依赖它兜底。
 */
public interface SourceBatchConfirmer {

    /** 确认一个来源批次：建发货批次、决定出库路由。幂等键相同则重放首次结果。 */
    IdempotentResult<Map<String, Object>> confirmSourceBatch(
            long sourceBatchId, String idempotencyKey, CommandContext context);

    /** 对该批次里走 SDK 路由的发货批次触发京东建单。只在首次确认后调用。 */
    void submitJdOutboundsForSourceBatch(long sourceBatchId, CommandContext context);
}
