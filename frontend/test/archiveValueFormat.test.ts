import assert from 'node:assert/strict';
import test from 'node:test';
import { formatArchiveValue } from '../src/pages/product/archiveValueFormat.ts';

test('成本金额圆整到 2 位小数并保留完整原值供悬停', () => {
  assert.deepEqual(formatArchiveValue('AF', '71.0098888888889'), { text: '71.01', full: '71.0098888888889' });
  assert.deepEqual(formatArchiveValue('AI', '73.0198888888889'), { text: '73.02', full: '73.0198888888889' });
  assert.deepEqual(formatArchiveValue('AD', '0.242'), { text: '0.24', full: '0.242' });
});

test('本就两位以内的金额原样展示且不挂 tooltip', () => {
  assert.deepEqual(formatArchiveValue('N', '49.49'), { text: '49.49' });
  assert.deepEqual(formatArchiveValue('M', '49'), { text: '49' });
  assert.deepEqual(formatArchiveValue('AJ', '78'), { text: '78' });
});

test('比率列显示为百分比', () => {
  assert.deepEqual(formatArchiveValue('R', '0.0671654015886526'), { text: '6.72%', full: '0.0671654015886526' });
  assert.deepEqual(formatArchiveValue('S', '0.05'), { text: '5%', full: '0.05' });
  assert.deepEqual(formatArchiveValue('AN', '0'), { text: '0%', full: '0' });
  assert.deepEqual(formatArchiveValue('AK', '0.251452091767881'), { text: '25.15%', full: '0.251452091767881' });
});

test('整数（规格/净含量）原样透传', () => {
  assert.deepEqual(formatArchiveValue('C', '1000'), { text: '1000' });
  assert.deepEqual(formatArchiveValue('K', '1010'), { text: '1010' });
});

test('非数值文本原样透传', () => {
  assert.deepEqual(formatArchiveValue('E', '子牧'), { text: '子牧' });
  assert.deepEqual(formatArchiveValue('I', '彩袋'), { text: '彩袋' });
  assert.deepEqual(formatArchiveValue('B', '停产'), { text: '停产' });
  assert.deepEqual(formatArchiveValue('A', '小龙坎火锅拼盘（肉类6拼）'), { text: '小龙坎火锅拼盘（肉类6拼）' });
});

test('异常形状不抛错原样返回', () => {
  assert.deepEqual(formatArchiveValue('M', ''), { text: '' });
  assert.deepEqual(formatArchiveValue('M', '1,000'), { text: '1,000' });
  assert.deepEqual(formatArchiveValue('M', '49.49元'), { text: '49.49元' });
});
