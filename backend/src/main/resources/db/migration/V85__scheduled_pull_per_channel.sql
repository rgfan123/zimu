-- 定时拉取下沉到渠道粒度：run_key 加渠道段，并记下「本次拉完推不推企微」。
--
-- 为什么必须改：V83 的 run_key 是 {日期}:{时段}，唯一约束兼作跨实例单飞门禁。
-- 各平台可以各自设时间之后，这个键就错了——彩食鲜 08:00 跑完占掉 2026-08-30:MORNING，
-- 飞象 10:00 那次会被 begin() 判成「已被别人领走」直接返回，表现是**静默漏拉**：
-- 没有报错、没有失败记录、界面上一切正常，只是那个平台今天早上的单没进来。
-- 这正是本仓一直在防的故障模式，所以 run_key 必须变成 {日期}:{时段}:{渠道}。

-- 1) 渠道列。
--
-- 存量行怎么办：生产此刻只有 3 行（2026-08-29 早/晚、2026-08-30 早），它们**确实**是
-- 「一次运行拉全部渠道」的语义，不是「渠道未知」。所以回填哨兵值 'ALL' 是如实记录而不是
-- 编造——那三次运行的 pull_summary 里就躺着三个渠道各一行。
--
-- 为什么不用「可空 + 唯一约束里 COALESCE」：那样唯一约束的真实语义藏在索引表达式里，
-- 出事时看约束定义看不出「空到底代表什么」，而这张表的唯一约束就是防漏拉的那道闸，
-- 它的含义必须一眼可读。NOT NULL + 显式哨兵把这件事摆在表面。
--
-- DEFAULT 只用于这一次回填，随后立刻丢掉：新代码必须显式写明是哪个渠道在跑，
-- 漏传渠道应该当场炸出来，而不是被默认值悄悄兜成 'ALL' 又变回全渠道占坑。
ALTER TABLE app.scheduled_pull_runs
    ADD COLUMN source_channel VARCHAR(32) NOT NULL DEFAULT 'ALL'
        CHECK (btrim(source_channel) <> '' AND source_channel = upper(source_channel));

-- 2) 存量 run_key 补上渠道段，让「run_key = 日期:时段:渠道」这条不变式在全表成立。
--
-- 改历史行的 run_key 看着吓人，但这里是安全的：run_key 不是任何外键，也不是企微发卡的
-- 键（发卡按 domain + entity_id + lock_version），它只在卡面文案和审计负载里作为可读标识
-- 出现。不改的话，未来任何人按公式重算 run_key 去对存量行都会对不上，那种「公式和数据
-- 不一致」的坑比改这三行贵得多。
--
-- 正则限定只改「两段式」的老格式，因此本语句可重复执行不会叠加后缀。
UPDATE app.scheduled_pull_runs
SET run_key = run_key || ':ALL'
WHERE run_key ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}:[A-Z]+$';

ALTER TABLE app.scheduled_pull_runs
    ALTER COLUMN source_channel DROP DEFAULT;

-- 3) 「拉完推不推企微」记在运行行上，而不是发卡时回头读配置。
--
-- 理由是可追责：卡发了还是没发，取决于**那一刻**的配置，事后配置改了就再也复原不了当时
-- 的判断。把决定写进运行记录，运行行本身就说明了「这次为什么没发卡」。
--
-- 默认 TRUE：存量三行当时确实是会发卡的，升级不改变它们的含义；同时贯彻本特性的空值纪律
-- ——读不到配置一律按「照常拉、照常推」走，绝不让一个读失败变成安静停摆。
ALTER TABLE app.scheduled_pull_runs
    ADD COLUMN notify_wecom BOOLEAN NOT NULL DEFAULT TRUE;

-- 发卡扫描的偏索引（idx_scheduled_pull_runs_notify）刻意不动：notify_wecom 关掉是少数情况，
-- 把它塞进索引谓词只会让索引更窄一点点，却要 DROP/CREATE 一遍已上线的索引，不划算。

COMMENT ON COLUMN app.scheduled_pull_runs.source_channel IS
    '本次运行负责的来源渠道；哨兵 ALL 为 V85 之前的全渠道运行，新运行一律写具体渠道';
COMMENT ON COLUMN app.scheduled_pull_runs.notify_wecom IS
    '本次运行是否允许发企微播报卡；按触发那一刻的渠道配置固化，事后改配置不影响既有运行';
COMMENT ON TABLE app.scheduled_pull_runs IS
    '定时来源渠道拉取与自动发货的一次运行；run_key = 日期:时段:渠道，其唯一约束兼作跨实例单飞门禁';
