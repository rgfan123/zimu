export interface DisplayRow {
  label: string;
  value: string;
}

/** detail 里缺字段时的统一呈现，代替静默丢弃整行。 */
export const SOURCE_NOT_PROVIDED = '来源未提供';

/**
 * 复核事项「做决定所需事实」的可复用呈现结构（Issue #72）。
 *
 * 每个复核家族 = 一组「事实组」（FactGroup），每组 = 固定字段清单（FactFieldDef）。
 * 抽屉用一个循环渲染所有家族，不逐家族复制 JSX；字段不在白名单就显示占位
 * （SOURCE_NOT_PROVIDED），不整行消失。字段定义本身就是白名单：读取只发生在
 * 固定键上，未知键 / 任意 detail 遍历被 fail-closed。
 */
export interface FactFieldDef {
  /** detail 中的固定键（白名单键）。 */
  key: string;
  /** 展示标签。 */
  label: string;
  /**
   * 可选的结构化标量投影：数组/对象证据经固定形状投影为标量文本；
   * 缺省按标量白名单规则读取 detail[key]。投影函数只读自己的固定键，fail-closed。
   */
  value?: (detail: Record<string, unknown>) => string | null;
}

export interface FactGroup {
  title: string;
  fields: readonly FactFieldDef[];
}

/** 结构化证据的固定截断上限：cell 值 / 改前改后值只展示受控长度。 */
const FACT_VALUE_CAP = 200;

/** 确定性候选的零命中事实：空数组表示「系统未命中任何候选」，不是「来源未提供」。 */
const NO_CANDIDATE = '未命中候选';

function truncate(value: unknown, cap = FACT_VALUE_CAP): string | null {
  const scalar = scalarValue(value);
  if (scalar === null) return null;
  return scalar.length <= cap ? scalar : `${scalar.slice(0, cap - 1)}…`;
}

/**
 * 候选清单投影：只读固定键（编号 + 名称），数组内其他键（如档案里的联系方式）不读取。
 * 空数组是确定性零命中事实，显示「未命中候选」而非占位。
 */
function candidateListText(
  detail: Record<string, unknown>,
  listKey: string,
  codeKey: string,
  nameKey: string,
): string | null {
  const raw = detail[listKey];
  if (!Array.isArray(raw)) return null;
  const items = raw.filter((item): item is Record<string, unknown> =>
    Boolean(item) && typeof item === 'object');
  const text = items.flatMap((item) => {
    const code = scalarValue(item[codeKey]);
    const name = scalarValue(item[nameKey]);
    return code === null && name === null ? [] : [[code, name].filter(Boolean).join(' · ')];
  });
  return text.length ? text.join('；') : NO_CANDIDATE;
}

/**
 * 导出后改单的改动明细投影（Issue #72）：逐条「字段（行号）：改前 X → 改后 Y」。
 * 字段键本身是白名单（REVISION_FIELD_LABELS），未知字段键的条目被 fail-closed 丢弃；
 * 改前/改后值截断到固定上限。改动字段键全部由后端 diff 生成，不承载任意自由文本。
 */
const REVISION_FIELD_LABELS: Record<string, string> = {
  source_version: '来源版本',
  receiver_name: '收货人',
  receiver_address: '收货地址',
  quantity: '数量',
  product_name: '商品名称',
  specification: '规格',
  unit: '单位',
  line_count: '行数',
  settlement_method: '结账方式',
  settlement_time: '结账时间',
  remark: '备注',
};

function revisionChangesText(detail: Record<string, unknown>): string | null {
  const raw = detail.changes;
  if (!Array.isArray(raw)) return null;
  if (raw.length === 0) return '无字段变更';
  const text = raw
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    .flatMap((item) => {
      const field = scalarValue(item.field);
      const label = field === null ? null : REVISION_FIELD_LABELS[field];
      if (!label) return [];
      const lineNo = scalarValue(item.line_no);
      const before = truncate(item.before);
      const after = truncate(item.after);
      const location = lineNo === null ? '' : `（第 ${lineNo} 行）`;
      return [`${label}${location}：改前 ${before ?? '—'} → 改后 ${after ?? '—'}`];
    });
  return text.length ? text.join('；') : '无字段变更';
}

const WECOM_TRACKING_FILE_FAILURE_MESSAGES: Record<string, string> = {
  WECOM_TRACKING_FILE_CHAT_UNSUPPORTED: '当前仅支持把运单文件单聊直发给机器人',
  WECOM_TRACKING_FILE_PAYLOAD_INVALID: '企微文件消息缺少可用的下载信息，请重新单聊发送原文件',
  WECOM_TRACKING_FILE_DOWNLOAD_FAILED: '运单文件下载或解密失败，请重新单聊发送原文件',
  WECOM_TRACKING_FILE_TOO_LARGE: '运单文件超过 20MB 上限，请拆分后重新发送',
  WECOM_TRACKING_FILE_INVALID: '回传文件格式或内容不符合精确 24 列模板，请下载原件核对',
  WECOM_TRACKING_FILE_PROCESSING_FAILED: '运单文件处理失败，请人工复核并重试',
};

function trackingFileFailureCode(detail: Record<string, unknown>): string {
  const code = scalarValue(detail.error_code);
  return code && WECOM_TRACKING_FILE_FAILURE_MESSAGES[code]
    ? code
    : 'WECOM_TRACKING_FILE_PROCESSING_FAILED';
}

/** 数量换算 / 精度家族共用的事实组：来源数量原文、单位、当前乘数、换算后结果、拒绝原因、履约方。 */
function quantityFactGroups(): FactGroup[] {
  return [{
    title: '数量换算',
    fields: [
      { key: 'source_quantity', label: '来源数量原文' },
      { key: 'source_unit', label: '来源单位' },
      { key: 'quantity_multiplier', label: '当前乘数' },
      { key: 'converted_quantity', label: '换算后结果' },
      { key: 'reject_reason', label: '拒绝原因' },
      // provider_code 沿袭通用白名单既有展示（#71 已放行），切事实组后不回归。
      { key: 'provider_code', label: '履约方' },
    ],
  }];
}

function sourceSyncStatusText(detail: Record<string, unknown>): string | null {
  const status = scalarValue(detail.status);
  if (status === 'PENDING') return '等待重新检查';
  if (status === 'SYNCING') return '正在回传';
  if (status === 'SYNCED') return '已验证同步成功';
  if (status === 'SYNC_FAILED') return '安全失败，等待修正';
  if (status === 'RECONCILIATION_REQUIRED') return '结果未知，等待人工对账';
  return null;
}

function sourceSyncNextStep(detail: Record<string, unknown>): string {
  const status = scalarValue(detail.status);
  if (status === 'SYNCED') {
    return '无需操作；来源平台已验证同步成功';
  }
  if (status === 'RECONCILIATION_REQUIRED') {
    return '先到来源平台核对是否已受理，再提交人工对账结论；禁止直接重试';
  }
  if (status === 'SYNC_FAILED') {
    return '修正业务代码对应问题后重新检查并再次确认';
  }
  if (Array.isArray(detail.blocker_codes) && detail.blocker_codes.length > 0) {
    return '按阻断代码修正 Shipment、数量、收货信息或平台配置后重新检查';
  }
  return '核对来源平台与 Shipment 当前事实后重新检查';
}

function sourceSyncBlockerCodesText(detail: Record<string, unknown>): string | null {
  const blockers = scalarValue(detail.blocker_codes);
  if (blockers !== null) return blockers;
  return scalarValue(detail.status) === 'SYNCED' ? '无阻断' : null;
}

/**
 * SKU 映射复核抽屉的固定展示字段（含来源文件位置）。只读取白名单内的键（fail-closed），
 * 缺失/空白时以「来源未提供」呈现，而不是整行消失——静默丢弃正是本票要消灭的行为。
 * 与 Issue #72 的事实组共用同一渲染结构（factGroupRows）。
 */
const SKU_MAPPING_FACT_GROUP: FactGroup = {
  title: '来源商品信息',
  fields: [
    { key: 'source_channel', label: '来源渠道' },
    { key: 'line_no', label: '订单行' },
    { key: 'source_sheet_name', label: '来源工作表' },
    { key: 'source_row_index', label: '来源行号' },
    { key: 'missing_source_sku_refs', label: '待映射来源商品' },
    { key: 'source_product_name', label: '来源商品名称' },
    { key: 'source_specification', label: '来源规格' },
    { key: 'source_unit', label: '来源单位' },
    { key: 'source_quantity', label: '来源数量' },
  ],
};

/**
 * 各复核家族的「做决定所需事实」（Issue #72）。字段即白名单，逐条说明为什么不是 PII：
 *
 * CUSTOMER_MATCH_REQUIRED：
 * - customer_name / source_customer_ref：来源渠道给出的客户名称原文与客户编号，是复核
 *   决策的对象（#71 已放行 customer_name / source_customer_ref 同源字段）。
 * - receiver_name / receiver_address：收货人与地址的可展示部分（不含电话），与销售出库页
 *   「收货人/收货地址」列、发货页既有展示同属既有安全投影。
 * - customer_candidates：确定性映射命中的既有客户档案（编号 + 名称），不读取档案内其他
 *   字段（联系方式等）；零命中是触发本复核的事实，显示「未命中候选」。
 *
 * CARRIER_MAPPING：
 * - tracking_number：来源渠道给的物流单号，业务标识而非个人信息。
 * - tracking_prefix：从运单号按确定性前缀规则识别的标识段。
 * - source_logistics_company：来源渠道给的物流公司名称。
 * - carrier_candidates：前缀规则命中的内部 Carrier 主数据（代码 + 名称）。
 *
 * MAPPING_MULTIPLIER / QUANTITY_SCALE：
 * - source_quantity / source_unit：来源渠道给的数量与单位（#71 已放行同源字段）。
 * - quantity_multiplier：当前生效的来源包装乘数快照，业务数值。
 * - converted_quantity：来源数量 × 乘数的结果（被拒绝的值），业务数值。
 * - reject_reason：系统确定性拒绝原因，非自由文本。
 *
 * IMPORT_DATA：
 * - source_sheet_name / source_row_index：来源文件结构元数据（#71 已放行）。
 * - column_name / cell_value：出问题列的列名与原始单元格值。cell_value 是本家族复核的
 *   明确对象（被标记的问题单元格），只读这一个固定键、不遍历其他单元格，且截断到
 *   固定上限；它不是「任意 detail 键」，故不属于 PII 泄漏路径。
 * - reject_reason：标记原因。
 *
 * REVISION_AFTER_EXPORT：
 * - changes：后端对改前订单与修订输入做确定性 diff 得到的字段级改动（字段键经
 *   REVISION_FIELD_LABELS 白名单过滤；改前/改后值截断；收货电话不在 diff 字段内）。
 * - export_batch_no / template_version：已导出履约文件的批次号与模板版本。
 * - source_version / change_reason：来源版本与声明的变更原因（#71 已放行 change_reason）。
 *
 * SOURCE_SYNC_BLOCKED：
 * - status / business_code / blocker_codes：来源回传模块生成的稳定处理结果与代码；
 *   不读取 message、check_hash、平台载荷或 receiver PII。下一步只由受控状态与阻断代码派生。
 */
const REVIEW_FACT_GROUPS: Record<string, FactGroup[]> = {
  // SKU 映射家族沿用 #71 确立的固定字段结构，与其余家族同一渲染路径。
  SKU_MAPPING_REQUIRED: [SKU_MAPPING_FACT_GROUP],
  SKU_MAPPING_CONFLICT: [SKU_MAPPING_FACT_GROUP],
  SOURCE_SKU_MAPPING_REQUIRED: [SKU_MAPPING_FACT_GROUP],
  PROVIDER_SKU_MAPPING_REQUIRED: [SKU_MAPPING_FACT_GROUP],
  WECOM_TRACKING_FILE_REVIEW: [{
    title: '运单文件处理结果',
    fields: [
      {
        key: 'source',
        label: '处理类型',
        value: (detail) => detail.source === 'WECOM_TRACKING_FILE' ? '企微运单文件' : null,
      },
      { key: 'error_code', label: '失败代码', value: trackingFileFailureCode },
      {
        key: 'message',
        label: '处理说明',
        value: (detail) => WECOM_TRACKING_FILE_FAILURE_MESSAGES[trackingFileFailureCode(detail)],
      },
    ],
  }],
  SOURCE_SYNC_BLOCKED: [{
    title: '来源回传处理依据',
    fields: [
      { key: 'status', label: '来源回传状态', value: sourceSyncStatusText },
      { key: 'business_code', label: '业务代码' },
      { key: 'blocker_codes', label: '阻断代码', value: sourceSyncBlockerCodesText },
      { key: 'next_action', label: '下一步', value: sourceSyncNextStep },
    ],
  }],
  CUSTOMER_MATCH_REQUIRED: [
    {
      title: '来源客户',
      fields: [
        // source_channel 沿袭通用白名单既有展示（#71 已放行），切事实组后不回归。
        { key: 'source_channel', label: '来源渠道' },
        { key: 'customer_name', label: '来源客户名称原文' },
        { key: 'source_customer_ref', label: '来源客户编号' },
      ],
    },
    {
      title: '收货信息（可展示部分）',
      fields: [
        { key: 'receiver_name', label: '收货人' },
        { key: 'receiver_address', label: '收货地址' },
      ],
    },
    {
      title: '候选客户档案',
      fields: [{
        key: 'customer_candidates',
        label: '候选客户',
        value: (detail) => candidateListText(detail, 'customer_candidates', 'customer_code', 'customer_name'),
      }],
    },
  ],
  CARRIER_MAPPING: [
    {
      title: '来源运单',
      fields: [
        { key: 'tracking_number', label: '运单号原文' },
        { key: 'tracking_prefix', label: '识别前缀' },
        { key: 'source_logistics_company', label: '来源物流公司' },
      ],
    },
    {
      title: '候选标准承运商',
      fields: [{
        key: 'carrier_candidates',
        label: '候选标准承运商',
        value: (detail) => candidateListText(detail, 'carrier_candidates', 'carrier_code', 'carrier_name'),
      }],
    },
  ],
  MAPPING_MULTIPLIER: quantityFactGroups(),
  QUANTITY_SCALE: quantityFactGroups(),
  IMPORT_DATA: [
    {
      title: '问题单元格',
      fields: [
        { key: 'source_sheet_name', label: '来源工作表' },
        { key: 'source_row_index', label: '来源行号' },
        { key: 'column_name', label: '列名' },
        { key: 'cell_value', label: '原始单元格值', value: (detail) => truncate(detail.cell_value) },
      ],
    },
    {
      title: '拒绝原因',
      fields: [{ key: 'reject_reason', label: '拒绝原因' }],
    },
  ],
  REVISION_AFTER_EXPORT: [
    {
      title: '改动明细',
      fields: [{ key: 'changes', label: '改动字段', value: revisionChangesText }],
    },
    {
      title: '导出文件版本',
      fields: [
        { key: 'export_batch_no', label: '已导出文件批次' },
        { key: 'template_version', label: '导出模板版本' },
      ],
    },
    {
      title: '来源与原因',
      fields: [
        { key: 'source_version', label: '来源版本' },
        { key: 'change_reason', label: '变更原因' },
      ],
    },
  ],
};

/** 复核家族 → 事实组；未定义事实组的家族返回空数组（走通用白名单兜底）。 */
export function reviewFactGroups(reasonCode: string): FactGroup[] {
  return REVIEW_FACT_GROUPS[reasonCode] ?? [];
}

/** 事实组逐字段渲染：白名单字段缺失/空白显示「来源未提供」，不整行消失。 */
export function factGroupRows(detail: Record<string, unknown>, group: FactGroup): DisplayRow[] {
  return group.fields.map((field) => ({
    label: field.label,
    value: field.value ? (field.value(detail) ?? SOURCE_NOT_PROVIDED)
      : (scalarValue(detail[field.key]) ?? SOURCE_NOT_PROVIDED),
  }));
}

/** SKU 映射类复核事项的 reason_code 全集；抽屉对它们逐条展示来源商品明细。 */
export const SKU_MAPPING_REASON_CODES: readonly string[] = [
  'SKU_MAPPING_REQUIRED',
  'SKU_MAPPING_CONFLICT',
  'SOURCE_SKU_MAPPING_REQUIRED',
  'PROVIDER_SKU_MAPPING_REQUIRED',
];

export function isSkuMappingReasonCode(reasonCode: string): boolean {
  return SKU_MAPPING_REASON_CODES.includes(reasonCode);
}

const EVENT_FIELD_LABELS: Record<string, string> = {
  sku_code: 'SKU',
  product_name: '商品',
  specification: '规格',
  quantity: '数量',
  requested_quantity: '申请数量',
  shipped_quantity: '发货数量',
  available_quantity: '可用数量',
  outbound_order_no: '出库单号',
  shipment_no: '发货单号',
  tracking_number: '运单号',
  logistics_company: '物流公司',
  provider_code: '履约方',
  provider_name: '履约方',
  source_ref: '来源单号',
  source_channel: '来源渠道',
  carrier: '承运商',
  reason: '原因',
  change_reason: '变更原因',
  revision_no: '修订号',
};

const REVIEW_FIELD_LABELS: Record<string, string> = {
  // message：创建复核事项时**系统写死的整句说明**（如「京东出库单已进入取消、拉回或
  // 拒收等异常终态，需人工复核」）。它是后端固定文案，不是用户输入、不含收件人 PII。
  // 此前不放行的结果是抽屉显示「没有可公开展示的补充字段」——数据库里明明有人话，
  // 运营却只能看到一个事项类型码，根本无从下手（2026-08-26 用户实测反馈 #3）。
  message: '系统说明',
  shipment_id: '发货批次编号',
  check_run_no: '映射核对批次',
  source_customer_ref: '来源客户编号',
  customer_name: '客户',
  line_no: '订单行',
  missing_source_sku_refs: '待映射来源商品',
  source_sku_ref: '来源商品编号',
  product_name: '商品',
  requested_quantity: '申请数量',
  provider_code: '履约方',
  source_version: '来源版本',
  change_reason: '变更原因',
  carrier: '承运商',
  resolution_type: '处理结果',
  customer_id: '已确认客户编号',
  sku_id: '已确认 SKU 编号',
  source_channel: '来源渠道',
  quantity_multiplier: '数量换算',
  remark: '处理依据',
  note: '处理依据',
  // ---- SKU 映射复核放行的「来源原始商品信息」----
  // 逐条说明为什么不是 PII：
  // source_product_name/source_specification/source_unit：来源渠道提供的商品目录属性
  //   （名称/规格/计量单位），是运营对应内部 SKU 所必需的描述性业务数据；不是个人
  //   姓名、联系方式或地址，且与既有白名单 product_name（商品）同属商品目录信息，
  //   订单行快照本就公开展示。
  // source_quantity：来源渠道给的商品数量，与既有白名单 requested_quantity（申请数量）
  //   同类业务数值，不包含任何个人身份数据。
  // source_sheet_name/source_row_index：来源 Excel/结构化载荷中该行所在位置，是文件结构
  //   元数据（工作表名 + 行号），不指向任何个人数据。
  source_product_name: '来源商品名称',
  source_specification: '来源规格',
  source_unit: '来源单位',
  source_quantity: '来源数量',
  source_sheet_name: '来源工作表',
  source_row_index: '来源行号',
};

const AUDIT_FIELD_LABELS: Record<string, string> = {
  order_no: '订单号',
  source_ref: '来源单号',
  source_channel: '来源渠道',
  product_name: '商品',
  provider_code: '履约方',
  provider_name: '履约方',
  export_id: '导出任务编号',
  export_batch_no: '导出批次',
  import_batch_id: '导入批次编号',
  batch_no: '批次号',
  file_name: '文件名',
  row_count: '数据行数',
  item_count: '明细数',
  result: '处理结果',
  status: '状态',
};

const JD_FIELD_LABELS: Record<string, string> = {
  ownerno: '事业部编码',
  ownername: '事业部名称',
  warehouseno: '仓库编码',
  warehousename: '仓库名称',
  warehouseorderno: '京东出库单号',
  erpdeliveryno: '系统出库单号',
  goodsno: '商品编码',
  goodsname: '商品名称',
  status: '业务状态',
  statusname: '业务状态',
  carriername: '承运商',
  waybillno: '运单号',
  trackingno: '运单号',
  availablequantity: '可用数量',
};

function scalarValue(value: unknown): string | null {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed || null;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (Array.isArray(value)) {
    const values = value.map(scalarValue);
    return values.every((item) => item !== null) && values.length
      ? (values as string[]).join('、')
      : null;
  }
  return null;
}

function approvedRows(
  payload: Record<string, unknown> | null | undefined,
  labels: Record<string, string>,
): DisplayRow[] {
  if (!payload) return [];
  return Object.entries(payload).flatMap(([key, raw]) => {
    const label = labels[key];
    const value = scalarValue(raw);
    return label && value !== null ? [{ label, value }] : [];
  });
}

export function safeEventPayloadRows(payload: Record<string, unknown>): DisplayRow[] {
  return approvedRows(payload, EVENT_FIELD_LABELS);
}

export function reviewCaseSummary(reviewCase: {
  reason_code: string;
  detail: Record<string, unknown>;
}): string {
  const rows = approvedRows(reviewCase.detail, REVIEW_FIELD_LABELS);
  return rows.length
    ? rows.map(({ label, value }) => `${label}：${value}`).join('；')
    : '请前往人工复核工作台查看并处理';
}

export function safeReviewDetailRows(detail: Record<string, unknown>): DisplayRow[] {
  return approvedRows(detail, REVIEW_FIELD_LABELS);
}

export interface ReviewBlockerRow {
  code: string;
  message: string | null;
  correctionTarget: string | null;
  /** 库存类阻断携带的商品身份：商品名（缺则空）+ 京东商品编码。 */
  productLabel: string | null;
}

/**
 * 京东预检类事项的结构化阻断明细（detail.blockers）。
 *
 * <p>逐条放行的字段都是系统生成的诊断数据：code 是稳定业务码、message 是后端固定
 * 文案、correction_target 指向要改的配置/记录位置——没有一个来自用户输入或含 PII。
 * 不透传它们，运营看到的只有「预检未通过」五个字，等于让人闭着眼修。
 */
function blockerProductLabel(item: Record<string, unknown>): string | null {
  const name = scalarValue(item.product_name);
  const goodsNo = scalarValue(item.goods_no);
  if (name && goodsNo) return `${name}（${goodsNo}）`;
  return name ?? goodsNo;
}

export function reviewBlockerRows(detail: Record<string, unknown>): ReviewBlockerRow[] {
  const raw = Array.isArray(detail.blockers) ? detail.blockers : [];
  return raw
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    .flatMap((item) => {
      const code = scalarValue(item.code);
      return code === null
        ? []
        : [{
            code,
            message: scalarValue(item.message),
            correctionTarget: scalarValue(item.correction_target),
            productLabel: blockerProductLabel(item),
          }];
    });
}

export function skuMappingDetailRows(detail: Record<string, unknown>): DisplayRow[] {
  return factGroupRows(detail, SKU_MAPPING_FACT_GROUP);
}

export interface SkuMappingEvidenceItem {
  sourceSkuRef: string | null;
  productName: string | null;
  specification: string | null;
  unit: string | null;
  quantity: string | null;
}

/** 结构化证据单元格：标量缺失/空白时统一呈现「来源未提供」。 */
export function skuMappingEvidenceCell(value: unknown): string {
  return scalarValue(value) ?? SOURCE_NOT_PROVIDED;
}

/**
 * SKU 映射复核的结构化证据：逐个被阻断商品一行，绝不合并成一串编号。
 * 优先读后端 evidence_items（对象数组，仅取固定白名单同源字段、标量强制、fail-closed）；
 * 缺失时从白名单字段 missing_source_sku_refs 退化为逐编号一行，其余单元格由调用方
 * 以「来源未提供」呈现。
 */
export function skuMappingEvidenceItems(detail: Record<string, unknown>): SkuMappingEvidenceItem[] {
  const rawItems = Array.isArray(detail.evidence_items) ? detail.evidence_items : [];
  const parsed = rawItems
    .filter((raw): raw is Record<string, unknown> => Boolean(raw) && typeof raw === 'object')
    .map((item) => ({
      sourceSkuRef: scalarValue(item.source_sku_ref),
      productName: scalarValue(item.product_name),
      specification: scalarValue(item.specification),
      unit: scalarValue(item.unit),
      quantity: scalarValue(item.quantity),
    }));
  if (parsed.length) return parsed;
  const refs = Array.isArray(detail.missing_source_sku_refs)
    ? detail.missing_source_sku_refs
        .map(scalarValue)
        .filter((value): value is string => value !== null)
    : [];
  return refs.map((sourceSkuRef) => ({
    sourceSkuRef,
    productName: null,
    specification: null,
    unit: null,
    quantity: null,
  }));
}

export function safeAuditPayloadRows(
  payload: Record<string, unknown> | null | undefined,
): DisplayRow[] {
  return approvedRows(payload, AUDIT_FIELD_LABELS);
}

export function displayOperator(operator: string | null | undefined): string {
  if (!operator || /^(system|seed-runner|demo-runner|jd-client)$/i.test(operator)) {
    return '系统';
  }
  return operator;
}

const AUDIT_SERVICE_LABELS: Record<string, string> = {
  order: '订单',
  fulfillment: '履约',
  'provider-tracking': '运单回传',
  'source-file-import': '来源订单导入',
  'source-return-export': '来源回填',
  'fulfillment-export': '履约导出',
  'jd.isc': '京东仓配',
  MasterDataService: '基础资料',
  ProcurementService: '采购',
  FulfillmentStockDecisionService: '履约库存',
  OperationalAlertService: '异常提醒',
  ReviewCaseResolutionService: '人工复核',
  demo: '演示流程',
};

export function auditServiceLabel(service: string): string {
  if (service.startsWith('connector.')) return '渠道连接';
  return AUDIT_SERVICE_LABELS[service] ?? '业务服务';
}

const AUDIT_OPERATION_LABELS: Record<string, string> = {
  'order.create': '创建订单',
  'order.revise': '修订订单',
  'source-orders.upload': '来源订单导入',
  'file.upload': '回传文件导入',
  'file.download': '文件下载',
  'tracking.accept': '接收运单',
  'procurement.receipt': '登记采购结果',
  'procurement.retry': '重试采购',
  'procurement.cancel_remaining': '取消剩余采购',
  'operational_alert.acknowledge': '确认异常提醒',
  queryOwners: '查询授权事业部',
  queryWarehouses: '查询京东仓库',
  queryProducts: '查询京东商品',
  queryStock: '查询京东库存',
  queryOutboundOrder: '查询京东发货信息',
  queryTracking: '查询京东运单',
  createOutboundOrder: '创建京东出库单',
  cancelOutboundOrder: '取消京东出库单',
  updateConfig: '更新渠道配置',
  testConnection: '检查渠道连接',
  'demo.run': '运行演示流程',
};

export function auditOperationLabel(operation: string): string {
  if (operation.startsWith('review_case.')) return '处理人工复核';
  if (operation.startsWith('master_data.')) return '维护基础资料';
  return AUDIT_OPERATION_LABELS[operation] ?? '其他业务操作';
}

function collectJdRows(value: unknown, rows: DisplayRow[]): void {
  if (rows.length >= 24 || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (const item of value) collectJdRows(item, rows);
    return;
  }
  if (typeof value !== 'object') return;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (rows.length >= 24) return;
    const normalized = key.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
    const label = JD_FIELD_LABELS[normalized];
    const scalar = scalarValue(raw);
    if (label && scalar !== null) {
      rows.push({ label, value: scalar });
    } else if (typeof raw === 'object') {
      collectJdRows(raw, rows);
    }
  }
}

export interface JdQueryPresentation {
  title: string;
  description: string;
  tone: 'success' | 'warning';
  rows: DisplayRow[];
}

export function jdQueryPresentation(
  mode: 'MOCK' | 'REAL',
  kind: 'owners' | 'warehouses' | 'outbound',
  result: { success: boolean; data?: unknown },
): JdQueryPresentation {
  const subject = kind === 'owners' ? '事业部' : kind === 'warehouses' ? '仓库权限' : '发货信息';
  const rows: DisplayRow[] = [];
  if (result.success) collectJdRows(result.data, rows);
  if (mode === 'MOCK') {
    return result.success
      ? {
          title: `模拟${subject}查询完成（不代表真实权限）`,
          description: '当前结果由模拟数据生成，仅用于流程演示，不能证明真实京东权限或业务状态。',
          tone: 'success',
          rows,
        }
      : {
          title: `模拟${subject}查询未完成`,
          description: '模拟查询暂未完成，请稍后重试；若持续发生，请联系管理员。',
          tone: 'warning',
          rows: [],
        };
  }
  return result.success
    ? {
        title: `真实${subject}查询完成`,
        description: '京东仓配已返回结果，页面仅展示经确认的业务字段。',
        tone: 'success',
        rows,
      }
    : {
        title: `真实${subject}查询未完成`,
        description: '请检查京东仓配授权状态后重试；若持续发生，请联系管理员。',
        tone: 'warning',
        rows: [],
      };
}
