-- PostgreSQL 不会自动为外键引用列建索引；按来源批次删除/核对时需要该前导列。
-- V70 已属于历史迁移，必须通过追加迁移补齐，不能改写 V70。
CREATE INDEX idx_source_template_profiles_trusted_from_batch
    ON app.source_template_profiles(trusted_from_batch_id);
