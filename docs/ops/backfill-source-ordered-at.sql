-- 存量回填：app.orders.source_ordered_at（V64 迁移只 ADD COLUMN，不回填历史订单）。
--
-- 用途：把已落库订单的「来源订单创建时间」从其来源导入行的原始快照
-- （app.raw_import_rows.raw_cells）解析回填，与建单时的解析口径完全一致
-- （backend SourceFileParser.parseTime：字符串按 Asia/Shanghai 解释后转 timestamptz）。
--
-- 本脚本不由部署/迁移自动执行——业务数据变更须经人手工审阅后自行执行（见文件
-- 末尾的自查 SELECT，建议先跑一遍确认样例再执行 UPDATE）。可重复执行：每条
-- UPDATE 都带 WHERE o.source_ordered_at IS NULL，不会覆盖已回填或已由新建单
-- 路径写入的值。
--
-- 一单可能对应多条来源行（同订单多个商品行）：取该单所有来源行解析出的最早
-- 时间，与 SourceImportService.canonical() 建单时 `min(orderedAt)` 的语义一致。
--
-- 各渠道时间键名（键名来自 backend SourceFileParser 的渠道模板列定义，
-- 已用生产 raw_import_rows.raw_cells 抽样核对）：
--
--   ZHONGHUI（中汇）  : 下单时间          格式 yyyy-MM-dd HH:mm:ss
--                        注意：中汇原始表另有「支付时间」列，不是下单时间，
--                        与结算/支付口径无关，不得混用。
--   FEIXIANG（飞象）  : 下单时间          格式 yyyy-MM-dd HH:mm:ss
--   JUFUBAO（聚福宝） : 下单时间          格式 yyyy-MM-dd HH:mm:ss
--                        仅限历史 Excel 导入路径（jufubao() 模板）。在线 JSON
--                        拉单渠道用 created_time（epoch 秒）作下单时间，不落
--                        raw_import_rows，本脚本管不到、也不需要——这条链路
--                        从 V64 上线起，新建单已经把 created_time 写进
--                        source_ordered_at 了，没有历史缺口。
--   WANGQI（历史技术值，对应大者旧 15 列模板）:
--                        渠道支付时间 / 渠道下单时间（取先出现的非空值，
--                        与 SourceFileParser.wangqi() 的 first(...) 语义一致）
--                        格式 yyyy-MM-dd HH:mm:ss
--
--   以下渠道当前导出模板里根本没有下单时间列，源数据本就没有这个事实，
--   保持 NULL、不回填（如实反映「渠道没告诉我们」，不得借用结算时间/
--   导入时刻顶替）：
--   DAZHE（大者 v2，11 列订单往返表）——没有任何日期列；
--     若历史数据实际落在大者旧 15 列模板（此时技术值是 WANGQI 而非
--     DAZHE），会被上面 WANGQI 分支覆盖。
--   CAISHIXIAN（彩食鲜）——当前导出模板没有下单时间列。
--   WANQI（万齐 52 列「订单管理导出」）——最接近的「期望时间」是履约期望窗口，
--     不是下单时刻，不可冒充。
--   WECOM——人工创建渠道，没有「渠道平台下单时刻」这个概念。
--
-- 时区假设：raw_cells 里的时间字符串一律按 Asia/Shanghai 本地时间解释
-- （与 SourceFileParser.SOURCE_TIME / parseTime 的既有口径一致），
-- `<text>::timestamp AT TIME ZONE 'Asia/Shanghai'` 是 PostgreSQL 里
-- 「把这个不带时区的本地时间解释为该时区、再转成 timestamptz」的标准写法。
-- 格式不满足 `yyyy-MM-dd HH:mm:ss` 的行直接跳过（不抛错、不阻断其余行），
-- 与应用侧 parseTime 解析失败落 NULL 的诚实语义一致。

BEGIN;

-- ① ZHONGHUI / FEIXIANG / JUFUBAO（Excel 导入路径）：统一走「下单时间」键。
WITH candidate AS (
    SELECT
        rir.order_id,
        MIN(
            (NULLIF(btrim(rir.raw_cells ->> '下单时间'), ''))::timestamp AT TIME ZONE 'Asia/Shanghai'
        ) AS source_ordered_at
    FROM app.raw_import_rows rir
    JOIN app.import_batches b ON b.id = rir.import_batch_id
    WHERE rir.order_id IS NOT NULL
      AND b.source_channel IN ('ZHONGHUI', 'FEIXIANG', 'JUFUBAO')
      AND btrim(rir.raw_cells ->> '下单时间') ~ '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$'
    GROUP BY rir.order_id
)
UPDATE app.orders o
SET source_ordered_at = candidate.source_ordered_at
FROM candidate
WHERE o.id = candidate.order_id
  AND o.source_ordered_at IS NULL
  AND candidate.source_ordered_at IS NOT NULL;

-- ② WANGQI（历史技术值，对应大者旧 15 列模板）：渠道支付时间优先，缺失时退渠道下单时间。
WITH candidate AS (
    SELECT
        rir.order_id,
        MIN(
            (NULLIF(btrim(COALESCE(
                NULLIF(rir.raw_cells ->> '渠道支付时间', ''),
                rir.raw_cells ->> '渠道下单时间'
            )), ''))::timestamp AT TIME ZONE 'Asia/Shanghai'
        ) AS source_ordered_at
    FROM app.raw_import_rows rir
    JOIN app.import_batches b ON b.id = rir.import_batch_id
    WHERE rir.order_id IS NOT NULL
      AND b.source_channel = 'WANGQI'
      AND btrim(COALESCE(
              NULLIF(rir.raw_cells ->> '渠道支付时间', ''),
              rir.raw_cells ->> '渠道下单时间'
          )) ~ '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$'
    GROUP BY rir.order_id
)
UPDATE app.orders o
SET source_ordered_at = candidate.source_ordered_at
FROM candidate
WHERE o.id = candidate.order_id
  AND o.source_ordered_at IS NULL
  AND candidate.source_ordered_at IS NOT NULL;

COMMIT;

-- ---------------------------------------------------------------------------
-- 执行前自查（建议）：把上面任一 UPDATE 的 `UPDATE ... SET ... FROM candidate
-- WHERE ...` 换成下面这样的 SELECT，先看一眼将要回填的行数与几条样例时间是否
-- 合理，确认无误再执行本文件里的 UPDATE：
--
--   WITH candidate AS ( ... 同上，任选一段 ... )
--   SELECT o.id, o.order_no, o.source_channel, o.settlement_time, candidate.source_ordered_at
--   FROM app.orders o JOIN candidate ON candidate.order_id = o.id
--   WHERE o.source_ordered_at IS NULL AND candidate.source_ordered_at IS NOT NULL
--   ORDER BY o.id;
-- ---------------------------------------------------------------------------
