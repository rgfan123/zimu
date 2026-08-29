import assert from 'node:assert/strict';
import { test } from 'node:test';
import { pendingSourceSyncNote, presentPendingSourceSync } from '../src/pages/workbench/pendingSourceSync.ts';

function shipment(overrides: Record<string, unknown> = {}) {
  return {
    id: '29',
    shipment_no: 'SHP-29',
    source_channel: 'JUFUBAO',
    source_sync_status: null,
    receiver_name: '王冰冰',
    tracking: { tracking_number: 'JDVA46790850228' },
    ...overrides,
  };
}

test('已发货且渠道支持在线回传的批次会进待办', () => {
  const view = presentPendingSourceSync([shipment()]);

  assert.equal(view.total, 1);
  assert.equal(view.rows[0].shipmentNo, 'SHP-29');
  assert.equal(view.rows[0].trackingNumber, 'JDVA46790850228');
});

test('已回传成功的不再出现', () => {
  const view = presentPendingSourceSync([shipment({ source_sync_status: 'SYNCED' })]);

  assert.equal(view.total, 0);
});

test('回传失败和从没回传过一样是待办——都还没告诉客户平台', () => {
  for (const status of ['FAILED', 'RECONCILIATION_REQUIRED', 'SYNCING']) {
    const view = presentPendingSourceSync([shipment({ source_sync_status: status })]);
    assert.equal(view.total, 1, status);
    assert.notEqual(pendingSourceSyncNote(view.rows[0]), null, status);
  }
});

test('没有运单号就不算待回传——没东西可告诉平台', () => {
  assert.equal(presentPendingSourceSync([shipment({ tracking: null })]).total, 0);
});

test('不走在线回传的渠道不进这张清单', () => {
  // 大者/中汇走文件回传，混进来只会让人以为漏了。
  for (const channel of ['DAZHE', 'ZHONGHUI', 'WECOM']) {
    assert.equal(presentPendingSourceSync([shipment({ source_channel: channel })]).total, 0, channel);
  }
});

test('从没回传过的不加多余注解', () => {
  const view = presentPendingSourceSync([shipment()]);

  assert.equal(pendingSourceSyncNote(view.rows[0]), null);
});
