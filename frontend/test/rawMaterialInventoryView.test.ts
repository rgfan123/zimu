import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError } from '../src/api/client.ts';
import {
  RAW_MATERIAL_CHECKING_HINT,
  RAW_MATERIAL_LOADING_HINT,
  RAW_MATERIAL_NOT_A_ZERO_NOTICE,
  RAW_MATERIAL_SCOPE_NOTE,
  rawMaterialEmptyStockText,
  rawMaterialInventoryNotice,
  rawMaterialInventoryState,
  rawMaterialReadFailureNotice,
  rawMaterialReadFailureReason,
  rawMaterialReadFailureReasonFromBusinessCode,
  rawMaterialStockStatusPresentation,
  type RawMaterialInventoryState,
  type RawMaterialNotice,
  type RawMaterialReadFailureReason,
} from '../src/pages/inventory/rawMaterialInventoryView.ts';

/**
 * 票 06 定下措辞，票 09 接上真数据：页面在拿不到数时说什么、拿到「零」时说什么。
 *
 * 措辞里唯一会伤人的错误是把「读不到原料」说成「没有原料」：运营据此下采购决定，代价是
 * 真金白银。票 09 之后又多了一个对称的错误——把「读到了没有」（在库物料为 0 的合法事实）
 * 说成故障。下面的断言把这两件事同时钉死。
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
  // 票 09：模块开放即有取数路径，票 06 的「已接通但没接上取数」中间态不复存在。
  assert.deepEqual(rawMaterialInventoryState('open'), { kind: 'ready' });

  assert.equal(
    rawMaterialInventoryNotice({ kind: 'checking' }),
    null,
    '还没读到清单时不能断言「未接通」——那是一句此刻拿不出证据的话',
  );
  assert.equal(
    rawMaterialInventoryNotice({ kind: 'ready' }),
    null,
    'ready 不出提示：接下来该说话的是取数结果（数据 / 空清单 / 失败），不是状态机',
  );
  assert.match(RAW_MATERIAL_CHECKING_HINT, /正在确认/);
  assert.match(RAW_MATERIAL_LOADING_HINT, /正在读取/);
});

test('码表（票 09 接线点）：四个稳定 business_code 各归各位，认不出的码一律 UNKNOWN', () => {
  assert.equal(rawMaterialReadFailureReasonFromBusinessCode('RAW_MATERIAL_NOT_CONFIGURED'), 'NOT_CONFIGURED');
  assert.equal(rawMaterialReadFailureReasonFromBusinessCode('RAW_MATERIAL_UNAVAILABLE'), 'UNAVAILABLE');
  assert.equal(rawMaterialReadFailureReasonFromBusinessCode('RAW_MATERIAL_UNAUTHORIZED'), 'UNAUTHORIZED');
  assert.equal(rawMaterialReadFailureReasonFromBusinessCode('RAW_MATERIAL_CONTRACT_DRIFT'), 'CONTRACT_DRIFT');

  for (const code of ['RAW_MATERIAL_SOMETHING_NEW', 'VERSION_CONFLICT', '', null, undefined]) {
    assert.equal(
      rawMaterialReadFailureReasonFromBusinessCode(code),
      'UNKNOWN',
      `认不出的码必须归 UNKNOWN（仍是「读不到」），不得猜一个更具体的原因: ${JSON.stringify(code)}`,
    );
  }
});

test('取数异常 → 原因：ApiError 走码表，网络级失败与其他异常不冒充「上游不可用」', () => {
  const apiFailure = (code?: string) =>
    new ApiError(503, { message: '', http_status: 503, business_code: code });

  assert.equal(rawMaterialReadFailureReason(apiFailure('RAW_MATERIAL_UNAVAILABLE')), 'UNAVAILABLE');
  assert.equal(rawMaterialReadFailureReason(apiFailure('RAW_MATERIAL_UNAUTHORIZED')), 'UNAUTHORIZED');
  assert.equal(rawMaterialReadFailureReason(apiFailure(undefined)), 'UNKNOWN', '没有码的错误体不猜原因');
  assert.equal(
    rawMaterialReadFailureReason(new Error('fetch failed')),
    'UNKNOWN',
    '请求根本没到网关时并没有「上游不可用」的证据，宁可 UNKNOWN 也不编一个更具体的原因',
  );
});

test('每一种拿不到数的情形都写明「读不到」不是「没有」', () => {
  const states: RawMaterialInventoryState[] = ALL_REASONS.map(
    (reason): RawMaterialInventoryState => ({ kind: 'unavailable', reason }),
  );

  for (const state of states) {
    const notice = noticeFor(state);
    assert.ok(
      notice.description.includes(RAW_MATERIAL_NOT_A_ZERO_NOTICE),
      `${JSON.stringify(state)} 漏掉了「读不到 ≠ 没有」的收尾句，这一处就会被读成「原料没了」`,
    );
  }

  // 取数失败（模块开放后）与模块未开放共用同一张措辞表：同一原因必须说同一句话。
  for (const reason of ALL_REASONS) {
    assert.deepEqual(rawMaterialReadFailureNotice(reason), unavailableNotice(reason));
  }

  assert.match(RAW_MATERIAL_NOT_A_ZERO_NOTICE, /读不到原料/);
  assert.match(RAW_MATERIAL_NOT_A_ZERO_NOTICE, /不是「没有原料」/);
  assert.match(RAW_MATERIAL_NOT_A_ZERO_NOTICE, /不要按零库存理解/);
});

test('提示里不出现任何数字，也不出现空态词——0 和「暂无数据」正是要避免的误读', () => {
  const texts = [
    RAW_MATERIAL_CHECKING_HINT,
    RAW_MATERIAL_LOADING_HINT,
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

test('读取成功且清单为空：「读到了没有」的措辞与所有失败措辞可区分', () => {
  const emptyText = rawMaterialEmptyStockText();
  const emptyWithKeyword = rawMaterialEmptyStockText('  黑猪  ');

  assert.match(emptyText, /当前无在库物料/);
  assert.match(emptyText, /读取已成功/, '空清单必须先说明读取成功——这正是它与失败的分界');
  assert.match(emptyWithKeyword, /没有匹配「黑猪」/, '带关键词的空结果只说无匹配，不升级成「无在库物料」');
  assert.match(emptyWithKeyword, /读取已成功/);

  for (const text of [emptyText, emptyWithKeyword]) {
    assert.equal(text.includes('读不到'), false, `成功态措辞不得出现「读不到」：${text}`);
    assert.equal(text.includes(RAW_MATERIAL_NOT_A_ZERO_NOTICE), false, '成功态不携带失败收尾句');
    assert.equal(text.includes('暂无数据'), false, '成功空态也不用含混的「暂无数据」');
  }
  for (const reason of ALL_REASONS) {
    const notice = unavailableNotice(reason);
    assert.equal(
      `${notice.title}${notice.description}`.includes('无在库物料'),
      false,
      `失败措辞不得出现「无在库物料」（那是成功空态专用的话）：${reason}`,
    );
  }
});

test('结存状态呈现：near_expiry / low 用警示色，frozen 升级为错误色，认不出的值原文中性展示', () => {
  assert.deepEqual(rawMaterialStockStatusPresentation('normal'), { label: '正常', tone: 'success' });
  assert.deepEqual(rawMaterialStockStatusPresentation('low'), { label: '低库存', tone: 'warning' });
  assert.deepEqual(rawMaterialStockStatusPresentation('near_expiry'), { label: '临期', tone: 'warning' });
  assert.deepEqual(rawMaterialStockStatusPresentation('frozen'), { label: '冻结', tone: 'error' });
  assert.deepEqual(
    rawMaterialStockStatusPresentation('quarantine'),
    { label: 'quarantine', tone: 'neutral' },
    '上游新增档位原文透出，不翻译成任何更乐观或更悲观的含义',
  );
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
