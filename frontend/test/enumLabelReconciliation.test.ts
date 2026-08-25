/**
 * 后端枚举/原因码 ↔ 前端中文标签表 的构建期对账（UIUX-03 #137）。
 *
 * 规则：后端可写枚举（Java enum、复核事项 reason_code、运营告警 alert_type、
 * 企业微信消息类型）的每一个值，前端标签表都必须有中文翻译；缺译即测试失败，
 * 防止「新增枚举值后前端标签表没有同步」导致的裸枚举上屏。
 *
 * 来源与提取规则（均基于仓库源码，新增枚举值后测试自动覆盖）：
 * - Java enum 族：解析枚举文件常量（OrderStatus / ProcessingStage / ProcessingHealth /
 *   SourceChannel / SettlementMethod）。
 * - 复核事项 reason_code：从以下权威位置提取——
 *   R1 OrderMapper.allowedActions 的 `case "..."` 注册表；
 *   R2 常量名含 REASON 的字符串常量（REVIEW_REASON / BLOCK_REASON 等）；
 *   R3 `reasonCode = "..."` 字面量（含 reviewReasonCode）；
 *   R4 `setReasonCode("...")` 字面量；
 *   R5 `INSERT INTO app.review_cases` 的 VALUES 位置字面量；
 *   R6 种子数据 seedReview(...) 的原因参数；
 *   R7 引用 review_cases 的 `reason_code='...'` SQL 字面量。
 * - 运营告警 alert_type：ALERT_TYPE 常量、`VALUES (?, 'TYPE', 'SEVERITY'` 字面量、
 *   种子数据 seedAlert(...) 类型参数。
 * - 消息类型：docs/openapi.yaml 的 message_type enum。
 *
 * 标签表：
 * - `@/constants/reasonLabels.ts`：REASON_LABELS（复核原因/attention/告警类型，全站唯一）与
 *   reasonLabel；`constants/labels.ts` 与 `pages/workbench/queuePresentation.ts` 都从它 re-export；
 * - `constants/labels.ts`：MESSAGE_TYPE_LABELS、SETTLEMENT_METHOD_LABELS；
 * - `pages/workbench/queuePresentation.ts`：REVIEW_STATUS_LABELS、TEAM_LABELS。
 *
 * 兜底契约：未知码必须回退显示原码（诚实呈现），禁止「未分类原因」类掩盖性文案——
 * 本文件同时断言 reasonLabel 的回退实现。
 */

import assert from 'node:assert/strict';
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const BACKEND = '../../backend/src/main/java';
const FRONTEND = '../src';

function read(relativePath: string): string {
  return readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8');
}

/** 读取目录下全部 Java 文件（路径 → 源码）。 */
function allJavaSources(): Map<string, string> {
  // 用 git ls-files 保证可复现且不依赖构建产物。
  const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
  const files = execSync('git -C ' + repoRoot + ' ls-files "backend/src/main/java/**/*.java"', {
    encoding: 'utf8',
  }).trim().split('\n').filter(Boolean);
  return new Map(files.map((f) => [f, readFileSync(fileURLToPath(new URL('../../' + f, import.meta.url)), 'utf8')]));
}

/** 解析 Java 枚举文件的常量列表（行首缩进 + 全大写 + 可选逗号）。 */
function javaEnumConstants(source: string): string[] {
  const constants: string[] = [];
  for (const match of source.matchAll(/^\s+([A-Z][A-Z0-9_]+),?$/gm)) {
    constants.push(match[1]);
  }
  return constants;
}

/** 复核事项 reason_code 全集（见文件头提取规则）。 */
function backendReviewReasons(): string[] {
  const sources = allJavaSources();
  const reasons = new Set<string>();
  for (const [path, src] of sources) {
    // R1：复核事项动作注册表（OrderMapper.allowedActions 的 reasonCode switch，含多值 case 标签）
    if (path.endsWith('order/OrderMapper.java')) {
      for (const m of src.matchAll(/case "([A-Z][A-Z0-9_]{3,}(?:",\s*"[A-Z][A-Z0-9_]{3,})*)"/g)) {
        for (const code of m[1].split(/",\s*"/)) reasons.add(code);
      }
    }
    // R2：常量名含 REASON 的字符串常量
    for (const m of src.matchAll(/[A-Z_]*REASON[A-Z_]*\s*=\s*"([A-Z][A-Z0-9_]{3,})"/g)) {
      if (!NON_REVIEW_REASON_CONSTANTS.has(m[1])) reasons.add(m[1]);
    }
    // R3：reasonCode 变量字面量（含 reviewReasonCode）
    for (const m of src.matchAll(/reasonCode\s*=\s*"([A-Z][A-Z0-9_]{3,})"/g)) reasons.add(m[1]);
    // R4：setReasonCode 字面量
    for (const m of src.matchAll(/setReasonCode\(\s*"([A-Z][A-Z0-9_]{3,})"\s*\)/g)) reasons.add(m[1]);
    // R5：review_cases INSERT 的 VALUES 位置字面量（case_type, 'OPEN', team, reason…）
    if (src.includes('INSERT INTO app.review_cases')) {
      for (const m of src.matchAll(/VALUES \(\?, '[A-Z][A-Z0-9_]{3,}', 'OPEN', '[A-Z_]+', '([A-Z][A-Z0-9_]{3,})'/g)) {
        reasons.add(m[1]);
      }
    }
    // R6：种子数据 seedReview 原因参数
    if (path.includes('DemoSeedDataInitializer')) {
      for (const m of src.matchAll(/seedReview\(seed[^)]*?"([A-Z][A-Z0-9_]{3,})"/g)) reasons.add(m[1]);
    }
    // R7：引用 review_cases 的 reason_code SQL 字面量
    if (src.includes('review_cases')) {
      for (const m of src.matchAll(/reason_code='([A-Z][A-Z0-9_]{3,})'/g)) reasons.add(m[1]);
    }
  }
  return [...reasons];
}

/** 运营告警 alert_type 全集（见文件头提取规则）。 */
function backendAlertTypes(): string[] {
  const sources = allJavaSources();
  const types = new Set<string>();
  for (const [path, src] of sources) {
    // A1：ALERT_TYPE 常量
    for (const m of src.matchAll(/ALERT_TYPE\s*=\s*"([A-Z][A-Z0-9_]{3,})"/g)) types.add(m[1]);
    // A2：operational_alerts INSERT 的 VALUES 位置字面量（alert_type, severity）
    if (src.includes('INSERT INTO app.operational_alerts')) {
      for (const m of src.matchAll(/VALUES \(\?, '([A-Z][A-Z0-9_]{3,})', '(?:YELLOW|RED)'/g)) types.add(m[1]);
    }
    // A3：种子数据 seedAlert 类型参数
    if (path.includes('DemoSeedDataInitializer')) {
      for (const m of src.matchAll(/seedAlert\(seed[^)]*?"([A-Z][A-Z0-9_]{3,})"/g)) types.add(m[1]);
    }
  }
  return [...types];
}

function readRecord(moduleSource: string, recordName: string): Record<string, string> {
  const start = moduleSource.indexOf(`export const ${recordName}`);
  assert.ok(start >= 0, `labels.ts 必须导出 ${recordName}`);
  const braceStart = moduleSource.indexOf('{', start);
  const braceEnd = moduleSource.indexOf('};', braceStart);
  const body = moduleSource.slice(braceStart + 1, braceEnd);
  const record: Record<string, string> = {};
  for (const m of body.matchAll(/([A-Za-z][A-Za-z0-9_]{2,})\s*:\s*'([^']*)'/g)) {
    record[m[1]] = m[2];
  }
  return record;
}

/**
 * 常量名含 REASON 但并非复核事项 reason_code 的例外（否则会误报缺译）：
 * - LINE_PAIRING_UNRESOLVED：MessagePublicProjectionSanitizer.PAIRING_REASON，
 *   运单草稿 detail 键值，不进 review_cases.reason_code。
 */
const NON_REVIEW_REASON_CONSTANTS = new Set(['LINE_PAIRING_UNRESOLVED']);

test('Java 枚举常量全部有前端中文标签', () => {
  const labelsSource = read(`${FRONTEND}/constants/labels.ts`);
  const families: Array<[string, string, string]> = [
    ['OrderStatus', 'cn/zimu/fulfillment/order/domain/OrderStatus.java', 'ORDER_STATUS_LABELS'],
    ['ProcessingStage', 'cn/zimu/fulfillment/order/domain/ProcessingStage.java', 'PROCESSING_STAGE_LABELS'],
    ['ProcessingHealth', 'cn/zimu/fulfillment/order/domain/ProcessingHealth.java', 'PROCESSING_HEALTH_LABELS'],
    ['SourceChannel', 'cn/zimu/fulfillment/common/domain/SourceChannel.java', 'CHANNEL_LABELS'],
    ['SettlementMethod', 'cn/zimu/fulfillment/order/domain/SettlementMethod.java', 'SETTLEMENT_METHOD_LABELS'],
    ['ProviderType', 'cn/zimu/fulfillment/sku/ProviderType.java', 'PROVIDER_TYPE_LABELS'],
    ['ReviewCaseStatus', 'cn/zimu/fulfillment/order/domain/ReviewCaseStatus.java', 'REVIEW_STATUS_LABELS'],
  ];
  for (const [enumName, javaPath, recordName] of families) {
    const constants = javaEnumConstants(read(`${BACKEND}/${javaPath}`));
    assert.ok(constants.length > 0, `${enumName} 枚举解析结果为空，检查提取规则`);
    // ReviewCaseStatus 的标签表在复核队列模块（queuePresentation），其余在 constants/labels。
    const labelsSourceForFamily = recordName === 'REVIEW_STATUS_LABELS'
      ? read(`${FRONTEND}/pages/workbench/queuePresentation.ts`)
      : labelsSource;
    const labels = readRecord(labelsSourceForFamily, recordName);
    const missing = constants.filter((c) => !(c in labels));
    assert.deepEqual(missing, [], `${enumName} 新增枚举值缺前端中文标签：${missing.join(', ')}`);
  }
});

test('复核事项 reason_code 全部有中文标签（单表口径，复核队列与调度台共用）', () => {
  const reasons = backendReviewReasons();
  assert.ok(reasons.length >= 25, `复核原因提取结果异常（${reasons.length} 个），检查提取规则`);
  const labels = readRecord(read(`${FRONTEND}/constants/reasonLabels.ts`), 'REASON_LABELS');
  const missing = reasons.filter((r) => !(r in labels));
  assert.deepEqual(missing, [], `后端复核原因缺中文标签：${missing.join(', ')}`);
  // 全站只允许一张 REASON_LABELS 表（UIUX-03：统一口径，避免同名不同义），其余位置必须 re-export
  const constantsLabels = read(`${FRONTEND}/constants/labels.ts`);
  assert.match(constantsLabels, /export \{ REASON_LABELS, reasonLabel \} from '\.\/reasonLabels\.ts';/, 'constants/labels 必须从 reasonLabels 单表 re-export');
  assert.doesNotMatch(constantsLabels, /export const REASON_LABELS/, '禁止在 constants/labels 定义第二套 REASON_LABELS');
  const queuePresentation = read(`${FRONTEND}/pages/workbench/queuePresentation.ts`);
  assert.match(queuePresentation, /export \{ REASON_LABELS \} from '.*reasonLabels\.ts';/, '复核队列必须复用全站 REASON_LABELS 单表');
  assert.doesNotMatch(queuePresentation, /export const REASON_LABELS/, '禁止在 queuePresentation 定义第二套 REASON_LABELS');
});

test('运营告警 alert_type 全部有调度台中文标签', () => {
  const types = backendAlertTypes();
  assert.ok(types.length >= 3, `alert_type 提取结果异常（${types.length} 个），检查提取规则`);
  const labels = readRecord(read(`${FRONTEND}/constants/reasonLabels.ts`), 'REASON_LABELS');
  const missing = types.filter((t) => !(t in labels));
  assert.deepEqual(missing, [], `后端告警类型缺调度台中文标签：${missing.join(', ')}`);
});

test('企业微信消息类型全部有中文标签（openapi 契约为准）', () => {
  const openapi = read('../../docs/openapi.yaml');
  const enumMatch = openapi.match(/message_type: \{ type: string, enum: \[([^\]]+)\]/);
  assert.ok(enumMatch, 'openapi.yaml 必须声明 message_type enum');
  const types = enumMatch[1].split(',').map((v) => v.trim());
  assert.ok(types.length >= 5, `message_type 解析异常：${types.join(',')}`);
  const labels = readRecord(read(`${FRONTEND}/constants/labels.ts`), 'MESSAGE_TYPE_LABELS');
  const missing = types.filter((t) => !(t in labels));
  assert.deepEqual(missing, [], `消息类型缺中文标签：${missing.join(', ')}`);
});

test('未知枚举回退显示原码，禁止掩盖性兜底文案', () => {
  const source = read(`${FRONTEND}/constants/reasonLabels.ts`);
  assert.match(source, /return REASON_LABELS\[code\] \?\? code;/, 'reasonLabel 必须回退为原码');
  assert.doesNotMatch(source, /未分类原因/, '禁止「未分类原因」掩盖性兜底文案');
  // 复核队列渲染同样必须回退原码而非编造文案（原因与状态两个维度）
  const manualReview = read(`${FRONTEND}/pages/workbench/ManualReviewPage.tsx`);
  assert.match(manualReview, /reasonLabel\(value\)/, '复核列表未知原因码必须回退原码');
  assert.match(manualReview, /REVIEW_STATUS_LABELS\[value\] \?\? value/, '复核列表未知状态码必须回退原码');
  const drawer = read(`${FRONTEND}/pages/workbench/ReviewCaseDrawer.tsx`);
  assert.match(drawer, /reasonLabel\(selected\.reason_code\)/, '复核抽屉未知原因码必须回退原码');
  assert.match(drawer, /REVIEW_STATUS_LABELS\[selected\.status\] \?\? selected\.status/, '复核抽屉未知状态码必须回退原码');
  // 订单详情结账方式与渠道消息类型同样必须回退原码
  const orderDetail = read(`${FRONTEND}/pages/orders/OrderDetailPage.tsx`);
  assert.match(orderDetail, /SETTLEMENT_METHOD_LABELS\[detail\.settlement\.method\] \?\? detail\.settlement\.method/, '结账方式未知值必须回退原码');
  const channelMessages = read(`${FRONTEND}/pages/workbench/ChannelMessagesPage.tsx`);
  assert.match(channelMessages, /MESSAGE_TYPE_LABELS\[value\] \?\? value/, '消息类型未知值必须回退原码');
});
