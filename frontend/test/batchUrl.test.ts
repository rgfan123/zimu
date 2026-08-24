import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  FILE_JOB_BATCH_PARAM,
  REVIEWS_BATCH_PARAM,
  fileJobUrlForBatch,
  parseBatchIdParam,
  reviewsUrlForBatch,
} from '../src/pages/shared/batchUrl.ts';

test('batch id URL param parses fail-closed: absent / valid / invalid', () => {
  assert.deepEqual(parseBatchIdParam(null), { kind: 'absent' });
  assert.deepEqual(parseBatchIdParam('7'), { kind: 'valid', id: '7' });
  assert.deepEqual(parseBatchIdParam('123456789012345678'), { kind: 'valid', id: '123456789012345678' });
});

test('malformed batch id is explicitly invalid, never silently treated as no filter', () => {
  for (const raw of ['', 'abc', '0', '007', '-1', '1.5', '7x', '1 2', '９']) {
    assert.deepEqual(parseBatchIdParam(raw), { kind: 'invalid', raw }, `raw=${JSON.stringify(raw)}`);
  }
});

test('batch urls use the established param names for sharing between pages', () => {
  assert.equal(REVIEWS_BATCH_PARAM, 'import_batch');
  assert.equal(FILE_JOB_BATCH_PARAM, 'import_batch');
  assert.equal(reviewsUrlForBatch('7'), '/workbench/reviews?import_batch=7');
  assert.equal(fileJobUrlForBatch('7'), '/fulfillment/sales-outbound?import_batch=7');
});
