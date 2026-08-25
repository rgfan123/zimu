import assert from 'node:assert/strict';
import test from 'node:test';
import {
  collectPushChannelBatchIds,
  isOnlinePushChannel,
  platformImportBatchId,
  platformChannelResultText,
  sourcePushButtonVisible,
  sourceReturnPushOutcome,
  type FulfillmentExport,
  type PlatformOrderRefreshResult,
  type SourceChannel,
  type SourceReturnExport,
} from '../src/api/types.ts';

function sourceReturn(overrides: Partial<SourceReturnExport> = {}): SourceReturnExport {
  return {
    id: 'sr-1',
    import_batch_id: 'ib-1',
    version_no: 1,
    is_final: true,
    template_version: 'source-return-v1',
    tracking_cutoff_at: '2026-08-13T08:00:00Z',
    file_sha256: 'a'.repeat(64),
    generated_at: '2026-08-13T08:00:00Z',
    ...overrides,
  };
}

function channel(overrides: Partial<PlatformOrderRefreshResult['channels'][number]>): PlatformOrderRefreshResult['channels'][number] {
  return { channel: 'CAISHIXIAN', status: 'OK', ...overrides };
}

function exportRow(overrides: Partial<FulfillmentExport>): FulfillmentExport {
  return {
    id: 'fe-1',
    export_batch_no: 'EXP-1',
    provider_id: 'p1',
    export_kind: 'JD_WAREHOUSE',
    template_version: 'v1',
    generated_at: '2026-08-13T08:00:00Z',
    usage_status: 'DOWNLOADED_WAITING_RETURN',
    ...overrides,
  };
}

// ---------- C3：推送结果区分「平台明确拒绝」vs「结果未知」 ----------

test('SUCCESS 推送展示平台受理引用', () => {
  const outcome = sourceReturnPushOutcome(sourceReturn({
    push_status: 'SUCCESS',
    push_platform_ref: 'CSX-20260813-001',
  }));
  assert.equal(outcome.kind, 'SUCCESS');
  assert.match(outcome.text, /CSX-20260813-001/);
  assert.match(outcome.text, /已推送到平台/);
});

test('SUCCESS 无平台引用时回退「已受理」', () => {
  const outcome = sourceReturnPushOutcome(sourceReturn({ push_status: 'SUCCESS', push_platform_ref: null }));
  assert.equal(outcome.kind, 'SUCCESS');
  assert.match(outcome.text, /已受理/);
});

test('FAILED 且 push_error.unknown_outcome=true → 结果未知，需先核实再决定是否重推', () => {
  const outcome = sourceReturnPushOutcome(sourceReturn({
    push_status: 'FAILED',
    push_error: { unknown_outcome: true, message: '平台受理结果未知' },
  }));
  assert.equal(outcome.kind, 'UNKNOWN');
  assert.match(outcome.text, /结果未知/);
  assert.match(outcome.text, /核实是否已受理/);
  assert.match(outcome.text, /重推/);
});

test('FAILED 且 push_error 带 message → 平台明确拒绝，展示后端原因', () => {
  const outcome = sourceReturnPushOutcome(sourceReturn({
    push_status: 'FAILED',
    push_error: { code: 'PLATFORM_REJECTED', message: '物流公司不存在' },
  }));
  assert.equal(outcome.kind, 'REJECTED');
  assert.match(outcome.text, /推送失败：物流公司不存在/);
});

test('FAILED 且 push_error 仅带 code → 回退展示 code', () => {
  const outcome = sourceReturnPushOutcome(sourceReturn({
    push_status: 'FAILED',
    push_error: { code: 'HTTP_502' },
  }));
  assert.equal(outcome.kind, 'REJECTED');
  assert.match(outcome.text, /HTTP_502/);
});

test('FAILED 且 platform_response 带 request_id → 提示透出平台请求号（P2 原始响应全量透出）', () => {
  const outcome = sourceReturnPushOutcome(sourceReturn({
    push_status: 'FAILED',
    push_error: {
      code: 'InvalidArgument',
      message: '快递单号只允许包含字母和数字',
      platform_response: { code: 'InvalidArgument', message: '快递单号只允许包含字母和数字', request_id: 'REQ-42' },
    },
  }));
  assert.equal(outcome.kind, 'REJECTED');
  assert.match(outcome.text, /推送失败：快递单号只允许包含字母和数字/);
  assert.match(outcome.text, /REQ-42/);
});

test('FAILED 且无 push_error → 回退「平台拒绝」', () => {
  const outcome = sourceReturnPushOutcome(sourceReturn({ push_status: 'FAILED', push_error: null }));
  assert.equal(outcome.kind, 'REJECTED');
  assert.match(outcome.text, /平台拒绝/);
});

test('非终态（PUSHING / NOT_PUSHED）与 null → OTHER 状态提示', () => {
  assert.equal(sourceReturnPushOutcome(sourceReturn({ push_status: 'PUSHING' })).kind, 'OTHER');
  assert.equal(sourceReturnPushOutcome(sourceReturn({ push_status: 'NOT_PUSHED' })).kind, 'OTHER');
  assert.deepEqual(sourceReturnPushOutcome(null), { kind: 'OTHER', text: '推送状态未知' });
});

// ---------- C1：在线回传仅支持彩食鲜/聚福宝 ----------

test('在线回传渠道闸门：仅彩食鲜/聚福宝通过', () => {
  assert.equal(isOnlinePushChannel('CAISHIXIAN'), true);
  assert.equal(isOnlinePushChannel('JUFUBAO'), true);
  assert.equal(isOnlinePushChannel('FEIXIANG'), false);
  assert.equal(isOnlinePushChannel('ZHONGHUI'), false);
  assert.equal(isOnlinePushChannel('WECOM'), false);
  assert.equal(isOnlinePushChannel(undefined), false);
  assert.equal(isOnlinePushChannel(null), false);
});

// ---------- P3：推送按钮按渠道过滤显示（渲染期零 N+1 的渠道映射 + 显示判定） ----------

test('P3 去重收集需解析渠道的批次 id，优先来源批次 import_batch_id', () => {
  const ids = collectPushChannelBatchIds([
    exportRow({ id: 'a', import_batch_id: 'ib-1', tracking_import_batch_id: 'tb-1' }),
    exportRow({ id: 'b', import_batch_id: 'ib-1' }),
    exportRow({ id: 'c', tracking_import_batch_id: 'tb-2' }),
    exportRow({ id: 'd' }),
  ]);
  assert.deepEqual(ids, ['ib-1', 'tb-2']);
});

test('P3 仅在线回传渠道（彩食鲜/聚福宝）显示推送按钮，其余渠道不显示', () => {
  const map = new Map<string, SourceChannel | undefined | null>([
    ['ib-csx', 'CAISHIXIAN'],
    ['ib-jfb', 'JUFUBAO'],
    ['ib-fx', 'FEIXIANG'],
    ['ib-zh', 'ZHONGHUI'],
    ['ib-null', null],
  ]);
  assert.equal(sourcePushButtonVisible(exportRow({ import_batch_id: 'ib-csx' }), map), true);
  assert.equal(sourcePushButtonVisible(exportRow({ import_batch_id: 'ib-jfb' }), map), true);
  assert.equal(sourcePushButtonVisible(exportRow({ import_batch_id: 'ib-fx' }), map), false);
  assert.equal(sourcePushButtonVisible(exportRow({ import_batch_id: 'ib-zh' }), map), false);
  assert.equal(sourcePushButtonVisible(exportRow({ import_batch_id: 'ib-null' }), map), false);
});

test('P3 渠道未知（无批次 id / 映射缺失 / 拉取失败）→ fail-closed 不显示按钮', () => {
  const map = new Map<string, SourceChannel | undefined | null>();
  assert.equal(sourcePushButtonVisible(exportRow({ import_batch_id: 'ib-1' }), map), false);
  assert.equal(sourcePushButtonVisible(exportRow({ import_batch_id: 'ib-missing' }), map), false);
  assert.equal(sourcePushButtonVisible(exportRow({ tracking_import_batch_id: 'tb-1' }), map), false);
  assert.equal(sourcePushButtonVisible(exportRow({}), map), false);
});

test('P3 SDK 直连行（仅 tracking_import_batch_id）渠道恒为 null → 不显示按钮', () => {
  // PROVIDER_TRACKING 批次按 schema 无 source_channel（import_batches CHECK 强制 NULL），
  // 即使映射成功也解析为 null → fail-closed。
  const map = new Map<string, SourceChannel | undefined | null>([['tb-1', null]]);
  assert.equal(sourcePushButtonVisible(exportRow({ tracking_import_batch_id: 'tb-1' }), map), false);
});

// ---------- C2：三平台刷新结果文案（SKIPPED 频控提示必须透出） ----------

test('SKIPPED 渠道透出后端频控 message（距上次拉取不足…）', () => {
  const text = platformChannelResultText(channel({
    status: 'SKIPPED',
    message: '距上次拉取不足 12 小时',
  }));
  assert.match(text, /^已跳过：距上次拉取不足 12 小时$/);
});

test('SKIPPED 无 message 时给出合规兜底文案', () => {
  const text = platformChannelResultText(channel({ status: 'SKIPPED' }));
  assert.match(text, /已跳过：达到每日拉取上限或拉取间隔不足/);
});

test('OK 且已生成导入批次 → 展示批次与行计数', () => {
  const text = platformChannelResultText(channel({
    status: 'OK',
    batch_no: 'SO-20260813-01',
    row_counts: { total: 10, accepted: 8, need_review: 1, rejected: 1 },
  }));
  assert.match(text, /批次 SO-20260813-01/);
  assert.match(text, /已接收 8 行/);
  assert.match(text, /待复核 1/);
  assert.match(text, /拒绝 1/);
});

test('OK 且仅拉取订单数（聚福宝）→ 展示拉取单数', () => {
  const text = platformChannelResultText(channel({ status: 'OK', order_count: 5 }));
  assert.match(text, /已拉取 5 单/);
});

test('FAILED → 展示后端失败原因，缺失时兜底「失败」', () => {
  assert.match(platformChannelResultText(channel({ status: 'FAILED', message: '凭据文件不存在' })), /凭据文件不存在/);
  assert.equal(platformChannelResultText(channel({ status: 'FAILED' })), '失败');
});

test('仅成功生成导入批次的刷新结果可进入人工确认', () => {
  assert.equal(platformImportBatchId(channel({ status: 'OK', batch_id: '23' })), '23');
  assert.equal(platformImportBatchId(channel({ status: 'OK' })), null);
  assert.equal(platformImportBatchId(channel({ status: 'FAILED', batch_id: '24' })), null);
  assert.equal(platformImportBatchId(channel({ status: 'SKIPPED', batch_id: '25' })), null);
});
