# 15 — 发货结果卡随附「原始文件订单」与「系统整合后」两张截图

Type: implementation
Status: 已实现并合入 main 工作区（未提交）
Priority: P1
Requested: Jerry 2026-08-28「发货后，企业微信展示的图片信息加上原本文件的订单截图和 AI 整合后的截图，方便人员及时查看核对」

## 诉求

发货后那张卡现在只有文字（订单 → 发货批次 → 京东出库单 → 运单）。
运营要核对「平台原本要什么」和「我们实际发了什么」，得自己去翻文件和系统，
无法在企微里当场比对。

**加两张图**：一张是源文件里这一单的原样，一张是系统整合后的发货口径。
两张并排看，对不上一眼就能发现。

## 现成的地基（不要新造）

| 能力 | 位置 | 说明 |
|---|---|---|
| 随卡附件 | `WecomBusinessCardSource.attachments(entityId, entityVersion)` | **default 方法**，`ShipmentResultCardSource` 覆写即可 |
| PNG 表渲染 | `card/source/PendingListImageRenderer.java` | 已用于整批确认清单 |
| 发货后卡 | `card/source/ShipmentResultCardSource.java` | 就是要改的这张 |
| 原始文件行 | `app.raw_import_rows`（有 `order_id` 列） | 按订单直接取得到 |

### PII 关口已经天然过了，但要加一道显式守卫

`ShipmentResultCardSource.route()` 现在是：

```java
return configured.filter(route -> route.type() == RouteType.SINGLE);
```

**只进单聊，配了群聊一律不发**，注释写明「收货人姓名同样是客户信息」。
所以带收件信息的截图发在这张卡上符合既有纪律。

⚠️ 但 `attachments()` 与 `route()` 是两个方法，将来有人放宽路由，图片会跟着进群。
**要在 `attachments()` 内部再判一次路由类型：非 SINGLE 直接返回空列表**，
并留注释说明原因。`Route` 记录的注释本来就要求「群聊必须脱敏……由 source 在渲染时保证」。

## ⚠️ 需 Jerry 一句话确认：「AI 整合后」指什么

主导者的理解是 **系统整合后的发货口径**——即源文件行经模板解析 + SKU 映射 + 组合装展开
之后，我们实际要发的东西（order_lines：SKU 编码、商品名、规格、数量）。

另一种可能：指**消息解析那条 AI 链路**（`list_interpretations` / 订单草稿），
但那条只对企微人工下单有效，文件导入的彩食鲜订单走的是模板解析、没有 AI 参与。

**默认按前者实现。** 若 Jerry 指的是后者，图 B 的取数换成订单草稿即可，其余不变。

## 实现要点

### 图 A · 原始文件订单

- 取数：`SELECT raw_cells FROM app.raw_import_rows WHERE order_id = ?`
- **用「字段 | 值」纵向两列布局，不要横向宽表**。理由：各渠道模板列数差异大
  （彩食鲜 23 个键、中汇不同），横向表在手机上没法看；纵向表天然适配任意模板。
  仓库里 `ProductArchiveSheetDrawer` 的「列|字段|值」三栏就是同款思路。
- **空值单元格跳过**，只渲染有值的字段。实测彩食鲜一行 23 个键里有
  `发货数量/物流单号/采购单号/错误原因/物流公司代码` 等多个恒空，全渲出来是噪音。
- 标题标明来源：`原始文件 · <source_channel> · <original_file_name>`
- 一单可能对应多行原始行（多商品），逐行分组渲染
- **没有原始行的订单**（企微人工创建单）：不出图 A，不报错，只出图 B

### 图 B · 系统整合后

- 取数：该订单的 `order_lines`（SKU 编码、商品名、规格、单位、请求数量、组合装标记）
  + 收件信息 + 发货批次/京东出库单/运单号
- 横向表可行（列固定），可复用 `PendingListImageRenderer` 的范式
- 标题：`系统整合后 · 实际发货口径`

### 渲染器要泛化

`PendingListImageRenderer.COLUMN_WIDTHS`（`:37`）是**写死的 7 列数组**
`{44, 150, 84, 112, 330, 320, 54}`，只服务整批确认清单那一种形状。

**把列宽改成入参**（或加一个纵向两列的渲染方法），
**不要改动整批确认清单现有的调用结果**——那张图已上线，像素级行为要保持。
有测试固定住旧调用的输出尺寸。

### 版本纪律

`attachments()` 的既有约定是「按当前事实即时生成，事实已变时返回空列表」。
**照办**，不要缓存图片、不要在事实变更后仍投旧图。

### 体积

`WecomMediaType.IMAGE` 上限 **10 MB**，格式 png/jpg/jpeg/gif。
纵向表在超长订单（几十行原始数据）时可能很高——
**设行数上限，超出时截断并在图上明写「已截断，完整数据见系统」**，
不要静默截断，也不要生成一张十米高的图。

## 不做的事

- 🚫 不改发货后卡的文字卡面与路由策略
- 🚫 不改整批确认清单那张图的现有输出
- 🚫 不把图片发进群聊（`attachments()` 内显式守卫）
- 🚫 不动 `raw_import_rows` 的数据
- 🚫 不执行任何生产 SQL

## Acceptance Criteria

- [ ] 发货后卡随附两张 PNG：`原始文件订单` 与 `系统整合后`
- [ ] 图 A 纵向「字段|值」，跳过空值，标题含渠道与原文件名；多原始行分组渲染
- [ ] 无原始行的订单只出图 B，不报错
- [ ] `attachments()` 内显式判路由：非 SINGLE 返回空列表，有测试覆盖
- [ ] 行数超限时图上明写「已截断」，且图片 < 10 MB，有测试
- [ ] `PendingListImageRenderer` 泛化后，**整批确认清单的既有输出零变化**（有测试固定尺寸）
- [ ] 事实已变时 `attachments()` 返回空列表（沿用既有版本纪律）
- [ ] 定向测试通过；不跑全量套件

## Files likely affected

- `backend/src/main/java/cn/zimu/fulfillment/connector/wecom/card/source/ShipmentResultCardSource.java`
- `backend/src/main/java/cn/zimu/fulfillment/connector/wecom/card/source/PendingListImageRenderer.java`
- 可能新增一个纵向表渲染方法/类
- 对应测试

## 工作区纪律

禁 `git add -A` / `commit` / `checkout|restore|stash`。
本票与票 06/07/09/11/12 文件面无重叠，**可与它们并行**（在独立 worktree 里跑）。

## Risk

中。风险在两处：
1. **PII**——图里有收件信息，必须守住单聊；`attachments()` 内的显式守卫是硬要求。
2. **泛化渲染器碰坏既有那张图**——整批确认卡已上线，必须有测试锁住旧行为。
