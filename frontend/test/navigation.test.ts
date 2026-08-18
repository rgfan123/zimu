import assert from 'node:assert/strict';
import test from 'node:test';
import { navigationContext } from '../src/navigation.ts';

test('ERP navigation groups task pages under a stable business section', () => {
  assert.deepEqual(navigationContext('/workbench/reviews', '人工复核'), {
    section: '作业中心',
    page: '人工复核',
  });
  assert.deepEqual(navigationContext('/procurement/tickets', '采购协同'), {
    section: '作业中心',
    page: '采购协同',
  });
  assert.deepEqual(navigationContext('/product/sku-mappings', 'SKU 映射'), {
    section: '主数据',
    page: 'SKU 映射',
  });
});
