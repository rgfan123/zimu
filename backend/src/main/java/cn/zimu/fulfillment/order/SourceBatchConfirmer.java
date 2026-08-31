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
 * 暴露的只有人工确认、受信模板确认和配对出站三个窄动作。
 *
 * <p><b>确认与出站必须成对调用</b>，且顺序固定：先确认，再出站。HTTP/企微同步调用方只在
 * 首次确认后触发；可崩溃恢复的 AutomaticRelease 重入时仍进入出站，由 Shipment 的已提交围栏
 * 跳过成功项，补上“确认已提交但任务尚未收尾”窗口。
 */
public interface SourceBatchConfirmer {

    /** 确认一个来源批次：建发货批次、决定出库路由。幂等键相同则重放首次结果。 */
    IdempotentResult<Map<String, Object>> confirmSourceBatch(
            long sourceBatchId, String idempotencyKey, CommandContext context);

    /** 受信模板自动确认；实现必须在确认事务内锁定并复验 profile 与批次有效模板身份。 */
    IdempotentResult<Map<String, Object>> confirmTrustedSourceBatch(
            long sourceBatchId, long templateProfileId, String idempotencyKey, CommandContext context);

    /** 对该批次里走 SDK 路由的发货批次触发京东建单，并返回逐项成功/失败事实。 */
    Map<String, Object> submitJdOutboundsForSourceBatch(long sourceBatchId, CommandContext context);
}
