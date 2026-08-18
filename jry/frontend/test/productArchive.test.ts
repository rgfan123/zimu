import assert from 'node:assert/strict';
import test from 'node:test';
import { displaySkuSpecification } from '../src/pages/product/productArchive.ts';

test('product archive never presents a JD goods number as a specification', () => {
  assert.equal(displaySkuSpecification('京东商品编号 EMG4418727174451'), '待维护');
  assert.equal(displaySkuSpecification('500g'), '500g');
  assert.equal(displaySkuSpecification(' 1kg '), '1kg');
});
