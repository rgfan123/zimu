import assert from 'node:assert/strict';
import test from 'node:test';
import {
  factGroupRows,
  reviewFactGroups,
  SOURCE_NOT_PROVIDED,
} from '../src/presentation/publicReady.ts';

function allRows(detail: Record<string, unknown>, reasonCode: string) {
  return reviewFactGroups(reasonCode).flatMap((group) => factGroupRows(detail, group));
}

test('五个复核家族都有可复用的事实组定义，SKU 映射家族沿用既有结构', () => {
  for (const reasonCode of [
    'CUSTOMER_MATCH_REQUIRED',
    'CARRIER_MAPPING',
    'MAPPING_MULTIPLIER',
    'QUANTITY_SCALE',
    'IMPORT_DATA',
    'REVISION_AFTER_EXPORT',
  ]) {
    const groups = reviewFactGroups(reasonCode);
    assert.ok(groups.length > 0, `${reasonCode} 必须定义事实组`);
    for (const group of groups) {
      assert.ok(group.title, '事实组必须有标题');
      assert.ok(group.fields.length > 0, `${reasonCode}/${group.title} 必须有字段`);
      for (const field of group.fields) {
        assert.ok(field.key, '字段键不能为空');
        assert.ok(field.label, `${reasonCode}/${group.title}/${field.key} 必须有标签`);
      }
    }
  }
  assert.equal(reviewFactGroups('SYNC_FAILED').length, 0, '未定义事实组的家族不渲染事实组');
});

test('事实组行渲染：白名单字段缺失/空白显示「来源未提供」，不整行消失', () => {
  const groups = reviewFactGroups('QUANTITY_SCALE');
  const rows = factGroupRows({
    source_quantity: '1.500',
    source_unit: '盒',
    quantity_multiplier: '2.000',
    converted_quantity: '3.000',
    reject_reason: '换算后不是整数',
    provider_code: 'JD',
  }, groups[0]);
  assert.deepEqual(rows, [
    { label: '来源数量原文', value: '1.500' },
    { label: '来源单位', value: '盒' },
    { label: '当前乘数', value: '2.000' },
    { label: '换算后结果', value: '3.000' },
    { label: '拒绝原因', value: '换算后不是整数' },
    { label: '履约方', value: 'JD' },
  ]);
  const missing = factGroupRows({}, groups[0]);
  assert.ok(missing.length === groups[0].fields.length, '缺字段时行数不变');
  assert.ok(missing.every((row) => row.value === SOURCE_NOT_PROVIDED), '缺字段显示占位');
});

test('CUSTOMER_MATCH_REQUIRED：来源客户/收货可展示部分/候选客户档案三组，PII 键 fail-closed', () => {
  const rows = allRows({
    customer_name: '彩食鲜北京一分店',
    source_customer_ref: 'CSX-CUST-0001',
    receiver_name: '张三',
    receiver_address: '上海市浦东新区测试路 1 号',
    receiver_phone: '13800000000',
    raw_payload: { sql: 'select *' },
    internal_token: 'do-not-render',
    customer_candidates: [
      { customer_code: 'CUST-WECOM-0001', customer_name: '子牧测试客户', profile: { phone: '13900000000' } },
    ],
  }, 'CUSTOMER_MATCH_REQUIRED');
  const labels = rows.map((row) => row.label);
  assert.ok(labels.includes('来源客户名称原文'));
  assert.ok(labels.includes('来源客户编号'));
  assert.ok(labels.includes('收货人'));
  assert.ok(labels.includes('收货地址'));
  assert.ok(!labels.includes('收货电话'));
  assert.doesNotMatch(JSON.stringify(rows), /13800000000|13900000000|do-not-render|raw_payload|sql/);
});

test('CUSTOMER_MATCH_REQUIRED：候选客户档案投影为「编号 · 名称」，零命中显示「未命中候选」', () => {
  const groups = reviewFactGroups('CUSTOMER_MATCH_REQUIRED');
  const candidateGroup = groups.find((group) => group.title.includes('候选'));
  assert.ok(candidateGroup);
  const [hit] = factGroupRows({
    customer_candidates: [
      { customer_code: 'CUST-WECOM-0001', customer_name: '子牧测试客户' },
      { customer_code: 'CUST-FX-0002', customer_name: '飞象客户' },
    ],
  }, candidateGroup);
  assert.match(hit.value, /CUST-WECOM-0001 · 子牧测试客户/);
  assert.match(hit.value, /CUST-FX-0002 · 飞象客户/);
  const [zeroHit] = factGroupRows({ customer_candidates: [] }, candidateGroup);
  assert.equal(zeroHit.value, '未命中候选');
  const [absent] = factGroupRows({}, candidateGroup);
  assert.equal(absent.value, SOURCE_NOT_PROVIDED);
});

test('CARRIER_MAPPING：运单号/前缀/来源物流公司/候选承运商，未知键不渲染', () => {
  const rows = allRows({
    tracking_number: 'SF1390123456789',
    tracking_prefix: 'SF',
    source_logistics_company: '顺丰速运',
    carrier_candidates: [{ carrier_code: 'SF', carrier_name: '顺丰速运' }],
    receiver_phone: '13800000000',
    carrier_candidates_raw: [{ sql: 'select *' }],
  }, 'CARRIER_MAPPING');
  const labels = rows.map((row) => row.label);
  assert.ok(labels.includes('运单号原文'));
  assert.ok(labels.includes('识别前缀'));
  assert.ok(labels.includes('来源物流公司'));
  assert.ok(labels.includes('候选标准承运商'));
  assert.doesNotMatch(JSON.stringify(rows), /13800000000|carrier_candidates_raw|select/);
});

test('CARRIER_MAPPING：候选承运商投影，未命中显示「未命中候选」', () => {
  const groups = reviewFactGroups('CARRIER_MAPPING');
  const candidateGroup = groups.find((group) => group.title.includes('候选'));
  assert.ok(candidateGroup);
  const [hit] = factGroupRows({
    carrier_candidates: [{ carrier_code: 'SF', carrier_name: '顺丰速运' }],
  }, candidateGroup);
  assert.match(hit.value, /SF/);
  assert.match(hit.value, /顺丰速运/);
  const [zeroHit] = factGroupRows({ carrier_candidates: [] }, candidateGroup);
  assert.equal(zeroHit.value, '未命中候选');
});

test('IMPORT_DATA：问题单元格/列名/sheet/行号/拒绝原因，cell 值受长度上限约束', () => {
  const rows = allRows({
    source_sheet_name: 'Sheet1',
    source_row_index: 7,
    column_name: '下单数量',
    cell_value: '1.5.3',
    reject_reason: '数量最多三位小数',
    receiver_phone: '13800000000',
  }, 'IMPORT_DATA');
  const labels = rows.map((row) => row.label);
  assert.ok(labels.includes('来源工作表'));
  assert.ok(labels.includes('来源行号'));
  assert.ok(labels.includes('列名'));
  assert.ok(labels.includes('原始单元格值'));
  assert.ok(labels.includes('拒绝原因'));
  assert.doesNotMatch(JSON.stringify(rows), /13800000000/);

  const groups = reviewFactGroups('IMPORT_DATA');
  const [capped] = factGroupRows({
    cell_value: 'x'.repeat(1000),
  }, groups[0]);
  assert.ok(capped.value.length <= 200, '原始单元格值必须截断到固定上限');
});

test('MAPPING_MULTIPLIER 与 QUANTITY_SCALE 共用数量换算事实组', () => {
  const multiplierGroups = reviewFactGroups('MAPPING_MULTIPLIER');
  const scaleGroups = reviewFactGroups('QUANTITY_SCALE');
  assert.deepEqual(
    multiplierGroups.map((group) => group.fields.map((field) => field.key)),
    scaleGroups.map((group) => group.fields.map((field) => field.key)),
    '两个数量家族必须展示同一组事实',
  );
});

test('REVISION_AFTER_EXPORT：改动明细投影改前/改后与行号，未知字段键被过滤', () => {
  const rows = allRows({
    changes: [
      { field: 'quantity', line_no: 1, before: '2.000', after: '3.000' },
      { field: 'receiver_name', line_no: null, before: '张三', after: '李四' },
      { field: 'receiver_phone', line_no: null, before: '13800000000', after: '13900000000' },
      { field: 'internal_secret', line_no: null, before: 'x', after: 'y' },
      { field: 'quantity', line_no: 2, before: null, after: '1.000' },
    ],
    export_batch_no: 'EXP-20260820-001',
    template_version: 'v1-24-columns',
    source_version: 'revision-2',
    change_reason: '客户修改数量',
    receiver_phone: '13800000000',
  }, 'REVISION_AFTER_EXPORT');
  const text = JSON.stringify(rows);
  assert.match(text, /第 1 行/);
  assert.match(text, /改前 2\.000 → 改后 3\.000/);
  assert.match(text, /收货人/);
  assert.match(text, /改前 张三 → 改后 李四/);
  assert.match(text, /改后 1\.000/);
  assert.match(text, /EXP-20260820-001/);
  assert.match(text, /v1-24-columns/);
  assert.match(text, /revision-2/);
  assert.match(text, /客户修改数量/);
  // PII 与未知字段键不出现：receiver_phone 改动项、internal_secret 改动项、detail 里的 phone 键
  assert.doesNotMatch(text, /13800000000|13900000000|internal_secret|receiver_phone/);
});

test('REVISION_AFTER_EXPORT：改动为空时显示「无字段变更」，缺 key 时显示占位', () => {
  const groups = reviewFactGroups('REVISION_AFTER_EXPORT');
  const changeGroup = groups.find((group) => group.title.includes('改动'));
  assert.ok(changeGroup);
  const [empty] = factGroupRows({ changes: [] }, changeGroup);
  assert.equal(empty.value, '无字段变更');
  const [absent] = factGroupRows({}, changeGroup);
  assert.equal(absent.value, SOURCE_NOT_PROVIDED);
});
