import assert from 'node:assert/strict';
import test from 'node:test';

import {
  describeSchedule,
  isDraftDirty,
  normalizeSchedule,
  schedulableConnectors,
  toDraft,
  toPatchBody,
  validateDraft,
} from '../src/pages/system/pullSchedule.ts';
import type { ConnectorConfig, ConnectorPullSchedule } from '../src/api/types.ts';

/**
 * 前端侧的空值纪律：与后端 `ChannelPullSchedule` 同一套语义——
 * 读不到就按默认拉，绝不等于「不拉」；停某一档必须是显式的 enabled: false。
 */

const CONFIGURED: ConnectorPullSchedule = {
  schedulable: true,
  configured: true,
  morning: { enabled: true, at: '07:30' },
  evening: { enabled: false, at: '21:00' },
  notify_wecom: false,
};

test('后端没给时间表时回落成「照常拉」而不是「不拉」', () => {
  const schedule = normalizeSchedule(undefined);

  assert.equal(schedule.morning.enabled, true);
  assert.equal(schedule.morning.at, '09:00');
  assert.equal(schedule.evening.enabled, true);
  assert.equal(schedule.evening.at, '18:00');
  assert.equal(schedule.notify_wecom, true);
});

test('时间字段是垃圾值时回落默认时间，不会渲染出空框', () => {
  const schedule = normalizeSchedule({
    schedulable: true,
    configured: true,
    morning: { enabled: true, at: '两点半' } as never,
    evening: { enabled: true, at: '' } as never,
    notify_wecom: true,
  });

  assert.equal(schedule.morning.at, '09:00');
  assert.equal(schedule.evening.at, '18:00');
});

test('只有真正的布尔 false 才算停用', () => {
  const stringy = normalizeSchedule({
    schedulable: true,
    configured: true,
    morning: { enabled: 'false', at: '09:00' } as never,
    evening: { enabled: false, at: '18:00' },
    notify_wecom: true,
  });

  // 字符串 "false" 不是停用：一次写入方式的 bug 不该把这一档安静地关掉。
  assert.equal(stringy.morning.enabled, true);
  // 显式的 false 必须留住。
  assert.equal(stringy.evening.enabled, false);
});

test('只有 schedulable 的渠道才出卡片', () => {
  const rows = [
    { source_channel: 'FEIXIANG', pull_schedule: CONFIGURED },
    { source_channel: 'ZHONGHUI', pull_schedule: { ...CONFIGURED, schedulable: false } },
    // 老后端没有这个字段：宁可不出卡片，也不要出一张点了保存必然 422 的卡。
    { source_channel: 'WECOM' },
  ] as unknown as ConnectorConfig[];

  assert.deepEqual(
    schedulableConnectors(rows).map((row) => row.source_channel),
    ['FEIXIANG'],
  );
});

test('写入体总是五个字段齐全（整体替换，不做部分 patch）', () => {
  const body = toPatchBody(toDraft(CONFIGURED));

  assert.deepEqual(body, {
    morning: { enabled: true, at: '07:30' },
    evening: { enabled: false, at: '21:00' },
    notify_wecom: false,
  });
  assert.deepEqual(Object.keys(body).sort(), ['evening', 'morning', 'notify_wecom']);
});

test('时间不合法时拦在提交前', () => {
  assert.equal(validateDraft(toDraft(CONFIGURED)), null);
  assert.match(
    validateDraft({ ...toDraft(CONFIGURED), eveningAt: '25:00' }) ?? '',
    /第二次拉取时间/,
  );
});

test('没改过不算脏，改任一字段就算脏', () => {
  const draft = toDraft(CONFIGURED);

  assert.equal(isDraftDirty(draft, CONFIGURED), false);
  assert.equal(isDraftDirty({ ...draft, morningAt: '08:00' }, CONFIGURED), true);
  assert.equal(isDraftDirty({ ...draft, notifyWecom: true }, CONFIGURED), true);
  assert.equal(isDraftDirty({ ...draft, eveningEnabled: true }, CONFIGURED), true);
});

test('卡片上直说现在会怎么跑，包括「一次都不拉」这种情况', () => {
  assert.equal(
    describeSchedule(toDraft(CONFIGURED)),
    '每天第一次 07:30 自动拉单，拉完不推企微',
  );
  assert.equal(
    describeSchedule({
      morningEnabled: true,
      morningAt: '09:00',
      eveningEnabled: true,
      eveningAt: '18:00',
      notifyWecom: true,
    }),
    '每天第一次 09:00、第二次 18:00 自动拉单，拉完推企微',
  );
  assert.equal(
    describeSchedule({
      morningEnabled: false,
      morningAt: '09:00',
      eveningEnabled: false,
      eveningAt: '18:00',
      notifyWecom: true,
    }),
    '两次拉取都已停用，这个平台不会自动拉单',
  );
});
