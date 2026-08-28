/**
 * 就地处置面板的订单事实区投影（订单来源 / 订单信息 / 收货人 / 商品清单）。
 *
 * 面板里的动作都在改真实订单，所以事实区的每一项都得可信：
 * 商品名必须是平台原名，时间不能把「我方入库」冒充成「平台下单」，
 * 拿不到的东西显示占位而不是猜一个。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  blockedOrderLineIds,
  fullAddress,
  presentOrderContext,
  type OrderContextSource,
} from '../src/pages/workbench/orderContext.ts';
import type { StockBlockerItem } from '../src/pages/workbench/stockBlockerCases.ts';

function blocker(orderLineIds: string[]): StockBlockerItem {
  return {
    code: 'JD_STOCK_INSUFFICIENT',
    message: '目标仓可用库存不足',
    goodsNo: 'JD-SKU-000001',
    productName: '羊小腿',
    skuCode: 'SKU-JD-000001',
    skuId: '1',
    orderLineIds,
    missingField: null,
    mappingIssueCode: null,
  };
}

function order(overrides: Partial<OrderContextSource> = {}): OrderContextSource {
  return {
    order_no: 'SO-20260828-0007',
    source_channel: 'JUFUBAO',
    source_ref: 'm951890039794349980',
    order_status: 'NEED_REVIEW',
    source_ordered_at: '2026-08-28T10:15:00Z',
    created_at: '2026-08-28T11:00:00Z',
    receiver: {
      name: '丁小满', phone: '13800001111',
      province: '上海市', city: '上海市', district: '浦东新区', town: '张江镇',
      address: '科苑路 88 号',
    },
    lines: [
      {
        id: '5',
        product_name: '【京东配送】子牧牛肉惠选礼包1400g',
        specification: '1400g/盒', unit: '盒', requested_quantity: '2', sku_code: 'SKU-JD-000048',
      },
      {
        id: '6',
        product_name: '乔府大院金饭碗五常大米5kg',
        specification: '5kg/袋', unit: '袋', requested_quantity: '1', sku_code: null,
      },
    ],
    ...overrides,
  };
}

test('订单来源与订单信息按原码给出，中文标签留给渲染层套', () => {
  const view = presentOrderContext(order());
  assert.equal(view.sourceChannel, 'JUFUBAO');
  assert.equal(view.sourceRef, 'm951890039794349980');
  assert.equal(view.orderNo, 'SO-20260828-0007');
  assert.equal(view.orderStatus, 'NEED_REVIEW');
});

test('下单时间优先取平台下单时刻', () => {
  const view = presentOrderContext(order());
  assert.equal(view.orderedAt, '2026-08-28 18:15');
  assert.equal(view.orderedAtIsFallback, false);
});

test('平台下单时刻缺失时退回入库时间，并标明这是回退值（不冒充下单时间）', () => {
  const view = presentOrderContext(order({ source_ordered_at: null }));
  assert.equal(view.orderedAt, '2026-08-28 19:00');
  assert.equal(view.orderedAtIsFallback, true);
});

test('收货人三项齐全（内部工作台可展示，但不得外流企微/对外接口）', () => {
  const view = presentOrderContext(order());
  assert.equal(view.receiverName, '丁小满');
  assert.equal(view.receiverPhone, '13800001111');
  assert.equal(view.receiverAddress, '上海市 上海市 浦东新区 张江镇 科苑路 88 号');
});

test('收货人缺失时为 null 而不是空串，渲染层据此显示占位', () => {
  const view = presentOrderContext(order({ receiver: { name: '  ', phone: null } }));
  assert.equal(view.receiverName, null);
  assert.equal(view.receiverPhone, null);
  assert.equal(view.receiverAddress, null);
});

test('商品清单用平台原名，带规格与数量', () => {
  const view = presentOrderContext(order());
  assert.deepEqual(view.lines.map((line) => line.productName), [
    '【京东配送】子牧牛肉惠选礼包1400g',
    '乔府大院金饭碗五常大米5kg',
  ]);
  assert.equal(view.lines[0].specification, '1400g/盒');
  assert.equal(view.lines[0].quantity, '2');
  assert.equal(view.lines[0].unit, '盒');
});

test('本次阻断锁定的行被标出来，其余行不标', () => {
  const view = presentOrderContext(order(), [blocker(['6'])]);
  assert.deepEqual(view.lines.map((line) => line.blocked), [false, true]);
});

test('没有阻断时一行都不标', () => {
  assert.deepEqual(presentOrderContext(order()).lines.map((l) => l.blocked), [false, false]);
});

test('商品名缺失时显示诚实占位，不回退到会让人对错单的名字', () => {
  const view = presentOrderContext(order({
    lines: [{ id: '9', product_name: null, requested_quantity: null }],
  }));
  assert.equal(view.lines[0].productName, '（商品名缺失）');
  assert.equal(view.lines[0].quantity, '—');
});

test('没有商品行时给空数组，不崩', () => {
  assert.deepEqual(presentOrderContext(order({ lines: null })).lines, []);
});

test('fullAddress：空段跳过，全空返回 null', () => {
  assert.equal(fullAddress({ province: '上海市', city: '', district: '浦东新区', address: '科苑路 88 号' }),
    '上海市 浦东新区 科苑路 88 号');
  assert.equal(fullAddress({ province: '  ', address: null }), null);
  assert.equal(fullAddress(null), null);
});

test('blockedOrderLineIds：跨阻断合并去重', () => {
  const ids = blockedOrderLineIds([blocker(['5', '6']), blocker(['6', '7'])]);
  assert.deepEqual([...ids].sort(), ['5', '6', '7']);
});
