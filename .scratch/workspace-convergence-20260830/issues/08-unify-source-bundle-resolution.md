# 08: 统一来源礼包解析接缝

**What to build:** 文件导入、平台 API 拉单和人工礼包解析使用同一个来源礼包接缝与稳定来源键；
ID 映射始终权威，名称键只兼容上游没有稳定 ID 的历史输入，同时让新的来源 SKU 引用成为
可审计事实。

**Blocked by:** 01: 建立隔离收敛来源账本与只读门禁。

**Status:** completed

- [x] 三种入口对同一来源引用产生同一礼包解析结果。
- [x] 现有 ID 键与“上游未提供稳定 ID”时的 legacy 名称键继续工作；一旦存在稳定 ID，
  不降级按名称自动匹配。ID 礼包/SKU 冲突、多礼包映射和 inactive BOM 均失败关闭。
- [x] 新数据库变化使用高于当前基线的追加式迁移，历史迁移保持不变。
- [x] schema 快照、迁移兼容与业务 API 契约同步。
- [x] 纯编译与真实 PostgreSQL/Testcontainers 验收状态分别报告，并形成独立提交。

**Verification:** `test-compile` 通过；文件/结构化导入、万旗/万齐/飞象、人工礼包解析
相关 Testcontainers 套件通过；V47 生产历史前向升级到 V89 与 schema 快照等价测试通过。
新增非整数数量护栏、结构化多商品混合履约血缘、人工 mixed-provider 分片、inactive BOM
全链拒绝和 `source_sku_ref` 分配后不可变测试；权威礼包不会降级成可发单品或残缺 BOM。
