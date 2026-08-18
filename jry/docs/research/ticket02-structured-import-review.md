# 票 02 评审材料：结构化订单导入用例（Structured Order Import Use Case）

状态：待评审（2026-08-18）
对应票：`.scratch/platform-online-integration/issues/02-structured-import-use-case.md`（关键路径，最先）
代码依据：`backend/src/main/java/cn/zimu/fulfillment/file/SourceImportService.java`（771 行，包私有）、`OrderCreateService`、DDL `V1__baseline.sql`、红队报告 §3/§5.2

## 1. 为什么需要

在线 Connector（票 07/08/09）拉到的订单必须进入 ImportBatch 闭环：`orders` 表 CHECK + trigger 强制非 WECOM 订单挂同渠道 `SOURCE_ORDER` 批次，且 confirm/履约导出/来源回填硬依赖 `raw_import_rows` 血缘。**现状没有**「不经过文件字节的结构化导入」入口：

- `SourceImportService` 包私有，唯一入口 `upload(byte[])` 走魔数+表头指纹解析
- `OrderCreateService.createImported` 是 public 但要求批次已存在，且 trigger 强制批次渠道一致
- `doCreate` 对 `(source_channel, source_ref)` 已存在抛 `DUPLICATE_ORDER` → 整批回滚（无行级跳过）
- 审计 actor 硬编码 HUMAN；`upload.existing()` 先 SELECT 后 INSERT（并发唯一冲突未捕获 → 500）

## 2. 改动设计（推荐方案 A：扩展现有服务，不新建平行类）

### 2.1 新增公开入口（放在 SourceImportService 内）

```java
// 结构化导入：批次 + raw 行血缘 + 订单 同一事务；供 in-process Connector 调用
public ImportBatchResult importStructured(
        SourceChannel channel, List<StructuredOrderRow> rows, String batchNo);
```

- `StructuredOrderRow`：`(sourceRef, sourceLineRef, canonicalInput, rawJson, carrierInfo…)`——Connector 的 transform 已产出 `CanonicalOrderInput`，此处不再做指纹/格式解析，只做：建批次 → 写 raw 行 → `orderCreateService.createImported`（复用现有映射后的校验：Schema→Business→Customer/SKU→重复/版本）
- **行级跳过**：捕获 `DUPLICATE_ORDER` 后该行跳过，写 `AuditLog(business_code=ORDER_ALREADY_EXISTS)`，批次继续（红队裁决：现状全仓无此码）
- **actorType=SYSTEM**：审计主体改为系统/Connector 身份（现硬编码 HUMAN 需参数化）
- **并发唯一冲突**：捕获 `uq_import_content_scope` 冲突后重查返回既有批次（修 upload.existing() 的竞态）

### 2.2 复用与最小改动

| 复用 | 现状 | 动作 |
|---|---|---|
| `canonical()` 行→CanonicalOrderInput 映射 | private | 结构化路径**不需要**（transform 已产出）；保留原样 |
| 批次创建 + raw 行写入 | upload 内私有逻辑 | **抽取**为私有方法供两个入口共用（upload 与 importStructured 行为一致，防止漂移） |
| `markReview`/`counts`/`importErrorCode` | private | 结构化路径复用（NEED_REVIEW 语义一致） |
| `upload` 可见性 | 包私有 | 改 public（红队：彩食鲜/飞象文件化 Connector 直接复用字节管线，1 行改动） |
| `createImported` | public 已有 | 不动 |
| DDL | — | **零改动**（批次/raw 行表、CHECK、trigger 均已存在且符合语义） |

### 2.3 不做的事

- 不开放内部 REST（`/internal/v1/orders` 保持 WECOM-only，铁律不动）
- 不改变 upload 现有文件导入行为（回归面=0）
- 不引入 JSON→Excel 绕路（已裁决删除）

## 3. 影响面与风险

| 项 | 评估 |
|---|---|
| P0 Approved 组件改动 | `SourceImportService` 增加方法 + 私有方法抽取；行为不变的回归点：文件导入（有现有测试基线） |
| 批次语义 | 新增路径批次渠道=调用方渠道，trigger 校验天然满足；batch_no 约定 `PULL-{channel}-{ts}` |
| raw 行血缘 | 结构化路径同样写 raw 行（status=ACCEPTED 才可 confirm）→ 履约导出/来源回填管线不变 |
| 幂等 | 行级跳过 + 批次级内容哈希 + 并发重查；批次级幂等键与上传一致 |
| 安全 | SYSTEM actor 需有明确身份配置；raw 快照脱敏入库 |
| 回归 | upload 路径零改动（仅可见性），跑现有导入测试套件验证 |

## 4. 验收（对应票 02）

1. 新建批次+raw 行+订单同一事务，渠道一致性满足 trigger
2. 批内含重复订单：跳过 + AuditLog，批次不整体失败
3. 并发相同内容：唯一冲突被捕获返回既有批次，非 500
4. confirm 后履约导出/来源回填与文件导入行为一致
5. SYSTEM 主体落审计

## 5. 请求评审结论

- [ ] 同意方案 A（扩展 SourceImportService）并放行实施
- [ ] 需要调整（指出改动点）
