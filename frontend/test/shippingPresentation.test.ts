/**
 * 今日发货工作台纯展示契约（Issue #107）。
 * 这里集中覆盖解析、汇总、Identifier、公开文案与畸形响应投影；真实 DOM 用户动线留在
 * shippingWorkbenchRoute.test.ts。
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  CHANNEL_PUBLIC_MESSAGES,
  QUOTA_UNAVAILABLE_TEXT,
  channelPublicMessage,
  failedRefreshChannels,
  presentShippingChannel,
  summarizeShippingResult,
  type ChannelStatus,
} from '../src/pages/workbench/shippingPresentation.ts';
import {
  CONTRACT_ERROR_COPY,
  GENERIC_FAILED_COPY,
  PROTOTYPE_KEYS,
  channel,
  failedRefreshError,
  rawChannel,
} from './shippingTestFixtures.ts';

test('shipping presentation summarizes batch count, total rows and reported orders', () => {
  const summary = summarizeShippingResult({
    channels: [
      channel(),
      channel({ channel: 'JUFUBAO', batch_no: undefined, batch_id: undefined, row_counts: undefined, order_count: 41 }),
      channel({
        channel: 'FEIXIANG',
        batch_no: 'IMP-FX-002',
        batch_id: '8',
        row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
      }),
    ],
  });
  assert.deepEqual(summary, {
    batchCount: 2,
    totalRows: 42,
    reportedOrders: 41,
    failedCount: 0,
    skippedCount: 0,
    contractErrorCount: 0,
    hasNewOrders: true,
  });
  assert.deepEqual(summarizeShippingResult({ channels: [] }), {
    batchCount: 0,
    totalRows: 0,
    reportedOrders: 0,
    failedCount: 0,
    skippedCount: 0,
    contractErrorCount: 0,
    hasNewOrders: false,
  });
  assert.deepEqual(summarizeShippingResult({
    channels: [
      channel(),
      channel({ channel: 'FEIXIANG', status: 'FAILED', batch_no: undefined, batch_id: undefined, row_counts: undefined }),
      channel({ channel: 'ZHONGHUI', status: 'SKIPPED', batch_no: undefined, batch_id: undefined, row_counts: undefined }),
    ],
  }), {
    batchCount: 1,
    totalRows: 30,
    reportedOrders: 0,
    failedCount: 1,
    skippedCount: 0,
    contractErrorCount: 1,
    hasNewOrders: true,
  });
});

test('shipping presentation only counts JUFUBAO report-only order_count into reportedOrders', () => {
  const mixed = summarizeShippingResult({
    channels: [
      channel({ order_count: 3 }),
      channel({
        channel: 'JUFUBAO',
        status: 'OK',
        batch_no: undefined,
        batch_id: undefined,
        row_counts: undefined,
        order_count: 41,
      }),
    ],
  });
  assert.equal(mixed.reportedOrders, 41);
  assert.equal(mixed.batchCount, 1);
  assert.equal(mixed.totalRows, 30);

  const importedOnly = presentShippingChannel(channel({ order_count: 3 }));
  assert.equal(importedOnly.reportOnly, false);
  assert.equal(importedOnly.orderCount, 3);

  const zeroJufubao = channel({
    channel: 'JUFUBAO',
    status: 'OK',
    batch_no: undefined,
    batch_id: undefined,
    row_counts: undefined,
    order_count: 0,
  });
  const zeroReport = presentShippingChannel(zeroJufubao);
  assert.equal(zeroReport.reportOnly, true);
  assert.equal(zeroReport.orderCount, 0);
  assert.equal(summarizeShippingResult({ channels: [zeroJufubao] }).reportedOrders, 0);

  const jufubaoWithBatch = presentShippingChannel(channel({
    channel: 'JUFUBAO',
    status: 'OK',
    batch_no: 'IMP-JFB-009',
    batch_id: '9',
    order_count: 12,
  }));
  assert.equal(jufubaoWithBatch.reportOnly, false);

  const jufubaoBatchNoOnly = presentShippingChannel(channel({
    channel: 'JUFUBAO',
    status: 'OK',
    batch_no: 'IMP-JFB-010',
    batch_id: undefined,
    order_count: 7,
  }));
  assert.equal(jufubaoBatchNoOnly.reportOnly, false);
});

test('failed refresh channels are accepted only from a fail-closed 502 PLATFORM_REFRESH_ALL_FAILED body', () => {
  const channels = [
    channel({
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'SCRIPT_FAILED',
      message: '脚本超时',
    }),
    channel({
      channel: 'JUFUBAO',
      status: 'SKIPPED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'SKIPPED',
      message: '今日无数据',
    }),
  ];
  const result = failedRefreshChannels(failedRefreshError(channels));
  assert.deepEqual(result, [
    { channel: 'CAISHIXIAN', status: 'FAILED', business_code: 'SCRIPT_FAILED' },
    { channel: 'JUFUBAO', status: 'SKIPPED', business_code: 'SKIPPED' },
  ]);
  assert.notEqual(result?.[0], channels[0], '必须返回新建对象，不得回传原始渠道对象');
  assert.equal(result && 'message' in result[0], false);
  assert.equal(result && 'batch_id' in result[0], false);
  assert.equal(failedRefreshChannels(failedRefreshError('not-an-array')), null);
  assert.equal(failedRefreshChannels(failedRefreshError([{ channel: 'CAISHIXIAN' }])), null);
  assert.equal(failedRefreshChannels({
    status: 500,
    body: { business_code: 'PLATFORM_REFRESH_ALL_FAILED', details: { channels } },
  }), null);
  assert.equal(failedRefreshChannels({
    status: 502,
    body: { business_code: 'INTERNAL', details: { channels } },
  }), null);
});

test('shipping presentation gives a batch channel a destination and a report-only channel none', () => {
  const imported = presentShippingChannel(channel());
  assert.equal(imported.destination, '/fulfillment/sales-outbound?import_batch=7');
  assert.equal(imported.reportOnly, false);
  assert.equal(imported.batchNo, 'IMP-CSX-001');

  const reportOnly = presentShippingChannel(channel({
    channel: 'JUFUBAO',
    batch_no: undefined,
    batch_id: undefined,
    row_counts: undefined,
    order_count: 41,
  }));
  assert.equal(reportOnly.reportOnly, true);
  assert.equal(reportOnly.destination, null);
  assert.equal(reportOnly.orderCount, 41);
  assert.equal(QUOTA_UNAVAILABLE_TEXT, '当前接口未暴露剩余拉取额度');
});

test('failed refresh channels reject extra channels, OK status and malformed field types as a whole', () => {
  const valid = {
    channel: 'CAISHIXIAN',
    status: 'FAILED',
    business_code: 'SCRIPT_FAILED',
    message: '脚本超时',
  };
  assert.equal(failedRefreshChannels(failedRefreshError([{ ...valid, channel: 'ZHONGHUI' }])), null);
  assert.equal(failedRefreshChannels(failedRefreshError([
    { channel: 'CAISHIXIAN', status: 'OK', business_code: 'OK' },
  ])), null);
  assert.equal(failedRefreshChannels(failedRefreshError([{ ...valid, message: { text: 'leak' } }])), null);
  assert.equal(failedRefreshChannels(failedRefreshError([{ ...valid, business_code: 12 }])), null);
  assert.equal(failedRefreshChannels(failedRefreshError([{ ...valid, channel: 7 }])), null);
  assert.equal(failedRefreshChannels(failedRefreshError([{ ...valid, status: { value: 'FAILED' } }])), null);
  assert.equal(failedRefreshChannels(failedRefreshError([
    valid,
    { channel: 'FEIXIANG', status: 'SKIPPED', business_code: 'SKIPPED', message: '今日无数据' },
    { channel: 'WANQI', status: 'FAILED', business_code: 'SCRIPT_FAILED' },
  ])), null);
});

test('failed refresh channels return a new narrow object and drop batch/file/latency fields', () => {
  const original = {
    channel: 'CAISHIXIAN',
    status: 'FAILED',
    business_code: 'SCRIPT_FAILED',
    message: '脚本超时 /tmp/caishixian_fetch_orders.py',
    batch_id: '7&return_to=https://evil.invalid',
    batch_no: 'IMP-CSX-001',
    row_counts: '30',
    order_count: 41,
    file_name: 'orders.xlsx',
    script_output: 'traceback',
    latency_ms: 12,
    latency: 12,
  };
  const result = failedRefreshChannels(failedRefreshError([original]));
  assert.deepEqual(result, [
    { channel: 'CAISHIXIAN', status: 'FAILED', business_code: 'SCRIPT_FAILED' },
  ]);
  assert.notEqual(result?.[0], original);
  assert.deepEqual(Object.keys(result?.[0] ?? {}).sort(), ['business_code', 'channel', 'status']);
});

test('presentShippingChannel only links Identifier batch ids and ignores malformed display fields', () => {
  const poisoned = presentShippingChannel(rawChannel({
    batch_id: '7&return_to=https://evil.invalid',
    batch_no: { no: 'IMP-CSX-001' },
    order_count: '41',
    row_counts: '30',
    message: { text: 'leak' },
  }));
  assert.equal(poisoned.destination, null);
  assert.equal(poisoned.batchNo, null);
  assert.equal(poisoned.orderCount, null);
  assert.equal(poisoned.rowCounts, null);
  assert.equal(poisoned.message, null);

  const valid = presentShippingChannel(channel());
  assert.equal(valid.destination, '/fulfillment/sales-outbound?import_batch=7');
  assert.equal(valid.batchNo, 'IMP-CSX-001');
  assert.deepEqual(valid.rowCounts, { total: 30, accepted: 28, need_review: 2, rejected: 0 });

  const partialRows = presentShippingChannel(rawChannel({
    batch_id: '8',
    row_counts: { total: 12, accepted: 12 },
  }));
  assert.equal(partialRows.rowCounts, null);

  const negative = presentShippingChannel(channel({
    channel: 'JUFUBAO',
    status: 'OK',
    batch_id: undefined,
    batch_no: undefined,
    row_counts: undefined,
    order_count: -1,
  }));
  assert.equal(negative.orderCount, null);
  assert.equal(negative.reportOnly, false);

  const infinite = presentShippingChannel(channel({
    channel: 'JUFUBAO',
    status: 'OK',
    batch_id: undefined,
    batch_no: undefined,
    row_counts: { total: Number.POSITIVE_INFINITY, accepted: 0, need_review: 0, rejected: 0 },
    order_count: Number.NaN,
  }));
  assert.equal(infinite.rowCounts, null);
  assert.equal(infinite.orderCount, null);

  assert.deepEqual(summarizeShippingResult({
    channels: [
      rawChannel({
        batch_id: '7&return_to=https://evil.invalid',
        batch_no: 12,
        row_counts: '30',
        order_count: 3,
      }),
      channel({
        channel: 'JUFUBAO',
        status: 'OK',
        batch_id: undefined,
        batch_no: undefined,
        row_counts: undefined,
        order_count: 41.5,
      }),
    ],
  }), {
    batchCount: 0,
    totalRows: 0,
    reportedOrders: 0,
    failedCount: 0,
    skippedCount: 0,
    contractErrorCount: 0,
    hasNewOrders: false,
  });
});

test('channel public copy derives only from status and business code', () => {
  const raw = 'leak /opt/zimu/scripts/caishixian_fetch_orders.py data-local/csx-credentials.txt CSX_PASSWORD <img src=x onerror=alert(1)>';
  const cases: Array<{ status: ChannelStatus; business_code: string; publicCopy: string | null }> = [
    { status: 'FAILED', business_code: 'CONNECTOR_CAPABILITY_UNAVAILABLE', publicCopy: '该渠道在线拉取尚未接入，本次未拉取' },
    { status: 'SKIPPED', business_code: 'CONNECTOR_CONFIG_MISSING', publicCopy: '该渠道连接配置不存在，本次未拉取' },
    { status: 'SKIPPED', business_code: 'CONNECTOR_DISABLED', publicCopy: '该渠道已停用，本次未拉取' },
    { status: 'SKIPPED', business_code: 'CONNECTOR_CLIENT_MODE_NOT_REAL', publicCopy: '该渠道未处于真实拉取模式，本次未拉取' },
    { status: 'SKIPPED', business_code: 'CONNECTOR_TRANSPORT_NOT_API', publicCopy: '该渠道未配置为接口拉取，本次未拉取' },
    { status: 'SKIPPED', business_code: 'PLATFORM_PULL_RATE_LIMITED', publicCopy: '距上次拉取间隔不足，本次已按合规限制跳过' },
    { status: 'SKIPPED', business_code: 'PLATFORM_PULL_CLAIM_CONFLICT', publicCopy: '本次拉取未能领取名额，请稍后重试' },
    { status: 'FAILED', business_code: 'PLATFORM_PULL_CLEANUP_FAILED', publicCopy: '拉取已结束，但临时文件清理不完整，请联系管理员处理' },
    { status: 'FAILED', business_code: 'SCRIPT_FAILED', publicCopy: '该渠道拉取失败，请稍后重试' },
    { status: 'FAILED', business_code: 'INTERNAL_ERROR', publicCopy: '该渠道刷新出现内部错误，请稍后重试' },
    { status: 'FAILED', business_code: 'REFRESH_FAILED', publicCopy: '该渠道刷新失败，请稍后重试' },
    { status: 'SKIPPED', business_code: 'SKIPPED', publicCopy: '该渠道已跳过本次拉取' },
    { status: 'FAILED', business_code: 'UNKNOWN_LEAK_CODE', publicCopy: '该渠道刷新失败，请稍后重试' },
    { status: 'SKIPPED', business_code: 'UNKNOWN_SKIP_CODE', publicCopy: '该渠道已跳过本次拉取' },
    { status: 'OK', business_code: 'OK', publicCopy: null },
  ];

  for (const item of cases) {
    const view = presentShippingChannel(channel({
      status: item.status,
      batch_no: item.status === 'OK' ? 'IMP-CSX-001' : undefined,
      batch_id: item.status === 'OK' ? '7' : undefined,
      row_counts: item.status === 'OK' ? { total: 30, accepted: 28, need_review: 2, rejected: 0 } : undefined,
      business_code: item.business_code,
      message: raw,
    }));
    assert.equal(view.message, item.publicCopy, item.business_code);
    assert.doesNotMatch(view.message ?? '', /caishixian_fetch_orders|csx-credentials|CSX_PASSWORD|<img/);
  }
});

test('channelPublicMessage never returns object/function for prototype business_code keys', () => {
  assert.ok(CHANNEL_PUBLIC_MESSAGES instanceof Map, '公开文案必须是 Map，不能被原型键命中');
  for (const key of PROTOTYPE_KEYS) {
    const message = channelPublicMessage('FAILED', key);
    assert.equal(typeof message, 'string', key);
    assert.equal(message, GENERIC_FAILED_COPY, key);
    assert.notEqual(typeof message, 'object');
    assert.notEqual(typeof message, 'function');
    assert.equal(CHANNEL_PUBLIC_MESSAGES.get(key), undefined, key);
  }
  assert.equal(channelPublicMessage('SKIPPED', '__proto__'), '该渠道已跳过本次拉取');
  assert.equal(channelPublicMessage('FAILED', 'SCRIPT_FAILED'), '该渠道拉取失败，请稍后重试');
  assert.equal(channelPublicMessage('OK', '__proto__'), null);
});

test('presentShippingChannel treats prototype business_code as unknown and keeps public copy a string', () => {
  for (const key of PROTOTYPE_KEYS) {
    const view = presentShippingChannel(channel({
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: key,
      message: `raw leak ${key}`,
    }));
    assert.equal(typeof view.message, 'string', key);
    assert.equal(view.message, GENERIC_FAILED_COPY, key);
    assert.equal(view.statusText, '失败');
    assert.equal(view.label, '彩食鲜');
    assert.equal(view.validContract, true);
    assert.equal(view.destination, null);
  }
});

test('presentShippingChannel rejects prototype channel/status keys without destinations or raw messages', () => {
  for (const key of PROTOTYPE_KEYS) {
    const byChannel = presentShippingChannel(rawChannel({
      channel: key,
      status: 'OK',
      message: `raw channel ${key}`,
    }));
    assert.equal(byChannel.label, '未知渠道', key);
    assert.equal(byChannel.statusText, '响应异常');
    assert.equal(byChannel.validContract, false, key);
    assert.equal(byChannel.destination, null, key);
    assert.equal(byChannel.batchNo, null, key);
    assert.equal(byChannel.batchId, null, key);
    assert.equal(byChannel.rowCounts, null, key);
    assert.equal(byChannel.orderCount, null, key);
    assert.equal(byChannel.reportOnly, false, key);
    assert.equal(byChannel.message, CONTRACT_ERROR_COPY);
    assert.doesNotMatch(byChannel.label, /function|Object|native code/);

    const byStatus = presentShippingChannel(rawChannel({
      channel: 'CAISHIXIAN',
      status: key,
      business_code: 'SCRIPT_FAILED',
      message: `raw status ${key}`,
    }));
    assert.equal(byStatus.statusText, '响应异常', key);
    assert.equal(byStatus.label, '彩食鲜');
    assert.equal(byStatus.validContract, false, key);
    assert.equal(byStatus.destination, null, key);
    assert.equal(byStatus.batchNo, null, key);
    assert.equal(byStatus.batchId, null, key);
    assert.equal(byStatus.rowCounts, null, key);
    assert.equal(byStatus.orderCount, null, key);
    assert.equal(byStatus.reportOnly, false, key);
    assert.equal(byStatus.message, CONTRACT_ERROR_COPY);
    assert.doesNotMatch(String(byStatus.message), /raw status/);
  }

  const legal = presentShippingChannel(channel());
  assert.equal(legal.validContract, true);
  assert.equal(legal.label, '彩食鲜');
  assert.equal(legal.statusText, '成功');
  assert.equal(legal.destination, '/fulfillment/sales-outbound?import_batch=7');
});

test('invalid contracts drop batch/count fields and cannot pollute shipping summary totals', () => {
  const unknownChannel = rawChannel({
    channel: '__proto__',
    status: 'FAILED',
    batch_no: 'IMP-PROTO-007',
    batch_id: '7',
    row_counts: { total: 30, accepted: 28, need_review: 2, rejected: 0 },
    order_count: 99,
    message: 'raw leak __proto__',
  });
  const unknownStatus = rawChannel({
    channel: 'CAISHIXIAN',
    status: 'toString',
    batch_no: 'IMP-CSX-008',
    batch_id: '8',
    row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
    order_count: 5,
    business_code: 'SCRIPT_FAILED',
    message: 'raw leak toString',
  });
  const legalFeixiang = channel({
    channel: 'FEIXIANG',
    batch_no: 'IMP-FX-009',
    batch_id: '9',
    row_counts: { total: 4, accepted: 4, need_review: 0, rejected: 0 },
  });

  const illegalChannelView = presentShippingChannel(unknownChannel);
  assert.equal(illegalChannelView.validContract, false);
  assert.equal(illegalChannelView.label, '未知渠道');
  assert.equal(illegalChannelView.statusText, '响应异常');
  assert.equal(typeof illegalChannelView.message, 'string');
  assert.equal(illegalChannelView.message, CONTRACT_ERROR_COPY);
  assert.equal(illegalChannelView.batchNo, null);
  assert.equal(illegalChannelView.batchId, null);
  assert.equal(illegalChannelView.rowCounts, null);
  assert.equal(illegalChannelView.orderCount, null);
  assert.equal(illegalChannelView.reportOnly, false);
  assert.equal(illegalChannelView.destination, null);

  const illegalStatusView = presentShippingChannel(unknownStatus);
  assert.equal(illegalStatusView.validContract, false);
  assert.equal(illegalStatusView.label, '彩食鲜');
  assert.equal(illegalStatusView.statusText, '响应异常');
  assert.equal(illegalStatusView.message, CONTRACT_ERROR_COPY);
  assert.equal(illegalStatusView.batchNo, null);
  assert.equal(illegalStatusView.batchId, null);
  assert.equal(illegalStatusView.rowCounts, null);
  assert.equal(illegalStatusView.orderCount, null);
  assert.equal(illegalStatusView.reportOnly, false);
  assert.equal(illegalStatusView.destination, null);

  const legalView = presentShippingChannel(legalFeixiang);
  assert.equal(legalView.validContract, true);
  assert.equal(legalView.label, '飞象');
  assert.equal(legalView.statusText, '成功');
  assert.equal(legalView.batchNo, 'IMP-FX-009');
  assert.equal(legalView.batchId, '9');
  assert.deepEqual(legalView.rowCounts, { total: 4, accepted: 4, need_review: 0, rejected: 0 });
  assert.equal(legalView.destination, '/fulfillment/sales-outbound?import_batch=9');

  assert.deepEqual(summarizeShippingResult({
    channels: [
      unknownChannel,
      unknownStatus,
      legalFeixiang,
    ],
  }), {
    batchCount: 1,
    totalRows: 4,
    reportedOrders: 0,
    failedCount: 0,
    skippedCount: 0,
    contractErrorCount: 2,
    hasNewOrders: true,
  });
});

test('invalid-only and mixed contracts expose contract errors without polluting legal totals', () => {
  const unknownSkipped = rawChannel({
    channel: 'ZHONGHUI',
    status: 'SKIPPED',
    batch_no: 'IMP-ZH-001',
    batch_id: '7',
    row_counts: { total: 8, accepted: 8, need_review: 0, rejected: 0 },
    order_count: 3,
    business_code: 'SKIPPED',
    message: '今日无数据 /tmp/zhonghui.py',
  });
  const badStatus = rawChannel({
    channel: 'CAISHIXIAN',
    status: 'WEIRD',
    batch_no: undefined,
    batch_id: undefined,
    row_counts: undefined,
    business_code: 'SKIPPED',
    message: '今日无数据',
  });
  assert.deepEqual(summarizeShippingResult({ channels: [unknownSkipped, badStatus] }), {
    batchCount: 0,
    totalRows: 0,
    reportedOrders: 0,
    failedCount: 0,
    skippedCount: 0,
    contractErrorCount: 2,
    hasNewOrders: false,
  });

  const legalCaishixian = channel();
  const unknownWanqi = rawChannel({
    channel: 'WANQI',
    status: 'SKIPPED',
    batch_no: 'IMP-WQ-002',
    batch_id: '8',
    row_counts: { total: 9, accepted: 9, need_review: 0, rejected: 0 },
    order_count: 4,
    business_code: 'SKIPPED',
    message: '今日无数据 WANQI_PASSWORD',
  });
  assert.deepEqual(summarizeShippingResult({ channels: [legalCaishixian, unknownWanqi] }), {
    batchCount: 1,
    totalRows: 30,
    reportedOrders: 0,
    failedCount: 0,
    skippedCount: 0,
    contractErrorCount: 1,
    hasNewOrders: true,
  });
});

test('SKIPPED channels increment skippedCount and never count as an all-clear sync', () => {
  const empty = { batch_no: undefined, batch_id: undefined, row_counts: undefined } as const;
  const zeroOk = channel({ status: 'OK', ...empty });
  const skippedJufubao = channel({ channel: 'JUFUBAO', status: 'SKIPPED', business_code: 'SKIPPED', ...empty });
  const skippedFeixiang = channel({ channel: 'FEIXIANG', status: 'SKIPPED', business_code: 'SKIPPED', ...empty });
  assert.deepEqual(summarizeShippingResult({ channels: [zeroOk, skippedJufubao, skippedFeixiang] }), {
    batchCount: 0, totalRows: 0, reportedOrders: 0, failedCount: 0, skippedCount: 2,
    contractErrorCount: 0, hasNewOrders: false,
  });
  assert.deepEqual(summarizeShippingResult({ channels: [channel(), skippedJufubao, skippedFeixiang] }), {
    batchCount: 1, totalRows: 30, reportedOrders: 0, failedCount: 0, skippedCount: 2,
    contractErrorCount: 0, hasNewOrders: true,
  });
});
