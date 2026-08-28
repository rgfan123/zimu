package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.util.Map;

/**
 * 文件导入链路的子单号回退键。
 *
 * <p><b>为什么需要回退</b>：结构化（API 拉单）链路把子单号写进
 * {@code raw_cells.source_line_ref}（{@code SourceImportService#rowCells}），而 Excel 链路的
 * {@code raw_cells} 就是平台原始列，没有这个键——{@code SourceFileParser} 虽然逐渠道解析出了
 * 子单号，却只在万旗的投影里落过。结果是<b>所有文件导入的单子在线回传全部阻断</b>，
 * 表现为 {@code SOURCE_SYNC_SINGLE_SOURCE_LINE_REQUIRED}。
 *
 * <p>2026-08-29 生产实证：31 行已接受来源行里只有 1 行带该键，那 1 行来自 API 拉单
 * （批次 59）；彩食鲜 9/9、大者 8/8、飞象 4/4、中汇 7/7、聚福宝 2/3 全缺。
 *
 * <p><b>为什么只能在读侧修</b>：{@code raw_import_rows.raw_cells} 由
 * {@code app.protect_raw_import_row()} 触发器保护为不可变，原始证据不许回填。历史行改不了，
 * 只能读的时候回退到平台原始列名。
 *
 * <p>键名与 {@code SourceFileParser} 各渠道 {@code row(..., lineRef, ...)} 的取值一一对应，
 * 改那边必须同步改这里。
 */
public final class SourceLineRefFallback {

    /** 与 SourceFileParser 的 lineRef 取值同源；只覆盖在线回传支持的三个渠道。 */
    private static final Map<SourceChannel, String> KEYS = Map.of(
            SourceChannel.JUFUBAO, "拆单号",
            SourceChannel.CAISHIXIAN, "子订单编号",
            SourceChannel.FEIXIANG, "订单商品ID");

    private SourceLineRefFallback() {}

    /**
     * 该渠道的平台原始列名。
     *
     * <p>未登记的渠道回落到规范键本身，让 COALESCE 退化成无操作——刻意<b>不返回 null</b>：
     * {@code jsonb->>NULL} 整个表达式会变成 null，反而把已有 source_line_ref 的行也读丢。
     */
    public static String keyFor(SourceChannel channel) {
        return KEYS.getOrDefault(channel, "source_line_ref");
    }
}
