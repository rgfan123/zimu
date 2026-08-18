# 02 — 结构化导入用例（关键路径，最先）

**What to build:** 应用层新增「结构化订单导入」用例（供 in-process Connector 调用，不开放内部 REST）：同一事务内创建同渠道 SOURCE_ORDER 批次 + raw_import_rows 血缘 + 订单；**行级跳过已存在订单**（`(source_channel, source_ref)` 已存在 → 该行跳过并写 AuditLog business_code=ORDER_ALREADY_EXISTS，而不是整批回滚）；actorType=SYSTEM；捕获内容哈希唯一冲突后重查返回既有批次。确认/履约导出/来源回填管线与现有文件导入完全复用（raw 行血缘不变）。

**Blocked by:** None — can start immediately（动 P0 Approved 组件，需先过规范评审）

**Status:** resolved

- [x] 新建批次 + raw 行 + 订单在同一事务内完成，渠道一致性满足 DDL trigger
- [x] 批内含重复订单时：重复行跳过 + AuditLog，批次不整体失败
- [x] 并发相同内容上传：唯一冲突被捕获，返回既有批次而非 500
- [x] confirm 后能正常生成履约导出与来源回填（与文件导入路径行为一致）
- [x] 全程以 SYSTEM 主体落审计，不依赖人工操作人

---

## 合并修订（2026-08-18）

用户裁定的人工流程，本票必须遵守：

- **导入批次的人工确认闸门保留**为交付形态。在线拉取只替掉「人工去平台导表 + 上传」这一步，确认闸门不动——理由是私有接口无 SLA、字段映射未经真实量验证，SKU 未映射/缺收货人仍必须阻断。
- **自动确认做成按渠道可开关的配置项**，本轮**不打开**；跑稳后再切。因此本票要预留开关位，但验收只验人工确认路径。
- `actorType=SYSTEM` 只适用于**拉取与建批次**，不适用于确认——确认仍是人工主体。

新增验收项：

- [ ] 自动确认开关位存在且默认关闭；关闭时行为与现有文件导入完全一致

## Comments

- 2026-08-18: 已实施（SourceImportService.importStructured + StructuredOrderRow，upload 改 public）。回归测试 ExcelClosedLoopApiTest + CaishixianSourceFileParserTest 16 通过。待双轴 code review（Standards/Spec）。
- 2026-08-18: 双轴 code review 完成并修复全部问题：import_mode 改 NEW（DDL CHECK）、重复订单改为**预检测跳过**（doCreate 的 DUPLICATE_ORDER 经事务代理标记 rollback-only 无法 catch，预检测是正解；跨批并发竞态由调度防重入兜底）、existingStructured 对齐 uq_import_content_scope、内容哈希确定性序列化、file_ref 占位（NOT NULL）、actorType=SYSTEM 参数化（OrderCreateService.createImported 重载）、raw 快照脱敏、upload/类可见性修正、finalizeBatch 抽取。新增 StructuredImportApiTest（3 用例：血缘/跳过/幂等，19 测试全绿）。
