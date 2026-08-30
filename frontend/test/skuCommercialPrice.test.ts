import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  COMMERCIAL_PRICE_PATTERN,
  buildProductWithInitialSkuBody,
  buildSkuCreateBody,
  buildSkuUpdateBody,
  commercialPriceLabel,
  optionalCommercialPrice,
  patchCommercialPrice,
} from '../src/pages/product/skuCommercialPrice.ts';

function openApiSchemaBlock(source: string, schemaName: string): string {
  const marker = `    ${schemaName}:\n`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `OpenAPI schema ${schemaName} must exist`);
  const tail = source.slice(start + marker.length);
  const nextSchema = tail.search(/\n    [A-Za-z][A-Za-z0-9]+:\n/);
  return nextSchema === -1 ? tail : tail.slice(0, nextSchema);
}

function openApiPropertyBlock(schema: string, propertyName: string): string {
  const marker = `        ${propertyName}:`;
  const start = schema.indexOf(marker);
  assert.notEqual(start, -1, `OpenAPI property ${propertyName} must exist`);
  const tail = schema.slice(start + marker.length);
  const nextProperty = tail.search(/\n        [a-z][a-z0-9_]*:/);
  return nextProperty === -1 ? tail : tail.slice(0, nextProperty);
}

test('OpenAPI 3.0.3 用显式 typed nullable schema 表达 SKU 未定价', () => {
  const openApi = readFileSync(
    fileURLToPath(new URL('../../docs/openapi.yaml', import.meta.url)),
    'utf8',
  );
  const nullablePrice = openApiSchemaBlock(openApi, 'NullableCommercialPrice');

  assert.match(nullablePrice, /^      type: string$/m);
  assert.match(nullablePrice, /^      nullable: true$/m);
  assert.match(nullablePrice, /\^\(0\|\[1-9\]\[0-9\]\{0,11\}\)/);
  for (const schemaName of ['SkuAttributes', 'SkuWrite', 'SkuPatch']) {
    const schema = openApiSchemaBlock(openApi, schemaName);
    for (const propertyName of ['purchase_price', 'retail_price']) {
      assert.match(
        openApiPropertyBlock(schema, propertyName),
        /\$ref:\s*'#\/components\/schemas\/NullableCommercialPrice'/,
        `${schemaName}.${propertyName} 应使用统一 nullable schema`,
      );
    }
  }
});

test('OpenAPI 公开 Product 品牌与 SKU 结构化包装身份', () => {
  const openApi = readFileSync(
    fileURLToPath(new URL('../../docs/openapi.yaml', import.meta.url)),
    'utf8',
  );
  for (const schemaName of ['ProductWrite', 'ProductPatch']) {
    assert.match(openApiSchemaBlock(openApi, schemaName), /^        brand_name:/m);
  }
  for (const schemaName of ['SkuAttributes', 'SkuWrite', 'InitialSkuWrite', 'SkuPatch']) {
    const schema = openApiSchemaBlock(openApi, schemaName);
    for (const property of ['net_content_value', 'net_content_unit', 'package_count', 'package_unit']) {
      assert.match(schema, new RegExp(`^        ${property}:`, 'm'), `${schemaName}.${property}`);
    }
  }
  assert.match(openApiSchemaBlock(openApi, 'SkuPatch'), /^        unit:/m);
});

test('商业价格只接受非负且最多两位小数的 decimal string', () => {
  for (const value of ['0', '0.00', '12', '12.3', '12.30', '999999999999.99']) {
    assert.equal(COMMERCIAL_PRICE_PATTERN.test(value), true, value);
    assert.equal(optionalCommercialPrice(` ${value} `), value);
  }

  for (const value of ['-0.01', '1.234', '.50', '01.00', '1000000000000.00']) {
    assert.equal(COMMERCIAL_PRICE_PATTERN.test(value), false, value);
    assert.throws(() => optionalCommercialPrice(value), /价格/);
  }
  assert.throws(() => optionalCommercialPrice(12.3), /decimal string/);
});

test('未定价与零元在输入和展示上严格分开', () => {
  assert.equal(optionalCommercialPrice(undefined), undefined);
  assert.equal(optionalCommercialPrice('  '), undefined);
  assert.equal(patchCommercialPrice(undefined), undefined);
  assert.equal(patchCommercialPrice('  '), null);
  assert.equal(patchCommercialPrice(null), null);

  assert.equal(commercialPriceLabel(null), '未定价');
  assert.equal(commercialPriceLabel('0.00'), '¥0.00');
  assert.equal(commercialPriceLabel('12.3'), '¥12.30');
});

test('SKU 新建和编辑把两个价格投影到公开 API 载荷', () => {
  assert.deepEqual(buildSkuCreateBody({
    provider_id: '11',
    product_id: '22',
    specification: '500g',
    unit: '袋',
    net_content_value: '500',
    net_content_unit: 'g',
    package_count: '2',
    package_unit: '袋',
    barcode: ' 690000000001 ',
    purchase_price: ' 12.30 ',
    retail_price: '',
    active: true,
  }), {
    provider_id: '11',
    product_id: '22',
    specification: '500g',
    unit: '袋',
    net_content_value: '500',
    net_content_unit: 'g',
    package_count: 2,
    package_unit: '袋',
    barcode: '690000000001',
    purchase_price: '12.30',
    retail_price: undefined,
    active: true,
  });

  assert.deepEqual(buildSkuUpdateBody({
    expected_version: 3,
    specification: '400g',
    unit: '件',
    net_content_value: '400',
    net_content_unit: 'g',
    package_count: '1',
    package_unit: '袋',
    barcode: '',
    purchase_price: '13',
    retail_price: '',
    active: false,
  }), {
    expected_version: 3,
    specification: '400g',
    unit: '件',
    net_content_value: '400',
    net_content_unit: 'g',
    package_count: 1,
    package_unit: '袋',
    barcode: null,
    purchase_price: '13',
    retail_price: null,
    active: false,
  });
});

test('商品档案新建提交新商品资料而不是已有商品标识', () => {
  const body = buildProductWithInitialSkuBody({
    product_code: 'PROD-NEW-001',
    product_name: '新商品',
    brand_name: '子牧',
    category_id: '9',
    provider_id: '11',
    specification: '500g',
    unit: '袋',
    net_content_value: '500',
    net_content_unit: 'g',
    package_count: '1',
    package_unit: '袋',
    barcode: ' 690000000009 ',
    purchase_price: '12.30',
    retail_price: '18',
    active: true,
  });

  assert.deepEqual(body, {
    product: {
      product_code: 'PROD-NEW-001',
      product_name: '新商品',
      brand_name: '子牧',
      category_id: '9',
      active: true,
    },
    sku: {
      provider_id: '11',
      specification: '500g',
      unit: '袋',
      net_content_value: '500',
      net_content_unit: 'g',
      package_count: 1,
      package_unit: '袋',
      barcode: '690000000009',
      purchase_price: '12.30',
      retail_price: '18',
      active: true,
    },
  });
  assert.equal('product_id' in body.sku, false);
});
