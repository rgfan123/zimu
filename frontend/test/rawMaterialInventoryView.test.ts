import assert from 'node:assert/strict';
import test from 'node:test';
import {
  RAW_MATERIAL_CHECKING_HINT,
  RAW_MATERIAL_NOT_A_ZERO_NOTICE,
  RAW_MATERIAL_SCOPE_NOTE,
  rawMaterialInventoryNotice,
  rawMaterialInventoryState,
  type RawMaterialInventoryState,
  type RawMaterialNotice,
  type RawMaterialReadFailureReason,
} from '../src/pages/inventory/rawMaterialInventoryView.ts';

/**
 * 票 06：原料库存页在没有数据时说什么。
 *
 * 这条链路今天一个字节的真实数据都拿不到（上游只有 stdio MCP 面），所以页面的全部行为
 * 就是措辞——而措辞里唯一会伤人的错误是把「读不到原料」说成「没有原料」：运营据此下采购
 * 决定，代价是真金白银。下面的断言把这件事钉死。
 */

/** 票面要求可区分呈现的四类原因（另有 UNKNOWN 兜底，见最后一条）。 */
const REQUIRED_REASONS: readonly RawMaterialReadFailureReason[] = [
  'NOT_CONFIGURED',
  'UNAVAILABLE',
  'UNAUTHORIZED',
  'CONTRACT_DRIFT',
];

const ALL_REASONS: readonly RawMaterialReadFailureReason[] = [...REQUIRED_REASONS, 'UNKNOWN'];

/** 拿不到数的状态必须有话可说；没有提示就是白页，白页正是本票要消灭的东西。 */
function noticeFor(state: RawMaterialInventoryState): RawMaterialNotice {
  const notice = rawMaterialInventoryNotice(state);
  if (!notice) throw new Error(`missing notice: ${JSON.stringify(state)}`);
  return notice;
}

function unavailableNotice(reason: RawMaterialReadFailureReason): RawMaterialNotice {
  return noticeFor({ kind: 'unavailable', reason });
}

test('页面状态只由外壳读到的模块清单裁定，未落定时不下结论', () => {
  assert.deepEqual(rawMaterialInventoryState('pending'), { kind: 'checking' });
  assert.deepEqual(rawMaterialInventoryState('closed'), { kind: 'unavailable', reason: 'NOT_CONFIGURED' });
  assert.deepEqual(rawMaterialInventoryState('open'), { kind: 'connected-without-read-path' });

  assert.equal(
    rawMaterialInventoryNotice({ kind: 'checking' }),
    null,
    '还没读到清单时不能断言「未接通」——那是一句此刻拿不出证据的话',
  );
  assert.match(RAW_MATERIAL_CHECKING_HINT, /正在确认/);
});

test('每一种拿不到数的情形都写明「读不到」不是「没有」', () => {
  const states: RawMaterialInventoryState[] = [
    ...ALL_REASONS.map((reason): RawMaterialInventoryState => ({ kind: 'unavailable', reason })),
    { kind: 'connected-without-read-path' },
  ];

  for (const state of states) {
    const notice = noticeFor(state);
    assert.ok(
      notice.description.includes(RAW_MATERIAL_NOT_A_ZERO_NOTICE),
      `${JSON.stringify(state)} 漏掉了「读不到 ≠ 没有」的收尾句，这一处就会被读成「原料没了」`,
    );
  }

  assert.match(RAW_MATERIAL_NOT_A_ZERO_NOTICE, /读不到原料/);
  assert.match(RAW_MATERIAL_NOT_A_ZERO_NOTICE, /不是「没有原料」/);
  assert.match(RAW_MATERIAL_NOT_A_ZERO_NOTICE, /不要按零库存理解/);
});

test('提示里不出现任何数字，也不出现空态词——0 和「暂无数据」正是要避免的误读', () => {
  const texts = [
    RAW_MATERIAL_CHECKING_HINT,
    RAW_MATERIAL_SCOPE_NOTE,
    ...ALL_REASONS.flatMap((reason) => {
      const notice = unavailableNotice(reason);
      return [notice.title, notice.description];
    }),
  ];

  for (const text of texts) {
    assert.equal(/\d/.test(text), false, `文案里不得出现数字（会被当成结存读）：${text}`);
    assert.equal(text.includes('暂无数据'), false, `文案不得使用空态词：${text}`);
    assert.equal(text.includes('没有原料'), text.includes('不是「没有原料」'), `「没有原料」只能出现在否定句里：${text}`);
  }
});

test('四类失败原因各自可区分：标题与说明都不同，处置也不同', () => {
  const notices = REQUIRED_REASONS.map(unavailableNotice);

  assert.equal(new Set(notices.map(({ title }) => title)).size, REQUIRED_REASONS.length, '四类原因的标题必须互不相同');
  assert.equal(
    new Set(notices.map(({ description }) => description)).size,
    REQUIRED_REASONS.length,
    '四类原因的说明必须互不相同——分类的价值在于处置不同',
  );

  const [notConfigured, unavailable, unauthorized, contractDrift] = notices;
  assert.match(notConfigured.title, /未接通/);
  assert.match(notConfigured.description, /商品与主数据/, '未接通的说明要与菜单口径对上：接通后入口会回到该板块');
  assert.match(unavailable.description, /重试/, '不可用是唯一可以「稍后重试」的一类');
  assert.match(unauthorized.description, /令牌/, '鉴权失败要指向换令牌，而不是重试');
  assert.match(contractDrift.description, /不是猜着解析/, '契约漂移必须停下，不得猜着解析出一份可能错的结存');
});

test('未识别的失败仍然是「读不到」，不会退化成「没有」', () => {
  const notice = unavailableNotice('UNKNOWN');

  assert.equal(notice.tone, 'error');
  assert.match(notice.description, /不替它猜含义/);
});

test('页面范围如实写明只读，且不假装能回答「这单原料够不够」', () => {
  assert.match(RAW_MATERIAL_SCOPE_NOTE, /只读视图/);
  assert.match(RAW_MATERIAL_SCOPE_NOTE, /不做出入库写入/);
  assert.match(RAW_MATERIAL_SCOPE_NOTE, /这单原料够不够/);
  assert.match(
    RAW_MATERIAL_SCOPE_NOTE,
    /没有任何连接键/,
    'SKU 与原料之间没有连接键，占用与消耗类推断在映射建立前没有可计算的基础（spec D6）',
  );
});
