import assert from 'node:assert/strict';
import test from 'node:test';
import {
  jdReceiverAddressBatchIdempotencyKey,
  jdReceiverAddressBatchItems,
  jdReceiverAddressCandidateText,
  jdReceiverAddressConfirmedText,
  jdReceiverAddressDefaults,
  jdReceiverAddressStatus,
  jdReceiverAddressStatusLabel,
} from '../src/pages/fulfillment/jdReceiverAddress.ts';
import type { JdReceiverAddressCandidate } from '../src/api/types.ts';

function row(overrides: Partial<JdReceiverAddressCandidate> = {}): JdReceiverAddressCandidate {
  return {
    shipment_id: '71',
    expected_version: 0,
    receiver_address_snapshot: '自由文本地址',
    source_channel: 'CAISHIXIAN',
    confirmed: false,
    candidate: {
      province: '上海市',
      city: '上海市',
      county: '浦东新区',
      town: null,
      detail_address: '测试路1号',
    },
    candidate_incomplete: false,
    ...overrides,
  };
}

test('候选与已确认状态严格区分：未确认行回填候选，已确认行回填已确认值', () => {
  const pending = row();
  assert.equal(jdReceiverAddressStatus(pending), 'pending');
  assert.equal(jdReceiverAddressStatusLabel('pending'), '待确认');
  assert.deepEqual(jdReceiverAddressDefaults(pending), {
    province: '上海市',
    city: '上海市',
    county: '浦东新区',
    town: '',
    detail_address: '测试路1号',
  });

  const confirmed = row({
    confirmed: true,
    confirmed_by: 'ops-01',
    province: '浙江省',
    city: '杭州市',
    county: '西湖区',
    detail_address: '文三路2号',
  });
  assert.equal(jdReceiverAddressStatus(confirmed), 'confirmed');
  assert.equal(jdReceiverAddressStatusLabel('confirmed'), '已确认');
  assert.deepEqual(jdReceiverAddressDefaults(confirmed), {
    province: '浙江省',
    city: '杭州市',
    county: '西湖区',
    town: '',
    detail_address: '文三路2号',
  });
});

test('来源层级缺失时落到人工：候选置空、状态为需人工填写、不拼残缺地址', () => {
  const incomplete = row({ candidate: null, candidate_incomplete: true });
  assert.equal(jdReceiverAddressStatus(incomplete), 'incomplete');
  assert.equal(jdReceiverAddressStatusLabel('incomplete'), '需人工填写');
  assert.equal(jdReceiverAddressCandidateText(incomplete), null);
  assert.deepEqual(jdReceiverAddressDefaults(incomplete), {
    province: '',
    city: '',
    county: '',
    town: '',
    detail_address: '',
  });
});

test('批量组装：编辑值优先、乡镇可留空、必填缺失进入 skipped 不猜测', () => {
  const a = row({ shipment_id: '1', candidate: { province: '上海市', city: '上海市', county: '浦东新区', town: null, detail_address: '测试路1号' } });
  const b = row({ shipment_id: '2', candidate: { province: '浙江省', city: '杭州市', county: '西湖区', town: null, detail_address: '文三路2号' } });
  const c = row({ shipment_id: '3', candidate: null, candidate_incomplete: true });

  const built = jdReceiverAddressBatchItems([b, a, c], {
    '1': { province: ' 上海市 ', detail_address: ' 新改的地址 ' },
  });

  assert.deepEqual(built.items, [
    {
      shipment_id: '1',
      expected_version: 0,
      province: '上海市',
      city: '上海市',
      county: '浦东新区',
      town: undefined,
      detail_address: '新改的地址',
    },
    {
      shipment_id: '2',
      expected_version: 0,
      province: '浙江省',
      city: '杭州市',
      county: '西湖区',
      town: undefined,
      detail_address: '文三路2号',
    },
  ]);
  assert.deepEqual(built.skipped, [{ shipment_id: '3', reason: '缺少必填层级，请人工补齐' }]);
});

test('人工编辑补齐后不再 skipped', () => {
  const incomplete = row({ shipment_id: '9', candidate: null, candidate_incomplete: true });
  const built = jdReceiverAddressBatchItems([incomplete], {
    '9': { province: '上海市', city: '上海市', county: '浦东新区', detail_address: '测试路1号' },
  });
  assert.equal(built.skipped.length, 0);
  assert.deepEqual(built.items, [{
    shipment_id: '9',
    expected_version: 0,
    province: '上海市',
    city: '上海市',
    county: '浦东新区',
    town: undefined,
    detail_address: '测试路1号',
  }]);
});

test('已确认行的展示文本与空状态', () => {
  assert.equal(jdReceiverAddressConfirmedText(row()), null);
  assert.equal(
    jdReceiverAddressConfirmedText(row({
      confirmed: true,
      province: '浙江省',
      city: '杭州市',
      county: '西湖区',
      town: '转塘街道',
      detail_address: '文三路2号',
    })),
    '浙江省 杭州市 西湖区 转塘街道 文三路2号',
  );
  assert.equal(jdReceiverAddressCandidateText(row()), '上海市 上海市 浦东新区 测试路1号');
});

test('幂等键由确认内容决定：同内容同键，任一值变化即新键', () => {
  const a = row({ shipment_id: '1' });
  const b = row({ shipment_id: '2' });
  const first = jdReceiverAddressBatchItems([a, b], {});
  const same = jdReceiverAddressBatchItems([b, a], {});
  const edited = jdReceiverAddressBatchItems([a, b], { '1': { province: '浙江省' } });

  assert.equal(
    jdReceiverAddressBatchIdempotencyKey(first.items),
    jdReceiverAddressBatchIdempotencyKey(same.items),
  );
  assert.notEqual(
    jdReceiverAddressBatchIdempotencyKey(first.items),
    jdReceiverAddressBatchIdempotencyKey(edited.items),
  );
  assert.match(jdReceiverAddressBatchIdempotencyKey(first.items), /^shipment-jd-receiver-address-batch-[0-9a-f]+$/);
});
