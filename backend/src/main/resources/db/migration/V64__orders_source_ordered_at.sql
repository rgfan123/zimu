-- 来源订单创建时间：渠道平台上的真实下单时刻（如彩食鲜订单在彩食鲜的下单时间），
-- 与既有 settlement_time（结算/导出口径，历史上被 ProviderFileService 的导出 SQL
-- 直接当 ordered_at 用）分开——两者此前被混同，同一列既要表达「渠道何时下单」又要
-- 表达「按什么结算」，部分渠道（如彩食鲜/大者 v2/万齐 52 列模板）源数据根本没有下单
-- 时间列时，settlement_time 会兜底成导入时刻，看起来像有时间、实际只是「导入时发生
-- 的时刻」，与「渠道买家何时下单」无关。
--
-- 语义：source_ordered_at 只在来源明确提供下单时间时才有值；来源没给就如实为 NULL，
-- 不借用结算时间或导入时刻顶替。字符串时间一律按 Asia/Shanghai 解释（与
-- SourceFileParser.parseTime 既有口径一致）后转 timestamptz 落库。
--
-- 读侧：ProviderFileService 的导出/卡片 ordered_at 改为
-- COALESCE(source_ordered_at, settlement_time)，存量订单（本列全为 NULL）自动落回
-- 原 settlement_time 口径，不破坏现状；新订单起两列分别演进。
ALTER TABLE app.orders ADD COLUMN source_ordered_at TIMESTAMPTZ;

COMMENT ON COLUMN app.orders.source_ordered_at IS
    '渠道平台的下单时刻；源数据缺失下单时间时为 NULL，不借用 settlement_time 或导入时刻顶替；字符串时间按 Asia/Shanghai 解释';
