-- 复核事项认领（企微卡片「我来处理」的落点）。
--
-- 为什么需要这两列：卡片上的「我来处理」此前无处可写——没有认领概念，点了只能
-- 记日志，而群里其他人看不见「已经有人在弄了」，于是要么无人管、要么两个人同时改。
-- 认领是这张卡唯一能承载的零参数幂等动作（处置动作 resolveCustomer/resolveSku
-- 都需要选客户、选 SKU，塞不进按钮）。
--
-- 认领不是处置：claimed_* 与 resolved_* 各自独立，认领后事项仍是 OPEN，
-- resolution_version 不推进——推进了会让卡上的版本断言立刻失效，
-- 认领人自己反而点不动后续按钮。

ALTER TABLE app.review_cases
    ADD COLUMN IF NOT EXISTS claimed_by  text,
    ADD COLUMN IF NOT EXISTS claimed_at  timestamptz;

-- 两列同生同灭：只有 claimed_by 没有 claimed_at 的行说不出「谁在什么时候认领的」，
-- 而审计恰恰要的是这两个一起。
ALTER TABLE app.review_cases
    ADD CONSTRAINT review_cases_claim_pairwise
    CHECK ((claimed_by IS NULL) = (claimed_at IS NULL));

ALTER TABLE app.review_cases
    ADD CONSTRAINT review_cases_claimed_by_not_blank
    CHECK (claimed_by IS NULL OR btrim(claimed_by) <> '');

-- 未认领的待办是运营要捞的那批；已认领的不该再混在里面。
CREATE INDEX IF NOT EXISTS review_cases_unclaimed_open_idx
    ON app.review_cases (created_at)
    WHERE status = 'OPEN' AND claimed_by IS NULL;
