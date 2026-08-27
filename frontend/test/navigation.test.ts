import assert from 'node:assert/strict';
import test from 'node:test';
import { navigationContext } from '../src/navigation.ts';

test('ERP navigation groups task pages under a stable business section', () => {
  // Issue #104：复核收件箱随「我的工作台」板块移动（导航归属变更，URL 不变）。
  assert.deepEqual(navigationContext('/workbench/reviews', '复核收件箱'), {
    section: '我的工作台',
    page: '复核收件箱',
  });
  // UIUX-11：侧栏按工作流重组——采购协同的隐藏直达挂在「渠道与文件」。
  // 2026-08-27：低频配置拆为「商品与主数据」+「系统与接入」两组（均默认折叠）。
  assert.deepEqual(navigationContext('/procurement/tickets', '采购协同'), {
    section: '渠道与文件',
    page: '采购协同',
  });
  assert.deepEqual(navigationContext('/product/sku-mappings', 'SKU 映射'), {
    section: '商品与主数据',
    page: 'SKU 映射',
  });
  assert.deepEqual(navigationContext('/fulfillment/shipments', '发货记录'), {
    section: '订单与发货',
    page: '发货记录',
  });
});
