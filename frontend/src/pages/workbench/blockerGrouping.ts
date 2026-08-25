/**
 * 京东建单阻塞项的纯投影：把复核事项里的 `detail.blockers` 解析成「按修复位置分组」的视图。
 *
 * <p>背景：`ShipmentJdOutboundService.preview()` 会把完整的 blocker 数组落进
 * `review_cases.detail.blockers`，而发货台本来就在拉 `FULFILLMENT_OPS` 的 OPEN 事项。
 * 数据一直都在，只是此前被 `groupReviewPreview` 数完 reason_code 就丢了。
 * 本模块负责把它捡回来——**不发任何请求**（预检是带副作用的读：取行锁、写审计、
 * 增删改 review_cases，绝不能在发货台按发货单 N+1 地调）。
 *
 * <p>无 React、无 fetch，便于用 node:test 直接断言。全程 fail-closed：
 * 畸形项丢弃而不是崩掉整个区块（体例照 `shippingSkeleton.presentAlertRows`）。
 */

/** 后端 `blockerMap()` 下发的五个稳定键（`ShipmentJdOutboundPreparer.blockerMap()`）。 */
export interface BlockerItem {
  code: string;
  path: string;
  source: string;
  correctionTarget: string;
  message: string;
}

/** 一条「京东建单预览阻断」复核事项及其携带的阻塞项。 */
export interface BlockerCase {
  caseId: string;
  caseNo: string | null;
  /** 仅当 subject_type==='SHIPMENT' 时有值——阻塞项自身不带发货单身份，只能由事项注入。 */
  shipmentId: string | null;
  blockers: BlockerItem[];
}

/** `source` 解析出的修复定位符。 */
export interface CorrectionLocator {
  table: string;
  column: string;
  /** 三段式（如 config.sourceNo）才有；两段式为 null。 */
  key: string | null;
}

export interface BlockerGroupView {
  /** correction_target 原文，仅作分组键与稳定排序用。 */
  targetKey: string;
  /** 中文组标题；契约外的值原样回显英文（照 agentPresentation 的纪律）。 */
  label: string;
  /** 该组共同指向的表名；组内来源不一致或无法解析时为 null。 */
  table: string | null;
  /** 该组涉及的配置键（table==='fulfillment_providers' 时即为待补齐的键名）。 */
  keys: string[];
  items: BlockerItem[];
}

/** 后端 reason_code 常量（`ShipmentJdOutboundAuditService.PREVIEW_BLOCKED_REASON`）。 */
export const PREVIEW_BLOCKED_REASON = 'JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED';

/**
 * correction_target 是**英文散文**，不是标识符——后端改个措辞这里就落回原文，
 * 所以它只做分组键与展示，绝不拿来推导跳转目标（那件事由 source 的表名段负责）。
 */
const TARGET_LABELS: Record<string, string> = {
  'fulfillment provider configuration': '履约方配置',
  'fulfillment provider address policy': '履约方地址策略',
  'shipment receiver address': '收货人地址',
  'sku mapping': 'SKU 映射',
};

/** 尾部括号注释，如 `shipments.jd_receiver_province (operator confirmed)`。 */
const TRAILING_NOTE = /\s*\([^)]*\)\s*$/;

/** 表路径形状：全小写下划线的表名 + 至少一段列/键名，且不含空格。 */
const TABLE_PATH = /^[a-z][a-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$/;

/**
 * 从 blocker.source 解析修复定位符。
 *
 * <p>能解析的只有「表路径」形态；`JD salable-good policy (100)`、
 * `non-COD outbound policy (50 zero bits)` 这类**策略说明**不是表路径，返回 null。
 * 调用方据此退化为「只显示来源原文、不给按钮」——不猜。
 */
export function parseBlockerSource(source: unknown): CorrectionLocator | null {
  if (typeof source !== 'string') return null;
  const cleaned = source.replace(TRAILING_NOTE, '').trim();
  if (!TABLE_PATH.test(cleaned)) return null;
  const [table, column, ...rest] = cleaned.split('.');
  return { table, column, key: rest.length > 0 ? rest.join('.') : null };
}

/** 单条 blocker 的 fail-closed 读取：五个键缺一不可，形状不符即丢弃。 */
function readBlocker(raw: unknown): BlockerItem | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const record = raw as Record<string, unknown>;
  const code = record.code;
  const path = record.path;
  const source = record.source;
  const target = record.correction_target;
  const message = record.message;
  if (typeof code !== 'string' || !code) return null;
  if (typeof path !== 'string' || !path) return null;
  if (typeof source !== 'string' || !source) return null;
  if (typeof target !== 'string' || !target) return null;
  if (typeof message !== 'string' || !message) return null;
  return { code, path, source, correctionTarget: target, message };
}

/**
 * 从复核事项列表里挑出「京东建单预览阻断」并抽取其 blockers。
 *
 * <p>只认 `reason_code === PREVIEW_BLOCKED_REASON`；其余事项与本视图无关。
 * 没有任何合法 blocker 的事项也会被丢弃——显示一个空组比不显示更糟。
 */
export function extractBlockerCases(items: unknown[]): BlockerCase[] {
  const cases: BlockerCase[] = [];
  for (const item of items) {
    if (typeof item !== 'object' || item === null) continue;
    const record = item as Record<string, unknown>;
    if (record.reason_code !== PREVIEW_BLOCKED_REASON) continue;
    const id = record.id;
    if (typeof id !== 'string' && typeof id !== 'number') continue;

    const detail = record.detail;
    const rawBlockers =
      typeof detail === 'object' && detail !== null
        ? (detail as Record<string, unknown>).blockers
        : undefined;
    if (!Array.isArray(rawBlockers)) continue;

    const blockers: BlockerItem[] = [];
    for (const raw of rawBlockers) {
      const parsed = readBlocker(raw);
      if (parsed) blockers.push(parsed);
    }
    if (blockers.length === 0) continue;

    cases.push({
      caseId: String(id),
      caseNo: typeof record.case_no === 'string' ? record.case_no : null,
      // subject_type/subject_id 由 OrderMapper.toReviewCase 派生；
      // 本类事项没有 order_line_id，故必为 SHIPMENT（OrderMapper.java:127-132）。
      shipmentId:
        record.subject_type === 'SHIPMENT' && typeof record.subject_id === 'string'
          ? record.subject_id
          : null,
      blockers,
    });
  }
  return cases;
}

/**
 * 按 correction_target 分组。组内表名一致才给出 table（用于生成修复入口），
 * 不一致或无法解析则为 null——宁可不给入口，也不把人送到错的地方。
 *
 * <p>排序：项数降序，同数按标签中文序，保证同样输入渲染顺序稳定。
 */
export function groupBlockers(blockers: BlockerItem[]): BlockerGroupView[] {
  const buckets = new Map<string, BlockerItem[]>();
  for (const blocker of blockers) {
    const bucket = buckets.get(blocker.correctionTarget);
    if (bucket) bucket.push(blocker);
    else buckets.set(blocker.correctionTarget, [blocker]);
  }

  const groups: BlockerGroupView[] = [];
  for (const [targetKey, items] of buckets) {
    const locators = items.map((item) => parseBlockerSource(item.source));
    const tables = new Set(locators.map((locator) => locator?.table ?? null));
    const table = tables.size === 1 ? [...tables][0] : null;
    const keys: string[] = [];
    for (const locator of locators) {
      if (locator?.key && !keys.includes(locator.key)) keys.push(locator.key);
    }
    groups.push({
      targetKey,
      label: TARGET_LABELS[targetKey] ?? targetKey,
      table,
      keys,
      items,
    });
  }

  return groups.sort(
    (a, b) => b.items.length - a.items.length || a.label.localeCompare(b.label, 'zh'),
  );
}

/** 跨多条事项合并去重：同一 path 只保留一条（多张发货单常缺同一批配置）。 */
export function mergeBlockers(cases: BlockerCase[]): BlockerItem[] {
  const seen = new Set<string>();
  const merged: BlockerItem[] = [];
  for (const item of cases) {
    for (const blocker of item.blockers) {
      if (seen.has(blocker.path)) continue;
      seen.add(blocker.path);
      merged.push(blocker);
    }
  }
  return merged;
}
