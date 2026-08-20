-- 11（异步任务基建 + 定义域写端点）：async_tasks.payload_ref 承载任务载荷。
--
-- 既有 message-worker 的 payload_ref 是引用（submission:123 / slug:version:runId），
-- VARCHAR(512) 足够；T11 的 AGENT_DRAFT_CREATE 任务需要把完整草稿 JSON（system_prompt
-- 上限 32000 字符，05 门禁长度）随任务传递（12 决策 5：建草稿 = 202 任务内闭环，
-- 草稿落库发生在任务执行中，落库前无引用可指），512 字符放不下 → 放宽为 TEXT。
-- 不新增列、不改表结构其余部分；claim/succeed/fail 的查询不受列宽影响。
ALTER TABLE app.async_tasks
    ALTER COLUMN payload_ref TYPE TEXT;
