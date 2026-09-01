import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import {
  editablePositiveCountFormValue,
  positiveCountFormValue,
} from '../src/pages/product/countFormValue.ts';

test('positive count form values convert integer text to JSON numbers', () => {
  assert.equal(positiveCountFormValue(3), 3);
  assert.equal(positiveCountFormValue('3'), 3);
  assert.equal(typeof positiveCountFormValue('3'), 'number');
});

test('incomplete legacy mappings stay editable but cannot be submitted unchanged', () => {
  assert.equal(editablePositiveCountFormValue(null), undefined);
  assert.equal(editablePositiveCountFormValue('2.5'), undefined);
  assert.equal(editablePositiveCountFormValue('2.000'), 2);
});

test('positive count form values reject decimal notation, fractions, non-positive values and int32 overflow', () => {
  for (const value of [2.5, '2.5', '3.000', 0, -1, 2_147_483_648, '', undefined]) {
    assert.throws(() => positiveCountFormValue(value), /int32 正整数/);
  }
});

test('count editors preserve raw input until validation instead of pre-rounding it', () => {
  for (const file of [
    'src/pages/product/BundlesPage.tsx',
    'src/pages/product/SkuMappingsPage.tsx',
  ]) {
    const source = readFileSync(file, 'utf8');
    assert.doesNotMatch(source, /InputNumber|precision=\{0\}/, file);
  }
});
