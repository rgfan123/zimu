-- 来源回填文件在线推送状态（票 11 回传闸门）
-- 人工触发 + 幂等：同一回填文件版本只允许推送成功一次；PUSHING 防并发重推。
ALTER TABLE app.source_return_exports
    ADD COLUMN push_status VARCHAR(16) NOT NULL DEFAULT 'NOT_PUSHED'
        CHECK (push_status IN ('NOT_PUSHED', 'PUSHING', 'SUCCESS', 'FAILED')),
    ADD COLUMN push_started_at TIMESTAMPTZ,
    ADD COLUMN pushed_at TIMESTAMPTZ,
    ADD COLUMN pushed_by VARCHAR(128),
    ADD COLUMN push_platform_ref VARCHAR(128),
    ADD COLUMN push_error JSONB;
