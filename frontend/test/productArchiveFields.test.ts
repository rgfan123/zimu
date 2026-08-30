import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildProductCreateBody,
  buildProductUpdateBody,
  leadTimeLabel,
  listingPeriodLabel,
  normalizeTags,
} from '../src/pages/product/productArchiveFields.ts';

/** JSON 序列化后断言，剔除值为 undefined 的键（与真实请求体一致）。 */
function compact(body: Record<string, unknown>): Record<string, unknown> {
  return JSON.parse(JSON.stringify(body));
}

test('product archive create body keeps filled fields and drops empty ones', () => {
  const body = compact(buildProductCreateBody({
    product_code: 'P-1001',
    product_name: '子牧羊小腿',
    category_id: '3',
    ingredients: '羔羊肉',
    tags: [' 预售 ', '预售', '应季'],
    listing_period: { from: '2026-09-01', to: '2026-11-30' },
    lead_time_hours: '48',
    purchase_price: '12.30',
    retail_price: '19.90',
    other_cost: '1.00',
    main_image_ref: 'product-images/abc.png',
    active: true,
  }));
  assert.deepEqual(body, {
    product_code: 'P-1001',
    product_name: '子牧羊小腿',
    category_id: '3',
    ingredients: '羔羊肉',
    tags: ['预售', '应季'],
    listed_from: '2026-09-01',
    listed_until: '2026-11-30',
    lead_time_hours: 48,
    main_image_ref: 'product-images/abc.png',
    active: true,
  });
});

test('product archive create body omits all optional fields when absent', () => {
  const body = compact(buildProductCreateBody({
    product_code: 'P-1001',
    product_name: '子牧羊小腿',
    category_id: '3',
    active: false,
  }));
  assert.deepEqual(body, {
    product_code: 'P-1001',
    product_name: '子牧羊小腿',
    category_id: '3',
    active: false,
  });
});

test('product archive update body distinguishes untouched, cleared and set fields', () => {
  const body = compact(buildProductUpdateBody({
    expected_version: 2,
    product_name: '子牧羊小腿',
    category_id: '3',
    ingredients: '',
    tags: [],
    listing_period: {},
    lead_time_hours: '',
    purchase_price: null,
    retail_price: '25.00',
    other_cost: undefined,
    main_image_ref: null,
    active: undefined,
  }));
  assert.deepEqual(body, {
    expected_version: 2,
    product_name: '子牧羊小腿',
    category_id: '3',
    ingredients: null,
    tags: null,
    listed_from: null,
    listed_until: null,
    lead_time_hours: null,
    main_image_ref: null,
  });
});

test('product archive update body only fills start of the listing period', () => {
  const body = compact(buildProductUpdateBody({
    expected_version: 0,
    listing_period: { from: '2026-09-01' },
  }));
  assert.deepEqual(body, {
    expected_version: 0,
    listed_from: '2026-09-01',
    listed_until: null,
  });
});

test('product archive display formatters cover hours and listing periods', () => {
  assert.equal(leadTimeLabel(48), '48小时内发货');
  assert.equal(leadTimeLabel(undefined), '—');
  assert.equal(listingPeriodLabel('2026-09-01', '2026-11-30'), '2026-09-01 ~ 2026-11-30');
  assert.equal(listingPeriodLabel('2026-09-01', undefined), '2026-09-01 起');
  assert.equal(listingPeriodLabel(undefined, '2026-11-30'), '至 2026-11-30');
  assert.equal(listingPeriodLabel(undefined, undefined), '—');
});

test('product archive tags normalize trims and dedupes', () => {
  assert.deepEqual(normalizeTags([' a ', 'a', 'b', '', 'b']), ['a', 'b']);
  assert.equal(normalizeTags([]), undefined);
  assert.equal(normalizeTags(['', '  ']), undefined);
  assert.equal(normalizeTags(undefined), undefined);
});
