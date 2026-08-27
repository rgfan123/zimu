-- 履约导出的人读文件名（2026-08-27 用户裁决：第三方发货清单按历史实物命名，
-- 如「子牧黑猪肉8.27发货清单.xlsx」——见归档 8.21雷山黑猪发货清单.xlsx）。
-- 企微投递与后台下载都用它；为空的历史导出退回批次号命名，不回填。
ALTER TABLE app.fulfillment_exports
    ADD COLUMN IF NOT EXISTS display_filename varchar(120);
