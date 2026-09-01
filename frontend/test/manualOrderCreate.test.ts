/**
 * 手工建单接缝（src/api/manualOrderCreate.ts）的纯函数单测：
 * 载荷装配（trim/空备注不发/V99 正整数纪律）、两把幂等键的重放语义、
 * 失败文案优先透传后端 message。
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  isValidManualQuantity,
  manualOrderCreateBody,
  manualOrderCreateRequest,
  manualOrderErrorText,
  manualOrderIdempotencyKey,
  manualOrderRoutingIdempotencyKey,
  manualOrderRoutingRequest,
} from '../src/api/manualOrderCreate.ts';
import { ApiError } from '../src/api/client.ts';

test('V99 数量纪律：只接受正整数字符串', () => {
  for (const valid of ['1', '3', '10', '999999']) assert.equal(isValidManualQuantity(valid), true, valid);
  for (const invalid of ['0', '01', '-1', '1.5', '', ' 3', '3 ', '3個', '1e3']) {
    assert.equal(isValidManualQuantity(invalid), false, invalid);
  }
});

test('表单值装配成契约载荷：trim 一切字符串，空备注整个不发', () => {
  assert.deepEqual(
    manualOrderCreateBody({
      customer_code: ' C001 ',
      receiver: { name: ' 李四 ', phone: ' 13900000000 ', address: ' 贵阳市观山湖区 1 号 ' },
      items: [{ sku_id: ' 15 ', quantity: '3' }],
      remark: '  ',
    }),
    {
      customer_code: 'C001',
      receiver: { name: '李四', phone: '13900000000', address: '贵阳市观山湖区 1 号' },
      items: [{ sku_id: '15', quantity: 3 }],
    },
  );
  assert.equal(
    manualOrderCreateBody({
      customer_code: 'C001',
      receiver: { name: '李四', phone: '139', address: '地址' },
      items: [{ sku_id: '15', quantity: '3' }],
      remark: ' 加急 ',
    }).remark,
    '加急',
  );
});

test('装配是最后一道断言：缺客户/缺收货三要素/坏数量/空行直接抛可上屏的错误', () => {
  const receiver = { name: '李四', phone: '139', address: '地址' };
  assert.throws(() => manualOrderCreateBody({ receiver, items: [{ sku_id: '15', quantity: '3' }] }), /请选择客户/);
  assert.throws(
    () => manualOrderCreateBody({ customer_code: 'C001', receiver: { ...receiver, phone: ' ' }, items: [{ sku_id: '15', quantity: '3' }] }),
    /收货人姓名、电话、地址均为必填/,
  );
  assert.throws(
    () => manualOrderCreateBody({ customer_code: 'C001', receiver, items: [{ quantity: '3' }] }),
    /第 1 行商品未选择/,
  );
  assert.throws(
    () => manualOrderCreateBody({ customer_code: 'C001', receiver, items: [{ sku_id: '15', quantity: '0' }] }),
    /第 1 行数量必须为正整数/,
  );
  assert.throws(() => manualOrderCreateBody({ customer_code: 'C001', receiver, items: [] }), /至少需要一行商品/);
});

test('建单幂等键：同草稿同内容重放，改内容或换草稿都换键', () => {
  const body = manualOrderCreateBody({
    customer_code: 'C001',
    receiver: { name: '李四', phone: '139', address: '地址' },
    items: [{ sku_id: '15', quantity: '3' }],
  });
  const key = manualOrderIdempotencyKey('draft-a', body);
  assert.match(key, /^manual-order-draft-a-[0-9a-f]{8}$/);
  assert.equal(key, manualOrderIdempotencyKey('draft-a', body), '同草稿同内容必须稳定重放');
  assert.notEqual(
    key,
    manualOrderIdempotencyKey('draft-a', { ...body, items: [{ sku_id: '15', quantity: '4' }] }),
    '内容变了必须换键——同键不同载荷会被服务端判 IDEMPOTENCY_CONFLICT',
  );
  assert.notEqual(
    key,
    manualOrderIdempotencyKey('draft-b', body),
    '新草稿必须换键——明天真实的第二张同内容订单不是重放',
  );
});

test('路由幂等键钉「订单 × 期望版本」，请求装配与契约一致', () => {
  assert.equal(manualOrderRoutingIdempotencyKey('901', 2), 'manual-order-routing-901-v2');
  assert.notEqual(manualOrderRoutingIdempotencyKey('901', 2), manualOrderRoutingIdempotencyKey('901', 5));

  assert.deepEqual(manualOrderCreateRequest(
    {
      customer_code: 'C001',
      receiver: { name: '李四', phone: '139', address: '地址' },
      items: [{ sku_id: '15', quantity: '3' }],
    },
    { 'Idempotency-Key': 'k1' },
  ).path, '/api/v1/orders/manual');

  assert.deepEqual(manualOrderRoutingRequest('901', 2, { 'Idempotency-Key': 'k2' }), {
    path: '/api/v1/orders/901/fulfillment-routing',
    options: {
      method: 'POST',
      body: { expected_order_version: 2 },
      headers: { 'Idempotency-Key': 'k2' },
    },
  });
});

test('失败文案优先透传后端 message（MANUAL_ORDER_* / ORDER_ROUTING_* 都是可行动的具体原因）', () => {
  assert.equal(
    manualOrderErrorText(new ApiError(422, {
      business_code: 'MANUAL_ORDER_CUSTOMER_NOT_FOUND',
      message: '客户不存在或已停用: C009',
      http_status: 422,
    })),
    '客户不存在或已停用: C009',
  );
  assert.equal(
    manualOrderErrorText(new ApiError(409, {
      business_code: 'ORDER_ROUTING_REVIEW_OPEN',
      message: '订单仍有开放复核事项',
      http_status: 409,
    })),
    '订单仍有开放复核事项',
  );
  // 后端没给 message 时落回通用分类文案（client.errorMessage），不裸奔空串。
  assert.equal(
    manualOrderErrorText(new ApiError(409, { business_code: 'VERSION_CONFLICT', message: '', http_status: 409 })),
    '数据已被其他操作更新，请刷新后重试',
  );
});
