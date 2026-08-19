# 03 — 渠道订单中礼包的形态与识别（研究结论）

状态：research 定稿
日期：2026-08-19
所属 effort：`.scratch/static-bundle-bom/map.md`（静态礼包 BOM 主数据实现方案）
权威边界：`待发货订单-测试/` 四份真实样本、`docs/excel-closed-loop-spec.md`、`docs/api-contract.md`、`CONTEXT.md`、`docs/schema.md`、`docs/schema.sql`、`backend/src/main/java/cn/zimu/fulfillment/{file,order,message,sku}` 实现代码、`大者国风上架品（内容详情）-202605更新(1).xlsx`（礼包主数据来源，ticket 01 详析）。

---

## 1. 样本形态证据（真实行示例）

### 1.1 结论速览

- **四份真实样本里没有任何礼包订单行**：逐格扫描「礼包/组合/套餐/礼盒/箱/套/组/盒」关键词，唯一的「礼包」命中是聚福宝表头的**「礼包名称」列名**，该列在两行数据里均为空。因此礼包识别是**面向未来的前瞻设计**，依据是礼包主数据文件（36 个礼包）而非渠道样本。
- **映射键**：彩食鲜/中汇有「商品编号」（数字码），聚福宝有「商品ID + 商品条码（真实 EAN-13）+ 商品编码（样例为空）+ 礼包名称（样例为空）」，飞象有「商品ID」（「商品货号」「商品规格」列存在但样例为空）。
- **商品名称统一带规格后缀**：`子牧牛腱子(谷饲牛腱子)500g*2` 一类，规格 `*N` 是来源包装乘数的直接来源（`JdPiecesCandidateParser` 正可解析）。
- **礼包主数据名称形态与渠道行不同**：主数据名称含「礼包/礼盒/组合/套餐」关键词 + 重量规格 + 「（BJ）」后缀，且全/半角括号混用（`牛肉大礼包5200g （BJ）`、`牛肉礼包6200g（BJ）`、`牛羊礼包5800g (BJ)`、`火锅组合套餐礼盒 2050g   (BJ)`）——这正是规范化匹配要处理的差异。

### 1.2 逐渠道表头与真实行

#### 彩食鲜（`彩食鲜待发货订单.xlsx`，Sheet `0`，7 行，22 列）

表头：`主订单编号 | 子订单编号 | 采购单号 | 供应商编码 | 站点编码 | 收货人 | 联系电话 | 省 | 市 | 区 | 详细地址 | 物流要求编码 | 物流要求名称 | 商品编号 | 商品名称 | 下单数量 | 订单备注 | 发货数量 | 物流公司代码 | 物流单号 | vip订单标识 | 错误原因`

真实行（商品编号 | 商品名称 | 下单数量）：

```
2047705 | 子牧牛腱子(谷饲牛腱子)500g*2 | 1
2047848 | 子牧羊腿肉500g*2               | 1
2066622 | 子牧雷山黑猪五花肉450g*2       | 1
2066578 | 子牧雷山黑猪仔排450g*2         | 1
2047778 | 子牧筋头巴脑（牛肉筋）500g*2   | 1
```

- **条码列：无**；**编码列：有（`商品编号`，数字码，如 `2047705`）**。
- 注意 `2047705` 在样本中出现两次（不同订单），说明渠道按自己的商品编号标识，同一编号可跨订单复用——`source_sku_ref` 就用它。

#### 聚福宝（`聚福宝待发货订单.xlsx`，`sheet1`，9 行，38 列；第 4 行起为「供应商汇总」区，解析到此停止）

表头（截取商品相关）：`主单号 | 拆单号 | 订单备注 | 订单总额 | 下单时间 | 支付时间 | 发货时间 | 订单状态 | 收货地址 | 收货人电话 | 收货人姓名 | 扩展信息 | 商品名称 | 商品规格ID | 商品规格 | 商品ID | 商品编码 | 商品条码 | 数量 | 进货价（单价） | 市场价（单价） | 礼包名称 | 快递费 | 供货商 | 渠道订单号 | …`

真实行（商品名称 | 商品规格 | 商品ID | 商品编码 | 商品条码 | 数量 | 礼包名称）：

```
乔府大院金饭碗五常大米5kg            | (空) | 66605101 | (空) | 6937004413052 | 1 | (空)
yosibaby/羊小贝山羊奶整箱200ml*10盒  | (空) | 66662134 | (空) | 6975334570755 | 2 | (空)
```

- **条码列：有且真实**（`6937004413052` 为 EAN-13）；**编码列：`商品ID` 有值、`商品编码` 样例为空**；**「礼包名称」列存在但样例为空**——这是唯一一个渠道在表头层面预留礼包标识的渠道，识别礼包时的强信号。
- 聚福宝每格带尾随制表符，解析时已 `replaceFirst("[\\t\\s]+$", "")` 清理（`SourceFileParser.jufubao`）。

#### 飞象（`飞象待发货订单.csv`，实为**误命名 XLSX**，2 行，21 列；真实 CSV v2 见 spec §3.3）

> 重要修正：本样本首字节是 `PK`（ZIP/OOXML 魔数），实际是一份 **XLSX 改名 .csv**，不是文本 CSV，也不是 GBK 编码——与 `docs/excel-closed-loop-spec.md` §3.3「历史 `批量发货1786435269.csv` 实际是误命名 XLSX」一致，属于 v1 兼容输入。表头指纹命中飞象 v1（`订单号 | 订单商品ID | 可发货数量 | 物流状态 | 物流公司 | 物流单号`）。任务说明里「飞象 CSV 编码可能是 GBK」适用于未来平台原始 40 列真 CSV（v2，GB18030 兼容、逗号分隔、LF），本样本不受影响。

表头：`订单号 | 会员名称 | 商品名称 | 商品ID | 商品货号 | 商品规格 | 订单商品ID | 可发货数量 | 成本价/协议价 | 会员价 | 订单状态 | 售后状态 | 物流状态 | 购买人账号 | 收货人姓名 | 收货人手机号 | 收货人地址 | 下单时间 | 物流公司 | 物流单号 | 备注`

真实行（商品名称 | 商品ID | 商品货号 | 商品规格 | 可发货数量）：

```
子牧原切羊排块（羊寸排）500g*2 | 6629889 | (空) | (空) | 1
```

- **条码列：无**；**编码列：`商品ID` 有值、`商品货号`/`商品规格` 列存在但样例为空**。

#### 中汇（`中汇待发货订单.xlsx`，Sheet1，6 行，25 列；同一订单号多商品行同属一单）

表头（商品相关）：`订单号 | 下单时间 | 支付时间 | 商品编号 | 商品名称 | 税率 | 一级分类 | 二级分类 | 三级分类 | 订单状态 | 商品状态 | 件数 | 商家单价 | … | 包装规格 | 单位`

真实行（商品编号 | 商品名称 | 包装规格 | 件数 | 单位）：

```
60043823 | 子牧澳洲谷饲上脑牛肉片1KG            | 1KG             | 4 | 份
60043825 | 子牧澳洲纯种和牛牛肉饼200g*4（八片）  | 200g*4（八片）   | 1 | 份
60043832 | 子牧牛后腿肉500g*2                    | 500g*2          | 1 | 份
60043849 | 子牧原切纯肉羊腿肉500g*2              | 500g*2          | 1 | 份
60043845 | 子牧羊小腿（羊腱子）500g*2             | 500g*2          | 1 | 份
```

- **条码列：无**；**编码列：有（`商品编号`）**；与别渠道不同，**规格独立成列（`包装规格`）**，商品名称列更干净。

### 1.3 渠道映射键总结

| 渠道 | 现用 `source_sku_ref`（`SourceFileParser`） | 可用映射键（样例实测） | 条码列 | 礼包专属信号 |
|---|---|---|---|---|
| 彩食鲜 | `商品编号`（`value`，如 `2047705`） | 商品编号 | 无 | 无 |
| 聚福宝 | `first(商品ID, 商品编码, 商品条码)`（`first` 取首个非空） | 商品ID、商品条码（EAN-13 真实）、商品编码（空） | **有** | **「礼包名称」列**（样例为空） |
| 飞象 | `first(商品ID, 订单商品ID)` | 商品ID、商品货号（空）、商品规格（空） | 无 | 无 |
| 中汇 | `商品编号`（如 `60043823`） | 商品编号、包装规格（独立列） | 无 | 无 |

**结论：映射键首选渠道商品编号/ID（ref 精确匹配），条码仅聚福宝可用作**（且礼包主数据 `大者国风上架品` 每礼包带 `商品条码`，如 `9250522000028`——聚福宝条码 → 礼包条码是天然强键）。名称/别名匹配作为 ref 缺失或 ref 未建映射时的兜底（见 §3）。

### 1.4 礼包主数据名称形态（识别要面对的输入差异）

来源：`大者国风上架品（内容详情）-202605更新(1).xlsx`（ticket 01 负责合并定稿，这里只列识别相关的形态）：

```
牛肉大礼包5200g （BJ）            ← 名称含「礼包」+ 重量规格 + 全角括号 + 空格
牛肉大礼包6200g(BJ)               ← 半角括号、无空格
牛肉礼包6200g（BJ）               ← 全角括号、无空格
牛羊礼包5800g (BJ)                ← 半角括号、有空格
火锅组合套餐礼盒 2050g   (BJ)      ← 同时含「组合/套餐/礼盒」+ 多空格
```

- 组件行（165 个）名称如 `牛腩块500g`、`牛腱子(谷饲牛腱子)500g*2`、`澳洲谷饲牛肋排40…`——与渠道单 SKU 名称风格高度一致（渠道名多带「子牧」前缀、组件名不带）。这意味**组件名 → internal_skus 可部分复用渠道 SKU 名匹配经验，但 95 个组件缺 EMG 编码**（ticket 02/01 范围）。
- 识别侧必须处理的差异：**（BJ）后缀、全/半角括号、空白位置、重量单位写法**。

---

## 2. 现有识别链路（改造前基线）

### 2.1 Excel 链路：来源行 → `source_channel_skus` 显式映射 → internal_skus

```
SourceFileParser.parse(字节)            # 魔数 PK→XLSX；表头指纹唯一命中渠道；逐行 map()
  └─ per-channel 投影：source_sku_ref / product_name / specification / quantity …
     （caishixian:189 value(商品编号)；jufubao:202 first(商品ID,商品编码,商品条码)；
       feixiang:214 first(商品ID,订单商品ID)；zhonghui:227 value(商品编号)）
SourceImportService.upload → canonical()     # LineType.SINGLE，构造 OrderItemInput(sourceSkuRef, productName…)
  └─ OrderCreateService.createSingleLine(480)
       └─ findMapping(channel, sourceSkuRef)(596)   # source_channel_skus 精确匹配
            ├─ 命中且 active 且 multiplier>0 → 内部 SKU → READY_TO_EXPORT
            └─ 未命中 → NEED_REVIEW + SKU_MAPPING_REQUIRED（ReviewCase，SKU_MATCH）
```

要点：
- **Excel 侧只按 `source_sku_ref` 精确匹配**（`OrderCreateService.findMapping`，600-606 行），商品名称/规格仅作快照，不参与匹配；且**无任何规范化**（无 NFKC、无全半角折叠、无后缀剥离）。
- 手工确认走 `ReviewCaseResolutionService.resolveSku`（203-270 行）：按 `(source_channel, source_sku_ref)` upsert `source_channel_skus`（`source_product_name` 取订单行快照、`quantity_multiplier` 必填），随后推进订单行。
- 乘数缺失/为 0/非法 → `MAPPING_MULTIPLIER` ReviewCase，禁止默认按 1（spec §6.1）。

### 2.2 企微链路：模型原值 → 确定性 SKU 候选 → 草稿 + 复核

```
MessageInterpreter（模型）→ InterpretationResult.items[{product, spec, unit, quantity, source_sku_ref}]
  └─ WecomOrderDraftFactory.createDrafts → newLine(151)
       └─ skuCandidates(ref, productName)(199)   # SQL(47-59)：WECOM 渠道
            # scs.source_sku_ref = ? OR scs.source_product_name = ?  （精确相等，channel='WECOM'）
       ├─ 1 个候选 → 形成候选（不自动确认）
       ├─ 0 / ≥2 个 → missing_fields 记 line_{n}_sku，走同一开放复核事项
       └─ 每个草稿恰好 1 个 ORDER_OPS/OPEN ReviewCase（WECOM_ORDER_DRAFT，CONTEXT.md）
```

要点：
- 企微比 Excel 多一种匹配维度：`source_product_name` 精确相等（仅 WECOM 渠道维护的映射名）。
- 模型只提原始描述，**候选一律由确定性映射生成，模型不得猜业务身份**（CONTEXT.md OrderDraft）。
- `candidateStatus`：`UNIQUE_HIT / ZERO_HIT / MULTI_HIT`（358-360 行），`missingFields`（235-264 行）驱动复核展示。

### 2.3 现有可复用机制盘点（识别侧）

| 机制 | 位置 | 复用方式 |
|---|---|---|
| `source_channel_skus` 显式映射表 | `schema.sql`/`SourceChannelSku.java` | 复制其模式到 `source_channel_bundles` |
| `findMapping` 精确 ref 匹配 + 激活/乘数门禁 | `OrderCreateService:596` | 礼包映射同款门禁 |
| `createBundleLine` 组件展开全逻辑 | `OrderCreateService:516-571` | **静态礼包命中后组件解析/快照/同 provider/缺映射即复核全部复用** |
| ReviewCase 队列 + `resolve-sku` 人工闭环 | `ReviewCaseResolutionService` | 新增 `resolve-bundle` 镜像 |
| 企微草稿候选框架（UNIQUE/ZERO/MULTI、missing、单一复核、疑似重复指纹） | `WecomOrderDraftFactory` | 扩展 `bundle_candidates` 分支 |
| `sku_aliases` 别名表 | `schema.sql:145`（`alias_type` NAME/BARCODE/SPECIFICATION/OTHER） | 复制为 `bundle_aliases` |
| `JdPiecesCandidateParser` 规格 ×N 解析 | `sku/JdPiecesCandidateParser.java` | 礼包名里的 `5200g` 类重量不适用 ×N；组件规格可能用到 |

---

## 3. 识别链路改造方案

### 3.1 推荐识别顺序：**先礼包、后 SKU**（礼包优先）

对每一条来源行（Excel 与企微一致）：

```
0. 规范化输入（§3.3）：
   ref   = 渠道商品编号/ID/条码（按渠道取列，聚福宝优先商品ID→条码）
   name  = 商品名称（NFKC、去空白、去（BJ）后缀）
   barcode = 渠道条码（聚福宝）
1. 礼包识别（先）：
   a. 显式礼包映射命中：source_channel_bundles 按 (channel, source_bundle_ref=ref) 精确匹配
      ——覆盖「渠道把礼包当普通商品给编号」的行，与 SKU 映射同构；
   b. 信号列命中：聚福宝「礼包名称」非空 → 直接进礼包名称匹配（1c）；
   c. 名称/别名命中：name 含礼包关键词（礼包|礼盒|组合|套餐，配置化）
      且规范化后精确等于某礼包 canonical name 或别名/条码 → 命中；
      唯一命中 → 礼包行；零/多命中 → NEED_REVIEW（BUNDLE_MATCH），不继续降级；
   d. 不含礼包关键词且无显式礼包映射 → 视为普通商品，走 2；
2. SKU 识别（现状原样保留）：
   source_channel_skus 按 ref 精确匹配（Excel）；ref 或 source_product_name 精确匹配（企微）；
   命中 → SINGLE；未命中 → NEED_REVIEW（SKU_MATCH，现状不动）。
```

**理由**：
- 礼包命中必须优先：礼包行在渠道侧也是一个「商品」行（有商品编号/名称/数量），若先走 SKU 映射，礼包会映射成单 SKU，组件展开语义全部丢失；而礼包主数据是自家数据、名称含强关键词，先识别是安全的。
- **用关键词 + 显式映射做前置闸门，避免普通 SKU 误伤**：普通 SKU 名称（`子牧牛腱子(谷饲牛腱子)500g*2`）不含礼包关键词，直接落到 2，现状行为零回归。
- 关键词只是**闸门**，不是匹配依据：通过闸门后仍要求**规范化精确命中**（canonical name / 别名 / 条码），杜绝模糊包含匹配的误命中（如「火锅」出现在普通品名里）。

### 3.2 映射表设计

**a) 渠道礼包映射表 `source_channel_bundles`**（复制 `source_channel_skus` 模式，新建表，ticket 02 落 DDL）：

| 列 | 说明 |
|---|---|
| `source_channel` | 渠道枚举 |
| `source_bundle_ref` | 渠道礼包编号/ID（= 该行 ref，如聚福宝商品ID、彩食鲜商品编号） |
| `source_bundle_name` | 渠道侧礼包名快照 |
| `source_barcode` | 渠道条码（聚福宝），可空 |
| `bundle_id` | 静态礼包主数据标识（FK，ticket 02 定义） |
| `quantity_multiplier` | 见 §5（一期恒 1） |
| `active` / `lock_version` | 同 `source_channel_skus` |

- **用途**：渠道已把礼包上架成独立「商品」时（有编号/条码），这是主匹配键——精确、无歧义，且天然处理「渠道改名了但编号没变」。
- 与 `sku_aliases` 的关系：`source_channel_bundles` 是**业务映射**（命中即可履约），`bundle_aliases` 只是**检索候选**，两级职责沿用现网 `source_channel_skus` / `sku_aliases` 的分工。

**b) 别名挂在礼包主数据上（推荐）**，而非独立渠道别名表：

- 礼包是**自家主数据**，渠道只是换个写法卖同一礼包；别名本质是「同一个礼包的不同叫法」，属于主数据属性（类似 `sku_aliases` 挂 `skus`）。
- 新建 `bundle_aliases(bundle_id, alias_type[NAME|BARCODE|SPECIFICATION|OTHER], alias_value, active)`，唯一约束 `(bundle_id, alias_type, alias_value)`。
- 别名直接服务 §1.4 的差异：`牛肉大礼包6200g(BJ)`、`牛肉礼包6200g（BJ）`、`牛肉大礼包6200g` 挂同一 bundle 的多条 NAME 别名；聚福宝条码/大者条码挂 BARCODE 别名（或主数据条码字段）。
- 渠道特有叫法（如飞象的「子牧…」前缀版）也进 `bundle_aliases`，不建渠道维度别名表——避免三张映射表各自膨胀。**识别时按 `bundle_aliases`（含 canonical name）全局精确匹配，命中后渠道映射（§3.2a）仍可再覆盖乘数/ref。**

### 3.3 名称匹配规则（规范化 + 精确命中，不做模糊）

匹配发生在「规范化后的输入名」对「规范化后的 canonical name + 全部别名（+ 条码）」：

1. **规范化**（复用 `Normalizer.normalize(value, NFKC)`，与 `SourceFileParser.normalizeHeader` 同源）：
   - NFKC：折叠全/半角（`（BJ）`→`(BJ)`、全角数字→半角）；
   - 去所有空白（含名称内/尾随空格，`火锅组合套餐礼盒 2050g   (BJ)` → 连续）；
   - 剥离尾部 `(BJ)` 变体（`(BJ)`、`（BJ）`、` (BJ)`）→ 仅作别名键之一，**canonical 名保留原始带 (BJ) 版本**，两侧同时归一（都带或都不带均需命中——最稳妥：规范化为「无 (BJ) 版本」为主键，带 (BJ) 版作为别名）；
   - **保留重量规格数字**（`5200g` 是礼包身份一部分：`牛肉礼包6200g` ≠ `牛肉大礼包5200g`），不做规格剥离；重量单位写法差异（`5.2kg` vs `5200g`）用别名覆盖，不做单位换算猜测。
2. **命中判定顺序**（同一套规范化键）：
   1. `source_channel_bundles.source_bundle_ref` = ref（显式映射，§3.2a）——ref 存在且命中，直接定案；
   2. `bundle_aliases.BARCODE` = 渠道条码（聚福宝）；
   3. `bundle_aliases.NAME`（含 canonical name）= 规范化名称；
   4. 全部未命中 → 名称含关键词 → `NEED_REVIEW(BUNDLE_MATCH)`；不含关键词 → 走 SKU。
3. **零/多命中语义**：
   - 唯一命中 → 礼包（唯一性 = 该规范化键在 canonical+aliases 中全局恰好 1 条 active）；
   - 多命中（别名撞车）→ `NEED_REVIEW(BUNDLE_MATCH)`，ReviewCase 带候选列表，人工选；**不自动取第一条**；
   - 名称含关键词但零命中 → `NEED_REVIEW(BUNDLE_MATCH)`（疑似渠道新礼包/主数据缺失），不降级成 SKU 猜测。

### 3.4 识别代码落点（改造接缝）

- 新增共享接缝 `BundleRecognitionService`（应用层用例，Excel 与企微都调用，符合 spec「Adapter 与未来 API Connector 共用应用层用例」）：
  - `resolve(channel, ref, name, barcode, spec)` → `BundleCandidate | empty`，内部实现 §3.1/§3.3 顺序；
  - `snapshotComponents(bundleId)` → 主数据 BOM → `List<BundleComponentInput>`（`sku_id`、`quantity_per_bundle`、名称/规格/单位快照、EMG 经 `provider_skus` 解析）。
- Excel：`SourceImportService.canonical()`（645-676 行）构造 `OrderItemInput` 前先调 `BundleRecognitionService`：命中 → `LineType.CUSTOM_BUNDLE` + components；未命中 → 现状 `SINGLE`。`OrderCreateService.createBundleLine` 已实现组件解析/缺映射/同 provider/快照，直接复用。
- 企微：`WecomOrderDraftFactory.skuCandidates`（199-224 行）旁新增 `bundleCandidates(ref, name)`；`OrderDraftLine` 增 `bundle_candidates` JSONB（或把 `sku_candidates` 泛化为带 `matched_by` 类型的候选列表，ticket 02 定 DDL）；`missingFields`/`candidateStatus`/`buildCase` 对称扩展。

---

## 4. 企微链路扩展（OrderDraft 候选生成 → 快照 BOM）

现状（§2.2）是：模型原值 → `skuCandidates`（ref/name 精确）→ 唯一命中成候选 / 零多命中进复核。

扩展为「**名称 → 礼包候选 → 快照 BOM**」：

1. 模型结构化输出 `items[].product` 即礼包名（自由文本，如「来一盒牛肉大礼包 5200g」），`source_sku_ref` 企微通常没有 → 名称匹配为主路径。
2. `bundleCandidates` 按 §3.3：规范化 → 关键词闸门 → canonical/别名/条码精确命中：
   - 唯一命中 → 候选 `{bundle_id, bundle_name, component_count, matched_by, missing_emg_count}`；
   - 零/多 → `candidateStatus = ZERO_HIT/MULTI_HIT`，`missing_fields` 记 `line_{n}_bundle`（沿用现有行级缺失项机制），单一 `ORDER_OPS` 复核事项展示候选/缺失。
3. **快照 BOM**：草稿行人工确认命中礼包后，成单时 `OrderCreateService.createBundleLine` 从主数据快照组件到 `order_line_components`（`total_quantity = 礼包份数 × quantity_per_bundle`，触发器校验），主数据后续修改不改写历史（map.md 已定）。
4. **礼包缺 EMG 组件时的草稿/复核展示**：组件缺 `provider_skus`（EMG）映射 ≠ 礼包不可识别，草稿照常显示礼包名 + 组件名/数量清单；复核事项 detail 增加 `missing_emg_components: [{component_name, quantity_per_bundle}]`（沿用现有 `WECOM_ORDER_DRAFT` 单事项、`detail` 白名单结构）。缺 EMG 的组件在成单后使该行停在 `NEED_REVIEW`（`createBundleLine` 缺映射分支已有 `SKU_MAPPING_REQUIRED` 语义，静态礼包场景由主数据侧保证组件 `sku_id` 已解析，仅 EMG/provider 映射缺失 → 行级 `NEED_REVIEW` + 复核，不阻塞同单其他行）。
5. 企微一期仍**人工确认后才成单**（CONTEXT.md OrderDraft），礼包候选不改变该原则。

---

## 5. 来源包装乘数：礼包在渠道的适用性

**结论：礼包行 `quantity_multiplier` 一期恒为 1，但保留列、约束为正整数，不物理删除。**

依据与建议：

- 乘数语义（CONTEXT.md `SourcePackMultiplier`）：「一个来源平台销售单位包含多少件 Canonical SKU」。对礼包，渠道卖 **1 份 = 1 份礼包**，组件数量由 BOM 的 `quantity_per_bundle` 表达，展开发生在组件层（`组件导出数量 = 礼包份数 × 单份用量`，spec §6.2 同款），**不存在「一份渠道礼包含多份礼包」的乘数**。
- 但渠道存在「组合销售」的变体（如 2 盒装、双份装）时，正确做法是**新建一个礼包主数据条目或渠道映射行**，而不是用乘数 2 复用同一礼包——乘数若允许 >1 会在导出、回填、完整份数校验上引入歧义。
- 因此：`source_channel_bundles.quantity_multiplier` 保留，**CHECK 为正整数，一期人工维护时固定 1**；缺失/为 0/非正整数 → `NEED_REVIEW`（与 `source_channel_skus` 门禁一致，spec §6.1，禁止默认按 1 处理）；下单快照乘数，后续修改不改写历史（沿用 `source_quantity_snapshot` × `mapping_multiplier_snapshot` = `requested_quantity` 约束）。
- 中汇 `件数`、聚福宝 `数量`、彩食鲜 `下单数量` 都是「来源份数」口径（礼包份数），无需换算；礼包回填给渠道的仍是来源份数（`SourceReturnExport` 现有规则不变）。

---

## 6. NEED_REVIEW 分支与处置

| 场景 | 分支 | 处置（复用机制） |
|---|---|---|
| 名称含礼包关键词但主数据零命中（渠道疑似新礼包） | `BUNDLE_MATCH`（新 reason，镜像 `SKU_MATCH`） | 新建 ReviewCase（`case_type=BUNDLE_MAPPING`，`responsible_team=SKU_OPS` 或新 `BUNDLE_OPS`），detail 带原值行 + 候选空；人工在主数据侧建礼包/别名后 `resolve-bundle` 关闭（镜像 `resolve-sku`） |
| 多命中（canonical/别名撞车） | `BUNDLE_MATCH` | ReviewCase 带候选列表（bundle_id + 名称 + 组件数），人工选；**不自动取第一条** |
| 显式礼包映射存在但组件 BOM 缺失/空 | `BUNDLE_MATCH`（或主数据侧阻断） | 行 `NEED_REVIEW`；主数据质量属于 ticket 02 建表约束（BOM 至少 1 组件、同 provider） |
| 组件缺 EMG/provider 映射 | 行级 `NEED_REVIEW`（沿用 `createBundleLine` 缺映射分支） | 同单其他行不阻塞；ReviewCase detail 列缺映射组件名；`provider_skus` 补齐后重试（沿用现有履约门禁 `ShipmentJdSkuMappingGateService`） |
| 跨 provider 组件（主数据脏数据） | `BUNDLE_MIXED_PROVIDERS`（现成） | 建表约束防入（ticket 02），运行时仍校验（`createBundleLine:547-552`） |
| 渠道乘数缺失/非法（礼包） | `MAPPING_MULTIPLIER`（现成 reason） | 与 SKU 一致，禁止默认按 1 |
| 企微草稿礼包候选零/多命中 | 不建单；`missing_fields` + 单一 `ORDER_OPS` 复核（现成） | 与 SKU 候选完全同构，仅字段名 `line_{n}_bundle` |
| 礼包行成单后缺 EMG 且未补 | 行停 `NEED_REVIEW`，批次确认被阻断（`validateSourceBatchExportability` 现成） | 与 SKU 缺失同路径，不新增机制 |

- **结论：全部 NEED_REVIEW 分支复用现有 ReviewCase 机制**（队列、原因码白名单、证据 detail、候选展示、`resolve`/`dismiss` 幂等与审计、批次确认阻断），只需新增 `BUNDLE_MATCH` 原因码 + `resolve-bundle` 人工命令（镜像 `resolve-sku`，另见 `docs/api-contract.md` §4.4 白名单模式）。

---

## 7. 可复用机制清单（实现时直接引用）

1. **`source_channel_skus` 映射模式** → `source_channel_bundles`（唯一键、active、lock_version、乘数门禁逐条照搬）。
2. **`OrderCreateService.createBundleLine`（516-571）**：静态礼包命中后的组件解析、缺映射→NEED_REVIEW、同 provider 校验、`order_line_components` 快照与 `total = 份数 × 单份用量` 约束、触发器（`schema.sql` 1450+：组件只属于 CUSTOM_BUNDLE、不可变、同 provider、总量守恒）——**全部原样复用，静态礼包只改变「组件从主数据来」这一件事**。
3. **`ReviewCaseResolutionService.resolveSku`（203-270）** → 镜像 `resolve-bundle`；ReviewCase 队列/白名单/幂等/审计框架不动。
4. **`WecomOrderDraftFactory` 候选框架**：`candidateStatus`（UNIQUE/ZERO/MULTI）、`missingFields`、单一 OPEN 复核、`suspectedDuplicate` 指纹、`whitelistedModelOutput` 白名单——扩展 bundle 候选分支即可。
5. **`sku_aliases` 表结构（schema.sql:145）** → `bundle_aliases`。
6. **`JdPiecesCandidateParser`**：礼包组件规格（`500g*2` 类）解析乘数候选时复用；礼包名 `5200g` 不适用 ×N。
7. **导出/回填/采购全套礼包机制**（spec §9：组件带礼包分组标识同盒、按完整份数校验回传；`order_line_components` → 导出展开；`procurement_tickets` 礼包组件明细）——静态礼包命中后走完全相同的下游，无需改造。
8. **Excel 解析链**（魔数、表头指纹、ImportBatch、逐行容错、批次确认）不动；`SourceFileParser` 各渠道投影规则不动（`source_sku_ref` 提取不变），仅在 `SourceImportService.canonical()` 加礼包识别分支；聚福宝「礼包名称」列经 `raw_cells` 已整体留痕，可在投影层补一个 `source_bundle_name` 字段供识别使用（可选增强）。

---

## 8. 给决策者的三条摘要

1. **样本里没有礼包行**（唯一「礼包」是聚福宝表头列名，值为空）；礼包识别是前瞻能力，输入差异全部来自礼包主数据文件（`（BJ）`后缀、全半角、重量规格、组合/套餐/礼盒关键词）。
2. **映射键**：Excel 四渠道首选渠道商品编号/ID（`source_sku_ref`，现状已在用），聚福宝额外有真实 EAN-13 条码（直连礼包主数据条码）；名称/别名匹配是 ref 之外的兜底，企微以名称为主。
3. **识别顺序：先礼包后 SKU**——渠道显式礼包映射（ref/条码）→ 名称/别名规范化精确命中（关键词闸门）→ 未命中按关键词有无分流到 `BUNDLE_MATCH` 或现状 `SKU_MATCH`；礼包乘数一期恒 1；全部 NEED_REVIEW 复用现有 ReviewCase 机制。
