/**
 * 复核事项 reason_code / 工作台 attention.reason_code / 运营告警 alert_type 的全站统一中文标签。
 *
 * 本模块零运行时依赖（node --test 可直接加载，不经过 Vite 的 @/ 别名解析）；
 * constants/labels.ts 与 pages/workbench/queuePresentation.ts 都从这里 re-export，
 * 全站只有这一张表（UIUX-03 #137：统一口径，禁止第二套文案、禁止同名不同义）。
 *
 * 覆盖后端全部可写枚举值：对账测试见 test/enumLabelReconciliation.test.ts，缺译即失败；
 * 未知码回退显示原码（诚实呈现，禁止掩盖性兜底文案）。
 */

export const REASON_LABELS: Record<string, string> = {
  // 复核事项（review_cases.reason_code）
  CUSTOMER_MATCH_REQUIRED: '客户映射待确认',
  SKU_MAPPING_REQUIRED: 'SKU 映射待确认',
  SKU_MAPPING_CONFLICT: 'SKU 映射冲突',
  SOURCE_SKU_MAPPING_REQUIRED: '来源 SKU 待确认',
  PROVIDER_SKU_MAPPING_REQUIRED: '履约方 SKU 待确认',
  PRODUCT_INACTIVE: '产品已停用',
  SKU_INACTIVE: 'SKU 已停用',
  PROVIDER_INACTIVE: '履约方已停用',
  SPECIFICATION_REQUIRED: '规格待补全',
  UNIT_REQUIRED: '单位待补全',
  PROVIDER_MAPPING_REQUIRED: '履约映射待补全',
  PROVIDER_MAPPING_INACTIVE: '履约映射已停用',
  UNIT_CONVERSION_REQUIRED: '单位换算待确认',
  BARCODE_CONFLICT: '条码冲突',
  REVIEW_REQUIRED: 'SKU 主数据待复核',
  PROVIDER_ASSIGNMENT_CONFLICT: '履约方归属冲突',
  SKU_REFERENCE_INVALID: 'SKU 引用无效',
  MAPPING_MULTIPLIER: '数量换算待确认',
  QUANTITY_SCALE: '数量精度待确认',
  CARRIER_MAPPING: '承运商映射待确认',
  MULTI_SHIPMENT_SOURCE_FOLLOWUP: '多批发货来源回传待跟进',
  IMPORT_DATA: '导入数据待修正',
  REVISION_AFTER_EXPORT: '导出后改单待确认',
  SYNC_FAILED: '来源回传失败',
  FULFILLMENT_EXCEPTION: '履约异常',
  WECOM_ORDER_DRAFT: '企业微信订单草稿待确认',
  WECOM_TRACKING_DRAFT: '企业微信运单草稿待确认',
  WECOM_TRACKING_FILE_REVIEW: '企微运单文件处理失败',
  WECOM_NEED_REVIEW: '企微消息待人工识别',
  WECOM_ORDER_CHANGE: '企微改单待确认',
  WECOM_ORDER_CANCEL: '企微取消待确认',
  JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED: '京东建单预检未通过',
  JD_SKU_MAPPING_BLOCKED: '京东商品映射未通过',
  JD_STOCK_BLOCKED: '京东库存判定未通过',
  JD_TRACKING_BACKFILLED_PENDING_REVIEW: '京东运单回填待复核',
  JD_TRACKING_CONFLICT: '京东运单冲突',
  JD_TRACKING_CARRIER_MAPPING_REQUIRED: '京东承运商映射待确认',
  JD_TRACKING_TERMINAL_EXCEPTION: '京东运单终态异常待复核',
  JD_TRACKING_CARGO_MISMATCH: '京东货品与建单不一致',
  MULTIPLE_TRACKINGS_FOR_OUTBOUND: '京东多运单待确认',
  SOURCE_SYNC_BLOCKED: '来源同步阻断待处理',
  // 京东库存/映射阻断码（review_cases.detail.blockers[].code，见 ShipmentJdStockCheckService
  // 的 stockBlocker/mappingGateBlocker 四个生成点）。与 reason_code 同表：同属 JD_* 命名空间、
  // 互不重名，且工作台卡片标题与复核队列分组必须是同一句话，拆两张表必然长歪（UIUX-03 #137）。
  // 文案按「能直接接在商品名后面」写，例：「牛肉饼(1.2kg) 缺货」。
  JD_STOCK_INSUFFICIENT: '缺货',
  JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED: '目标仓无库存记录',
  JD_STOCK_RESPONSE_AMBIGUOUS: '京东库存数据重复',
  JD_STOCK_RESPONSE_INVALID: '京东库存数据异常',
  JD_SKU_MAPPING_GATE_BLOCKED: '京东商品校验未通过',
  // 运营告警（operational_alerts.alert_type；JD_SKU_MAPPING 当前降级审计通道，落库放开后同口径）
  JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED: '京东出库提交失败',
  PROCUREMENT_REQUIRED: '需采购补货',
  JD_STOCK_QUERY_FAILED: '京东库存查询失败',
  JD_SKU_MAPPING: '京东 SKU 映射待处理',
  FULFILLMENT_EXPORT_WECOM: '导出文件企微投递',
  // 历史/聚合 attention 码（视图口径，保留既有标签）
  NEED_REVIEW: '待复核',
  CUSTOMER_UNMATCHED: '客户未匹配',
  SKU_UNMAPPED: 'SKU 未映射',
  OUT_OF_STOCK: '缺货',
  PROCUREMENT_PENDING: '采购待处理',
  PROCUREMENT_FAILED: '采购失败',
  JD_SUBMIT_FAILED: '京东提交失败',
  TRACKING_OVERDUE: '运单超时未回',
  RETURN_OVERDUE: '回填超时',
  MULTI_SHIPMENT_FOLLOWUP: '多批次发货待跟进',
};

/**
 * 京东 SKU 映射门禁的逐项失败原因（`detail.blockers[].mapping_issue_code`，见
 * ShipmentJdSkuMappingGateService 的 `issue(...)` 生成点）。
 *
 * <p><b>为什么不并进 REASON_LABELS</b>：这些码是无前缀的通用词（MAPPING_MISSING /
 * GOODS_DISABLED …），并进全站表迟早和某个新增 reason_code 撞名却不同义，正是
 * UIUX-03 明令禁止的「同名不同义」。它们同属一个独立命名空间，因此在**同一个文件**里
 * 单开一张表——全站仍然只有这一处定义，没有第二套文案。
 *
 * <p>为什么需要它：门禁有 14 种失败原因，落到 blocker 上 code 一律被塌缩成
 * `JD_SKU_MAPPING_GATE_BLOCKED`（见 mappingGateBlocker 注释：为保持既有消费方按 code
 * 分组的口径不变）。真正的原因只在 `mapping_issue_code` 里，此前前端整个丢掉了，
 * 于是 12 种毛病在工作台上长成同一句「京东库存判定未通过」。
 */
export const MAPPING_ISSUE_LABELS: Record<string, string> = {
  INTERNAL_SKU_MISSING: '未关联内部 SKU',
  INTERNAL_SKU_INACTIVE: '内部 SKU 已停用',
  MAPPING_MISSING: '未配置京东商品映射',
  MAPPING_INACTIVE: '京东商品映射已停用',
  GOODS_NO_MISSING: '京东商品编码为空',
  UNIT_CONVERSION_MISSING: '缺京东件数换算',
  UNIT_CONVERSION_INVALID: '京东件数换算无效',
  NON_INTEGRAL_QUANTITY: '数量换算不成整件',
  JD_GOODS_QUERY_FAILED: '京东商品查询失败',
  JD_GOODS_NOT_FOUND: '京东查无此商品',
  GOODS_NO_CONFLICT: '京东商品编码不一致',
  ERP_GOODS_NO_CONFLICT: '京东 ERP 商品编码不一致',
  GOODS_STATUS_MISSING: '京东商品状态缺失',
  GOODS_DISABLED: '京东商品已停用',
};

/**
 * 未登记的原因码回退为原码而非掩盖性兜底文案——
 * 掩盖性兜底会让调度台出现同名卡片且掩盖新增枚举值；对账测试保证缺译即失败。
 */
export function reasonLabel(code: string): string {
  return REASON_LABELS[code] ?? code;
}

/**
 * 阻断项的一句话原因：有 `mapping_issue_code` 就用它（那才是真原因），否则用 blocker 的
 * `code`。两张表都查不到时回退原码——同 reasonLabel 的诚实呈现口径，不编造兜底文案。
 */
export function blockerReasonLabel(code: string, mappingIssueCode?: string | null): string {
  if (mappingIssueCode) {
    return MAPPING_ISSUE_LABELS[mappingIssueCode] ?? REASON_LABELS[mappingIssueCode] ?? mappingIssueCode;
  }
  return reasonLabel(code);
}
