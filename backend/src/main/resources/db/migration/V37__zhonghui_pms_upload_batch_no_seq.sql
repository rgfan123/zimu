-- 中汇 PMS 批量上传批次号原子流水（batch_no 唯一性来源；V35 未含，独立迁移避免已应用库校验失败）。
CREATE SEQUENCE app.zhonghui_pms_upload_batch_no_seq;
