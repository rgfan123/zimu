/**
 * 「待我人工复核」卡片文案投影（UIUX 2026-08-28 用户反馈）。
 *
 * 用户原话：「在没有点进去之前，这个预览该显示的应该是什么缺货，而不是这么晦涩难懂的一串文字」。
 * 改造前主行是 `RC-JD-STOCK-B9C7…` 事项编码、副行是我方内部 SKU 名。
 *
 * 本文件锁死三件容易长歪的事：
 * 1. 商品名必须是**平台原名**（order_lines.product_name_snapshot），不是我方 products.product_name；
 * 2. 原因由 blocker 决定，**不得写死「缺货」**——14 种映射门禁失败塌缩在同一个外层码下，
 *    只有 JD_STOCK_INSUFFICIENT 才是真缺货（生产实测有卡片把「映射没配」说成缺货）；
 * 3. RC 编码不上主行，只留在 caseNo 供排查对单。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  orderIdsToLoad,
  platformProductName,
  presentStockBlockerCard,
  type OrderFacts,
} from '../src/pages/workbench/reviewCardPresentation.ts';
import type { StockBlockerCase, StockBlockerItem } from '../src/pages/workbench/stockBlockerCases.ts';

function blocker(overrides: Partial<StockBlockerItem> = {}): StockBlockerItem {
  return {
    code: 'JD_SKU_MAPPING_GATE_BLOCKED',
    message: '未配置京东履约方商品映射',
    goodsNo: 'EMG4418918549603',
    // 我方内部 SKU 名——卡片**不该**显示这个。
    productName: '牛肉饼(1.2kg)',
    skuCode: 'SKU-JD-000048',
    skuId: '48',
    orderLineIds: ['5'],
    missingField: null,
    mappingIssueCode: null,
    ...overrides,
  };
}

function reviewCase(overrides: Partial<StockBlockerCase> = {}): StockBlockerCase {
  return {
    caseId: '42',
    caseNo: 'RC-JD-STOCK-B9C7F7B5529146B89A0E7631E82CB6D5',
    shipmentId: '17',
    orderId: '31',
    orderNo: 'SO-20260828-0007',
    blockers: [blocker()],
    ...overrides,
  };
}

/** 聚福宝的真实平台原名形状：带渠道前缀，与我方内部名完全不同。 */
const FACTS: OrderFacts = {
  orderNo: 'SO-20260828-0007',
  receiverName: '丁小满',
  receiverPhone: '13800001111',
  receiverAddress: '上海市 浦东新区 张江镇 科苑路 88 号',
  productNameByLineId: { '5': '【京东配送】子牧牛肉惠选礼包1400g' },
};

test('主行用平台原名而非我方内部 SKU 名', () => {
  const view = presentStockBlockerCard(reviewCase(), FACTS);
  assert.match(view.title, /^【京东配送】子牧牛肉惠选礼包1400g/);
  assert.doesNotMatch(view.title, /牛肉饼/, '内部 SKU 名不得上卡片主行');
});

test('拿不到订单详情时退回 blocker 自带的名字，不空着', () => {
  const view = presentStockBlockerCard(reviewCase(), null);
  assert.match(view.title, /^牛肉饼\(1\.2kg\)/);
});

test('RC 事项编码不出现在主行与副行，只留在 caseNo 供排查', () => {
  const view = presentStockBlockerCard(reviewCase(), FACTS);
  assert.doesNotMatch(view.title, /RC-JD-STOCK/);
  assert.doesNotMatch(view.subtitle, /RC-JD-STOCK/);
  assert.equal(view.caseNo, 'RC-JD-STOCK-B9C7F7B5529146B89A0E7631E82CB6D5');
});

test('副行是收货人信息，不是内部 SKU 名', () => {
  const view = presentStockBlockerCard(reviewCase(), FACTS);
  assert.equal(view.subtitle, '收货人 丁小满 · 13800001111 · 上海市 浦东新区 张江镇 科苑路 88 号');
});

test('收货人未加载时诚实说明，不编造', () => {
  const view = presentStockBlockerCard(reviewCase(), null);
  assert.equal(view.subtitle, '订单 SO-20260828-0007（收货人信息待加载）');
});

test('整卡指向对应订单；没有关联订单时不造假链接', () => {
  assert.equal(presentStockBlockerCard(reviewCase(), FACTS).orderHref, '/orders/31');
  assert.equal(presentStockBlockerCard(reviewCase({ orderId: null }), null).orderHref, null);
});

// —— 文案不得写死「缺货」：14 种门禁失败塌缩在同一外层码下，只有一种是真缺货 ——

test('真缺货才说「缺货」', () => {
  const view = presentStockBlockerCard(
    reviewCase({ blockers: [blocker({ code: 'JD_STOCK_INSUFFICIENT' })] }),
    FACTS,
  );
  assert.match(view.title, /缺货$/);
});

test('映射门禁阻断说的是映射问题，绝不说成「缺货」（生产实测：映射配着却报缺货）', () => {
  const view = presentStockBlockerCard(
    reviewCase({ blockers: [blocker({ mappingIssueCode: 'MAPPING_MISSING' })] }),
    FACTS,
  );
  assert.match(view.title, /未配置京东商品映射$/);
  assert.doesNotMatch(view.title, /缺货/);
});

test('拿不到细分 issue 时用中性措辞，不臆造「缺货」', () => {
  const view = presentStockBlockerCard(reviewCase(), FACTS);
  assert.match(view.title, /京东商品校验未通过$/);
  assert.doesNotMatch(view.title, /缺货/);
});

test('每一种映射 issue 都给出与「缺货」不同的具体说法', () => {
  const issues = [
    'INTERNAL_SKU_MISSING', 'INTERNAL_SKU_INACTIVE', 'MAPPING_MISSING', 'MAPPING_INACTIVE',
    'GOODS_NO_MISSING', 'UNIT_CONVERSION_MISSING', 'UNIT_CONVERSION_INVALID', 'NON_INTEGRAL_QUANTITY',
    'JD_GOODS_QUERY_FAILED', 'JD_GOODS_NOT_FOUND', 'GOODS_NO_CONFLICT', 'ERP_GOODS_NO_CONFLICT',
    'GOODS_STATUS_MISSING', 'GOODS_DISABLED',
  ];
  const seen = new Set<string>();
  for (const issue of issues) {
    const view = presentStockBlockerCard(
      reviewCase({ blockers: [blocker({ mappingIssueCode: issue })] }), FACTS,
    );
    assert.doesNotMatch(view.title, /缺货/, `${issue} 不得被说成缺货`);
    assert.doesNotMatch(view.title, new RegExp(issue), `${issue} 缺中文翻译，裸码上屏`);
    seen.add(view.title);
  }
  assert.equal(seen.size, issues.length, '14 种 issue 的文案必须互不相同，否则等于没细分');
});

test('未登记的 issue 码回退原码而非掩盖性兜底文案', () => {
  const view = presentStockBlockerCard(
    reviewCase({ blockers: [blocker({ mappingIssueCode: 'BRAND_NEW_ISSUE' })] }), FACTS,
  );
  assert.match(view.title, /BRAND_NEW_ISSUE$/);
});

test('一条事项多个阻断时缀「等 N 项」，不把商品名拼成长文本', () => {
  const view = presentStockBlockerCard(
    reviewCase({ blockers: [blocker(), blocker({ skuId: '49', orderLineIds: ['6'] })] }),
    FACTS,
  );
  assert.match(view.title, /等 2 项$/);
  assert.equal(view.blockerCount, 2);
});

test('platformProductName：订单行换不到名字时退回内部名，再退京东编码', () => {
  assert.equal(platformProductName(blocker(), FACTS), '【京东配送】子牧牛肉惠选礼包1400g');
  assert.equal(platformProductName(blocker({ orderLineIds: ['999'] }), FACTS), '牛肉饼(1.2kg)');
  assert.equal(platformProductName(blocker({ productName: null }), null), 'EMG4418918549603');
  assert.equal(platformProductName(blocker({ productName: null, goodsNo: null }), null), null);
});

test('orderIdsToLoad：去重且丢弃无订单的事项', () => {
  assert.deepEqual(
    orderIdsToLoad([reviewCase(), reviewCase({ caseId: '43' }), reviewCase({ caseId: '44', orderId: '32' }), reviewCase({ caseId: '45', orderId: null })]),
    ['31', '32'],
  );
});
