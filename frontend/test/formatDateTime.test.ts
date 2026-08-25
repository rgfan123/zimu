import assert from 'node:assert/strict';
import test from 'node:test';
import { formatDateTime, formatDateTimeSeconds } from '../src/format/dateTime.ts';

test('formatDateTime renders Asia/Shanghai YYYY-MM-DD HH:mm for UTC timestamps', () => {
  // 2026-08-13T01:02:03Z = 2026-08-13 09:02:03 Asia/Shanghai
  assert.equal(formatDateTime('2026-08-13T01:02:03Z'), '2026-08-13 09:02');
  assert.equal(formatDateTime('2026-08-13T09:02:03+08:00'), '2026-08-13 09:02');
});

test('formatDateTimeSeconds adds seconds for precise contexts', () => {
  assert.equal(formatDateTimeSeconds('2026-08-13T01:02:03Z'), '2026-08-13 09:02:03');
});

test('formatDateTime falls back on empty or invalid input', () => {
  assert.equal(formatDateTime(null), '—');
  assert.equal(formatDateTime(undefined), '—');
  assert.equal(formatDateTime('not-a-date'), '—');
  assert.equal(formatDateTime('', '未提供'), '未提供');
});
