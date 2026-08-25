/**
 * issue #40「京东只读查询页骨架收敛」——JdQueryPage 纯逻辑核心测试（node:test）。
 *
 * 覆盖骨架的关键纯函数：参数构建（buildJdParams）、白名单收集（collectWhitelisted，
 * 四种原页口径：基础信息/库存与单据/序列号/退货详情）、权限未开通判定。
 * 组件 JSX 渲染层由各页面直接复用，本文件只测可单测的纯逻辑。
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildJdParams,
  collectWhitelisted,
  isJdBusinessCodeDenied,
  isJdPermissionDenied,
  normalizeKey,
  scalarString,
} from '../src/pages/shared/jdQueryCore.ts';
import type { JdQueryField } from '../src/pages/shared/jdQueryCore.ts';

test('buildJdParams 按字段 kind 归一化并过滤空值', () => {
  const fields: JdQueryField[] = [
    { name: 'goods_no', label: '商品编码', kind: 'list' },
    { name: 'page_size', label: '每页条数', kind: 'number' },
    { name: 'cursor', label: '游标' },
    { name: 'return_detail_flag', label: '返回明细', kind: 'flag' },
  ];

  // list 按英文/中文逗号拆分；number 取整转字符串；flag 不参与提交。
  assert.deepEqual(
    buildJdParams(fields, {
      goods_no: 'G1, G2，G3',
      page_size: 20.7,
      cursor: 'abc',
      return_detail_flag: 1,
    }),
    { goods_no: ['G1', 'G2', 'G3'], page_size: '20', cursor: 'abc' },
  );

  // 空值（undefined / null / 空白字符串）一律跳过。
  assert.deepEqual(buildJdParams(fields, { cursor: '  ' }), {});
  assert.deepEqual(buildJdParams(fields, { cursor: undefined, goods_no: '' }), {});
  assert.deepEqual(buildJdParams(fields, { goods_no: null, page_size: Number.NaN }), {});

  // 未配置的字段名不参与。
  assert.deepEqual(buildJdParams(fields, { private_debug: 'never-forward' }), {});
});

test('collectWhitelisted 只透出白名单字段（基础信息口径：标量数组不合并、未收录对象仍递归）', () => {
  const whitelist = { customerno: '客户编码', customername: '客户名称', shopnos: '店铺编码列表' };
  const rows: { label: string; value: string }[] = [];
  collectWhitelisted(
    {
      customerno: 'C1',
      customername: ' 张三 ',
      shopnos: ['S1', 'S2'],
      contact: { phone: '13800000000' },
      note: '内部备注',
    },
    whitelist,
    rows,
  );
  assert.deepEqual(rows, [
    { label: '客户编码', value: 'C1' },
    { label: '客户名称', value: '张三' },
  ]);
});

test('collectWhitelisted 标量数组合并为一行（库存/单据口径：arrayJoin）', () => {
  const whitelist = { shopnos: '店铺编码列表', warehousenos: '仓库编码列表' };
  const rows: { label: string; value: string }[] = [];
  collectWhitelisted(
    { shopnos: ['S1', 'S2'], warehousenos: [], nested: { shopnos: ['S3'] } },
    whitelist,
    rows,
    { arrayJoin: '、' },
  );
  assert.deepEqual(rows, [
    { label: '店铺编码列表', value: 'S1、S2' },
    { label: '店铺编码列表', value: 'S3' },
  ]);
});

test('collectWhitelisted 按 label|value 去重（序列号口径：dedupe + arrayJoin ", "）', () => {
  const whitelist = { goodsno: '商品编码', serialnos: '序列号列表' };
  const rows: { label: string; value: string }[] = [];
  collectWhitelisted(
    { items: [{ goodsno: 'G1' }, { goodsno: 'G1' }, { serialnos: ['A', 'B'] }] },
    whitelist,
    rows,
    { maxRows: 24, dedupe: true, arrayJoin: ', ' },
  );
  assert.deepEqual(rows, [
    { label: '商品编码', value: 'G1' },
    { label: '序列号列表', value: 'A, B' },
  ]);
});

test('collectWhitelisted 前缀嵌套 + 跳过未收录 + null 展示（退货详情口径）', () => {
  const whitelist = {
    erpreturntowarehouseno: '系统退货入库单号',
    status: '状态',
    goodslist: '商品明细',
    goodsno: '商品编码',
    planquantity: '计划数量',
  };
  const rows: { label: string; value: string }[] = [];
  collectWhitelisted(
    {
      erpreturntowarehouseno: 'ZM-RTW-001',
      status: null,
      goodslist: [{ goodsno: 'G1', planquantity: 2 }, { goodsno: 'G2' }],
      phone: '13800000000',
    },
    whitelist,
    rows,
    { maxRows: 100000, prefixNested: true, skipUnlisted: true, indexArrays: true, includeNull: true },
  );
  assert.deepEqual(rows, [
    { label: '系统退货入库单号', value: 'ZM-RTW-001' },
    { label: '状态', value: 'null' },
    { label: '商品明细 · 1商品编码', value: 'G1' },
    { label: '商品明细 · 1计划数量', value: '2' },
    { label: '商品明细 · 2商品编码', value: 'G2' },
  ]);
});

test('collectWhitelisted 行数上限（默认 40）', () => {
  const whitelist: Record<string, string> = {};
  const data: Record<string, unknown> = {};
  for (let i = 0; i < 100; i += 1) {
    whitelist[`field${i}`] = `字段${i}`;
    data[`field_${i}`] = `v${i}`;
  }
  const rows: { label: string; value: string }[] = [];
  collectWhitelisted(data, whitelist, rows);
  assert.equal(rows.length, 40);
});

test('isJdPermissionDenied：业务码 2001 或消息含权限/authorization 字样', () => {
  assert.equal(isJdPermissionDenied({ success: false, business_code: '2001' }), true);
  assert.equal(isJdPermissionDenied({ success: false, business_code: 'X', message: '接口权限未开通' }), true);
  assert.equal(isJdPermissionDenied({ success: false, business_code: 'X', message: 'no permission' }), true);
  assert.equal(isJdPermissionDenied({ success: false, business_code: 'X', message: '业务繁忙，请稍后重试' }), false);
});

test('isJdBusinessCodeDenied：仅严格匹配业务码 2001', () => {
  assert.equal(isJdBusinessCodeDenied({ success: false, business_code: '2001' }), true);
  assert.equal(isJdBusinessCodeDenied({ success: false, business_code: 'X', message: '接口权限未开通' }), false);
  assert.equal(isJdBusinessCodeDenied({ success: true, business_code: 'MOCK_SUCCESS' }), false);
});

test('normalizeKey 去符号 + 小写', () => {
  assert.equal(normalizeKey('erp_return_to_warehouse_no'), 'erpreturntowarehouseno');
  assert.equal(normalizeKey('Order.No'), 'orderno');
  assert.equal(normalizeKey('firstCategoryCode'), 'firstcategorycode');
});

test('scalarString 字符串裁剪 / 数字布尔 / 数组合并 / 对象与空值', () => {
  assert.equal(scalarString('  x ', null), 'x');
  assert.equal(scalarString('', null), null);
  assert.equal(scalarString(12, null), '12');
  assert.equal(scalarString(true, null), 'true');
  assert.equal(scalarString(['a', 'b'], '、'), 'a、b');
  assert.equal(scalarString([], '、'), null);
  assert.equal(scalarString(['a', 2], ', '), 'a, 2');
  assert.equal(scalarString({ a: 1 }, null), null);
  assert.equal(scalarString(null, null), null);
  assert.equal(scalarString(undefined, '、'), null);
});
