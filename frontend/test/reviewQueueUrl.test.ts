import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  ALERTS_STATUS_PARAM,
  REVIEWS_VIEW_PARAM,
  alertsQueueUrl,
  alertsRouteFromLegacyView,
  attentionCardUrl,
  reviewsQueueUrl,
} from '../src/pages/shared/reviewQueueUrl.ts';

/**
 * Issue #96 URL 约定（与 batchUrl.test.ts 的 #95 约定同一测试层级）+ Issue #64 拆分：
 * - 工作台 → 复核队列的跳转筛选全部落在 query string，可分享、刷新/回退可恢复；
 * - 运营提醒是独立路由 /workbench/alerts（Issue #64），其状态筛选也是 URL 事实源；
 * - 旧 view=alerts 分享链接（#96 时代）必须重定向到新提醒路由且不丢其他参数；
 * - 真实时间口径：DashboardController summary SQL 中「待人工介入」KPI 与 attention
 *   聚合均不带时间边界（全部 OPEN），因此链接不伪造任何 date/business_date 参数。
 */

test('reviewsQueueUrl only carries explicitly set filters', () => {
  assert.equal(reviewsQueueUrl(), '/workbench/reviews');
  assert.equal(reviewsQueueUrl({}), '/workbench/reviews');
  assert.equal(reviewsQueueUrl({ status: 'OPEN' }), '/workbench/reviews?status=OPEN');
  assert.equal(
    reviewsQueueUrl({ status: 'OPEN', reasonCode: 'SKU_MAPPING_REQUIRED' }),
    '/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED',
  );
  assert.equal(
    reviewsQueueUrl({ status: 'OPEN', reasonCode: 'SKU_MAPPING_REQUIRED', team: 'SKU_OPS' }),
    '/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS',
  );
});

test('reviewsQueueUrl keeps the batch context alongside new filters (#95 compatible)', () => {
  assert.equal(
    reviewsQueueUrl({ status: 'OPEN', batchId: '7' }),
    `/workbench/reviews?status=OPEN&import_batch=7`,
  );
  assert.equal(
    reviewsQueueUrl({ batchId: '7', reasonCode: 'CUSTOMER_MATCH_REQUIRED', team: 'CUSTOMER_OPS' }),
    `/workbench/reviews?reason_code=CUSTOMER_MATCH_REQUIRED&responsible_team=CUSTOMER_OPS&import_batch=7`,
  );
});

test('reviewsQueueUrl view param: reviews is the default and omitted, alerts is explicit (legacy #96 shape kept)', () => {
  assert.equal(reviewsQueueUrl({ view: 'reviews' }), '/workbench/reviews');
  // #96 时代的 view=alerts 形态保持可构造：路由层会把这类链接重定向到新提醒页。
  assert.equal(reviewsQueueUrl({ view: 'alerts' }), '/workbench/reviews?view=alerts');
});

test('alertsQueueUrl is the new standalone alerts route and only carries an explicit status filter', () => {
  assert.equal(alertsQueueUrl(), '/workbench/alerts');
  assert.equal(alertsQueueUrl({}), '/workbench/alerts');
  assert.equal(
    alertsQueueUrl({ status: 'ACKNOWLEDGED' }),
    `/workbench/alerts?${ALERTS_STATUS_PARAM}=ACKNOWLEDGED`,
  );
});

test('legacy view=alerts links are rewritten to the alerts route, dropping only the view param', () => {
  assert.equal(
    alertsRouteFromLegacyView(new URLSearchParams(`${REVIEWS_VIEW_PARAM}=alerts`)),
    '/workbench/alerts',
  );
  assert.equal(
    alertsRouteFromLegacyView(new URLSearchParams(`${REVIEWS_VIEW_PARAM}=alerts&import_batch=7&status=OPEN`)),
    '/workbench/alerts?import_batch=7&status=OPEN',
  );
  // 非 alerts 视图原样留在复核路由（不重写、不丢参数）。
  assert.equal(
    alertsRouteFromLegacyView(new URLSearchParams(`${REVIEWS_VIEW_PARAM}=reviews&status=OPEN`)),
    null,
  );
});

test('links never carry fabricated time params: the pending scope is all OPEN, no date bound', () => {
  for (const url of [
    reviewsQueueUrl({ status: 'OPEN' }),
    reviewsQueueUrl({ status: 'OPEN', reasonCode: 'SYNC_FAILED', team: 'ORDER_OPS' }),
    attentionCardUrl('SKU_MAPPING_REQUIRED'),
    attentionCardUrl('PROCUREMENT_REQUIRED'),
  ]) {
    assert.doesNotMatch(url, /business_date|date_from|date_to|date=/, `link must not carry time params: ${url}`);
  }
});

test('attention card routing: review-case reasons go to the reason-prefiltered queue', () => {
  assert.equal(
    attentionCardUrl('SKU_MAPPING_REQUIRED'),
    '/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED',
  );
  assert.equal(
    attentionCardUrl('CUSTOMER_MATCH_REQUIRED'),
    '/workbench/reviews?status=OPEN&reason_code=CUSTOMER_MATCH_REQUIRED',
  );
  assert.equal(
    attentionCardUrl('WECOM_ORDER_DRAFT'),
    '/workbench/reviews?status=OPEN&reason_code=WECOM_ORDER_DRAFT',
  );
});

test('attention card routing: alert-only reasons go to the alerts route', () => {
  assert.equal(attentionCardUrl('PROCUREMENT_REQUIRED'), '/workbench/alerts');
  assert.equal(attentionCardUrl('JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED'), '/workbench/alerts');
  assert.equal(attentionCardUrl('JD_SKU_MAPPING'), '/workbench/alerts');
});

test('attention card routing: unknown reasons are treated as review cases (the KPI only counts review cases)', () => {
  assert.equal(
    attentionCardUrl('SOME_FUTURE_CODE'),
    '/workbench/reviews?status=OPEN&reason_code=SOME_FUTURE_CODE',
  );
});
