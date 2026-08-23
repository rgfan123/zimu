import assert from 'node:assert/strict';
import test from 'node:test';
import { navigationContext } from '../src/navigation.ts';

test('ERP navigation groups task pages under a stable business section', () => {
  // Issue #104：复核收件箱随「我的工作台」板块移动（导航归属变更，URL 不变）。
  assert.deepEqual(navigationContext('/workbench/reviews', '复核收件箱'), {
    section: '我的工作台',
    page: '复核收件箱',
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
