import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildProductCreateBody } from '../src/pages/product/productArchiveFields.ts';
import { buildProductWithInitialSkuBody } from '../src/pages/product/skuCommercialPrice.ts';

/**
 * 商品编码留空时必须**整个不发这个字段**。
 *
 * <p>2026-08-30 生产事故：V86 把 product_code 改成「留空由触发器发号」，前端表单也改成
 * 选填、提示「留空自动生成」，但两处 body 构造仍是 `String(values.product_code)`。
 * AntD 对未填字段给 `undefined`，`String(undefined)` 是**字面量 "undefined"**，
 * 触发器看它非空就不发号——界面承诺的自动生成静默失效，还往库里写进一个假编码。
 *
 * <p>生产实测确认过：POST 一个 product_code="undefined" 会原样落库（已清理）。
 * 这两条用例就是钉住那一刻。
 */

test('商品编码留空时不发该字段——不能发字面量 undefined', () => {
  const body = buildProductCreateBody({
    product_name: '测试商品',
    category_id: '8',
  }) as Record<string, unknown>;

  assert.equal('product_code' in body, false, '留空时不应出现 product_code 键');
  // 最要命的两种错法，逐一钉死
  assert.notEqual(body.product_code, 'undefined');
  assert.notEqual(body.product_code, '');
});

test('建商品带初始 SKU 时同理', () => {
  const body = buildProductWithInitialSkuBody({
    product_name: '测试商品',
    category_id: '8',
    provider_id: '2',
    specification: '5kg',
    unit: '袋',
  }) as { product: Record<string, unknown> };

  assert.equal('product_code' in body.product, false);
  assert.notEqual(body.product.product_code, 'undefined');
});

test('显式填了编码仍要原样发出——外部约定的编码必须能存活', () => {
  const body = buildProductCreateBody({
    product_code: 'PROD-LOCAL-R099',
    product_name: '测试商品',
    category_id: '8',
  }) as Record<string, unknown>;

  assert.equal(body.product_code, 'PROD-LOCAL-R099');
});

test('只填了空白字符等同于留空', () => {
  // 人在输入框里敲了个空格再删不干净，不该因此变成「显式指定了编码」。
  const body = buildProductCreateBody({
    product_code: '   ',
    product_name: '测试商品',
    category_id: '8',
  }) as Record<string, unknown>;

  assert.equal('product_code' in body, false);
});
