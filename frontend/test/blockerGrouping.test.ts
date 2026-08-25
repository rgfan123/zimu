import assert from 'node:assert/strict';
import test from 'node:test';

import {
  extractBlockerCases,
  groupBlockers,
  mergeBlockers,
  parseBlockerSource,
  PREVIEW_BLOCKED_REASON,
} from '../src/pages/workbench/blockerGrouping.ts';

/**
 * 夹具取自线上真实预检响应（彩食鲜真单 SHIP-8057…，2026-08-25 配置补齐前）：
 * 10 条阻塞，三个 code、两个 correction_target。刻意不做简化——
 * 简化过的夹具会把「三个 code 指向同一个修复位置」这个坑测没了。
 */
const REAL_BLOCKERS = [
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'sourceNo', 'fulfillment_providers.config.sourceNo', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'warehouseNo', 'fulfillment_providers.config.warehouseNo', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'pin', 'fulfillment_providers.config.pin', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'channelInfo.erpShopNo', 'fulfillment_providers.config.erpShopNo', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'channelInfo.salesPlatformSource', 'fulfillment_providers.config.salesPlatformSource', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING', 'customerInfo.customerCode', 'fulfillment_providers.config.customerCode', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'customerInfo.ownerNo', 'fulfillment_providers.config.ownerNo', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'customerInfo.shopNo', 'fulfillment_providers.config.shopNo', 'fulfillment provider configuration'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'receiverInfo.townPolicy', 'fulfillment_providers.config.townRequired', 'fulfillment provider address policy'],
  ['JD_SHIPMENT_OUTBOUND_CONFIG_MISSING', 'carrierInfo.carrierNo', 'fulfillment_providers.config.carrierNo', 'fulfillment provider configuration'],
].map(([code, path, source, correction_target]) => ({
  code,
  path,
  source,
  correction_target,
  message: `履约方配置缺少京东标识 ${path}，请先补齐后再建单`,
}));

function reviewCase(overrides: Record<string, unknown> = {}) {
  return {
    id: '77',
    case_no: 'RC-JD-PREVIEW-ABC',
    reason_code: PREVIEW_BLOCKED_REASON,
    subject_type: 'SHIPMENT',
    subject_id: '1',
    detail: { message: '京东出库请求预览存在阻断项', blockers: REAL_BLOCKERS },
    ...overrides,
  };
}

test('parseBlockerSource：三段式拆出表/列/键', () => {
  assert.deepEqual(parseBlockerSource('fulfillment_providers.config.sourceNo'), {
    table: 'fulfillment_providers',
    column: 'config',
    key: 'sourceNo',
  });
});

test('parseBlockerSource：两段式的 key 为 null', () => {
  assert.deepEqual(parseBlockerSource('shipments.receiver_name_snapshot'), {
    table: 'shipments',
    column: 'receiver_name_snapshot',
    key: null,
  });
});

test('parseBlockerSource：剥离尾部括号注释', () => {
  assert.deepEqual(parseBlockerSource('shipments.jd_receiver_province (operator confirmed)'), {
    table: 'shipments',
    column: 'jd_receiver_province',
    key: null,
  });
});

test('parseBlockerSource：策略说明不是表路径，返回 null 而不是硬拆', () => {
  // 这些 source 真实存在于 validations[]，拆出来会得到「JD salable-good policy」这种假表名
  assert.equal(parseBlockerSource('JD salable-good policy (100)'), null);
  assert.equal(parseBlockerSource('non-COD outbound policy (50 zero bits)'), null);
  assert.equal(parseBlockerSource('sales-platform template'), null);
});

test('parseBlockerSource：非字符串与空串返回 null', () => {
  assert.equal(parseBlockerSource(undefined), null);
  assert.equal(parseBlockerSource(null), null);
  assert.equal(parseBlockerSource(42), null);
  assert.equal(parseBlockerSource(''), null);
});

test('extractBlockerCases：只挑预览阻断事项，并带出发货单 id', () => {
  const cases = extractBlockerCases([
    reviewCase(),
    { id: '78', reason_code: 'JD_SKU_MAPPING_BLOCKED', detail: { blockers: REAL_BLOCKERS } },
  ]);
  assert.equal(cases.length, 1);
  assert.equal(cases[0].caseId, '77');
  assert.equal(cases[0].shipmentId, '1');
  assert.equal(cases[0].blockers.length, 10);
});

test('extractBlockerCases：subject 不是 SHIPMENT 时 shipmentId 为 null 而不是瞎猜', () => {
  const [only] = extractBlockerCases([reviewCase({ subject_type: 'ORDER', subject_id: '9' })]);
  assert.equal(only.shipmentId, null);
});

test('extractBlockerCases：畸形 blocker 丢弃，不崩整块', () => {
  const [only] = extractBlockerCases([
    reviewCase({
      detail: {
        blockers: [
          REAL_BLOCKERS[0],
          null,
          'not-an-object',
          { code: 'X' },
          { ...REAL_BLOCKERS[1], message: '' },
        ],
      },
    }),
  ]);
  assert.equal(only.blockers.length, 1);
  assert.equal(only.blockers[0].path, 'sourceNo');
});

test('extractBlockerCases：blockers 非数组或全畸形时整条事项丢弃', () => {
  assert.deepEqual(extractBlockerCases([reviewCase({ detail: { blockers: 'nope' } })]), []);
  assert.deepEqual(extractBlockerCases([reviewCase({ detail: {} })]), []);
  assert.deepEqual(extractBlockerCases([reviewCase({ detail: { blockers: [null, 1] } })]), []);
});

test('groupBlockers：真实 10 条分成两组，配置 9 项 + 地址策略 1 项', () => {
  const [config, address] = groupBlockers(
    REAL_BLOCKERS.map((b) => ({
      code: b.code,
      path: b.path,
      source: b.source,
      correctionTarget: b.correction_target,
      message: b.message,
    })),
  );
  assert.equal(config.label, '履约方配置');
  assert.equal(config.items.length, 9);
  assert.equal(config.table, 'fulfillment_providers');
  assert.equal(address.label, '履约方地址策略');
  assert.equal(address.items.length, 1);
});

test('groupBlockers：三个不同 code 归入同一修复位置——按 code 分组会漏掉两条', () => {
  const grouped = groupBlockers(
    REAL_BLOCKERS.map((b) => ({
      code: b.code,
      path: b.path,
      source: b.source,
      correctionTarget: b.correction_target,
      message: b.message,
    })),
  );
  const configCodes = new Set(grouped[0].items.map((item) => item.code));
  assert.ok(configCodes.has('JD_SHIPMENT_OUTBOUND_CONFIG_MISSING'));
  assert.ok(configCodes.has('JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING'));
  assert.equal(configCodes.size, 2, '同一组内确实存在两个不同 code');
});

test('groupBlockers：待补齐的配置键去重且保序', () => {
  const [config] = groupBlockers(
    REAL_BLOCKERS.map((b) => ({
      code: b.code,
      path: b.path,
      source: b.source,
      correctionTarget: b.correction_target,
      message: b.message,
    })),
  );
  assert.deepEqual(config.keys, [
    'sourceNo', 'warehouseNo', 'pin', 'erpShopNo', 'salesPlatformSource',
    'customerCode', 'ownerNo', 'shopNo', 'carrierNo',
  ]);
  assert.equal(config.keys.length, config.items.length, '配置组每一项都对应一个待补键');
});

test('groupBlockers：契约外的 correction_target 原样回显英文，不译不丢', () => {
  const [group] = groupBlockers([
    { code: 'X', path: 'p', source: 'shipments.foo', correctionTarget: 'brand new target', message: 'm' },
  ]);
  assert.equal(group.label, 'brand new target');
});

test('groupBlockers：组内来源表不一致时 table 为 null——宁可不给入口也不送错地方', () => {
  const [group] = groupBlockers([
    { code: 'A', path: 'a', source: 'fulfillment_providers.config.x', correctionTarget: 'mixed', message: 'm' },
    { code: 'B', path: 'b', source: 'shipments.y', correctionTarget: 'mixed', message: 'm' },
  ]);
  assert.equal(group.table, null);
});

test('groupBlockers：来源无法解析时 table 为 null', () => {
  const [group] = groupBlockers([
    { code: 'A', path: 'a', source: 'JD salable-good policy (100)', correctionTarget: 'policy', message: 'm' },
  ]);
  assert.equal(group.table, null);
  assert.equal(group.keys.length, 0);
});

test('mergeBlockers：跨事项按 path 去重（多单常缺同一批配置）', () => {
  const cases = extractBlockerCases([
    reviewCase({ id: '77', subject_id: '1' }),
    reviewCase({ id: '78', subject_id: '2' }),
  ]);
  assert.equal(cases.length, 2);
  assert.equal(mergeBlockers(cases).length, 10, '两单共 20 条，去重后仍是 10 个不同 path');
});
