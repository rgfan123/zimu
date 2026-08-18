import assert from 'node:assert/strict';
import test from 'node:test';
import { productIdentityPresentation } from '../src/pages/shared/productIdentityPresentation.ts';

test('商品身份统一以名称为主、编码为次、规格单位为补充', () => {
  assert.deepEqual(productIdentityPresentation({
    name: '上脑肉片',
    code: 'SKU-JD-000005',
    meta: ['1kg', '件'],
  }), {
    primary: '上脑肉片',
    secondary: 'SKU-JD-000005',
    meta: '1kg · 件',
  });
});

test('没有名称时保守显示编码，不伪造商品名称', () => {
  assert.deepEqual(productIdentityPresentation({ code: 'SKU-ONLY-001' }), {
    primary: 'SKU-ONLY-001',
    secondary: undefined,
    meta: undefined,
  });
});
