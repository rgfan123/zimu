-- 模型尝试耗尽后，先持久化可恢复的收口意图；任务 FAILED 与 NEED_REVIEW 仍在同一事务内提交。
ALTER TABLE app.async_tasks
    DROP CONSTRAINT async_tasks_status_check;

ALTER TABLE app.async_tasks
    ADD CONSTRAINT async_tasks_status_check
    CHECK (status IN ('PENDING', 'RUNNING', 'FINALIZING', 'SUCCEEDED', 'FAILED'));

DROP INDEX app.idx_async_tasks_due;

CREATE INDEX idx_async_tasks_due
    ON app.async_tasks (status, next_run_at, id)
    WHERE status IN ('PENDING', 'RUNNING', 'FINALIZING');
