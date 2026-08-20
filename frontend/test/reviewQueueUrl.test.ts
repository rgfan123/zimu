import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  attentionCardUrl,
  reviewsQueueUrl,
} from '../src/pages/shared/reviewQueueUrl.ts';

/**
 * Issue #96 URL 约定（与 batchUrl.test.ts 的 #95 约定同一测试层级）：
 * - 工作台 → 复核队列的跳转筛选全部落在 query string，可分享、刷新/回退可恢复；
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

test('reviewsQueueUrl view param: reviews is the default and omitted, alerts is explicit', () => {
  assert.equal(reviewsQueueUrl({ view: 'reviews' }), '/workbench/reviews');
  assert.equal(reviewsQueueUrl({ view: 'alerts' }), '/workbench/reviews?view=alerts');
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

test('attention card routing: alert-only reasons go to the alerts queue', () => {
  assert.equal(attentionCardUrl('PROCUREMENT_REQUIRED'), '/workbench/reviews?view=alerts');
  assert.equal(attentionCardUrl('JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED'), '/workbench/reviews?view=alerts');
  assert.equal(attentionCardUrl('JD_SKU_MAPPING'), '/workbench/reviews?view=alerts');
});

test('attention card routing: unknown reasons are treated as review cases (the KPI only counts review cases)', () => {
  assert.equal(
    attentionCardUrl('SOME_FUTURE_CODE'),
    '/workbench/reviews?status=OPEN&reason_code=SOME_FUTURE_CODE',
  );
});
