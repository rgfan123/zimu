import assert from 'node:assert/strict';
import test from 'node:test';
import {
  EVAL_STATUS_PRESENTATION,
  INPUT_DIGEST_EXPLANATION,
  agentListFiltersFromParams,
  agentListSearchParams,
  digestLabel,
  evalCaseGroups,
  filterAgentItems,
  formatCompactJson,
  formatJson,
  formatLatency,
  formatTime,
  modelVisibilityPresentation,
  rangeToStartedParams,
  runModePresentation,
  runOutcomePresentation,
  runStatusPresentation,
  runsFiltersFromParams,
  runsLocation,
  runsSearchParams,
  sevenDayPresentation,
  sortToolCalls,
  statePresentation,
  versionStatusPresentation,
} from '../src/pages/agents/agentPresentation.ts';
import type {
  AgentEvalCaseItem,
  AgentListItem,
  RunToolCallItem,
} from '../src/api/agentTypes.ts';

// ---------- 易错点 1：state 三值由服务端派生，前端只渲染不推导 ----------

test('state 三值直映：RUNNING / DISABLED / NO_ACTIVE_VERSION 各配独立文案', () => {
  assert.deepEqual(statePresentation('RUNNING'), { label: '运行中', tone: 'success' });
  assert.deepEqual(statePresentation('DISABLED'), { label: '已停用', tone: 'default' });
  assert.deepEqual(statePresentation('NO_ACTIVE_VERSION'), { label: '无生效版本', tone: 'warning' });
});

test('state 呈现不依赖 enabled：函数签名只接受 state，不存在 status×enabled 组合口径', () => {
  const labels = ['RUNNING', 'DISABLED', 'NO_ACTIVE_VERSION'].map(
    (value) => statePresentation(value as AgentListItem['state']).label,
  );
  // 三个值必须产出三个不同文案——任何「合并成同一状态」的推导都会在这里失败
  assert.equal(new Set(labels).size, 3);
});

test('版本状态（版本链）三态文案与 state 口径分离', () => {
  assert.deepEqual(versionStatusPresentation('ACTIVE'), { label: '生效中', tone: 'success' });
  assert.deepEqual(versionStatusPresentation('DRAFT'), { label: '草稿', tone: 'warning' });
  assert.deepEqual(versionStatusPresentation('RETIRED'), { label: '已下线', tone: 'default' });
});

// ---------- 易错点 2：模型元数据三态不得折叠 ----------

test('模型元数据三态：EXPOSED / NOT_PUBLIC / NOT_CONFIGURED 文案必须两两不同', () => {
  const exposed = modelVisibilityPresentation('EXPOSED');
  const notPublic = modelVisibilityPresentation('NOT_PUBLIC');
  const notConfigured = modelVisibilityPresentation('NOT_CONFIGURED');

  assert.equal(exposed.label, '已公开');
  assert.equal(notPublic.label, '未公开');
  assert.equal(notConfigured.label, '未配置');

  const labels = new Set([exposed.label, notPublic.label, notConfigured.label]);
  const notes = new Set([exposed.note, notPublic.note, notConfigured.note]);
  assert.equal(labels.size, 3, '三个可见性的标签不得折叠');
  assert.equal(notes.size, 3, '三个可见性的说明不得折叠');

  // 运维含义必须可区分：一个说「未登记 allowlist」，一个说「未配置」
  assert.match(notPublic.note, /allowlist/);
  assert.match(notConfigured.note, /未配置|未携带/);
});

// ---------- 易错点 3：LIVE 与 PREVIEW 视觉隔离 ----------

test('run_mode 默认 LIVE：URL 构造只在 PREVIEW 时写 run_mode', () => {
  const live = runsLocation({}, { limit: 20, offset: 0 });
  assert.equal(live, '/agents/runs');
  assert.doesNotMatch(live, /run_mode/);

  const preview = runsLocation({ runMode: 'PREVIEW' }, { limit: 20, offset: 0 });
  assert.match(preview, /run_mode=PREVIEW/);

  // 显式 LIVE 与缺省等价：不写参（后端默认即 LIVE）
  assert.doesNotMatch(runsLocation({ runMode: 'LIVE' }, { limit: 20, offset: 0 }), /run_mode/);
});

test('URL 解析把缺省/显式 LIVE 归一为 undefined（= LIVE），PREVIEW 单独识别', () => {
  assert.equal(runsFiltersFromParams(new URLSearchParams()).runMode, undefined);
  assert.equal(runsFiltersFromParams(new URLSearchParams('run_mode=LIVE')).runMode, undefined);
  assert.equal(runsFiltersFromParams(new URLSearchParams('run_mode=PREVIEW')).runMode, 'PREVIEW');
});

test('runModePresentation：LIVE 与 PREVIEW 有不同标签与说明', () => {
  const live = runModePresentation('LIVE');
  const preview = runModePresentation('PREVIEW');
  assert.equal(live.label, 'LIVE');
  assert.equal(preview.label, 'PREVIEW');
  assert.notEqual(live.note, preview.note);
  assert.match(preview.note, /草稿试跑/);
  assert.equal(preview.tone, 'warning');
});

test('运行状态/结果：outcome=null（运行中）显示「进行中」而非空白', () => {
  assert.equal(runOutcomePresentation(null).label, '进行中');
  assert.equal(runOutcomePresentation('SUCCESS').label, '成功');
  assert.equal(runOutcomePresentation('NEEDS_INPUT').label, '需澄清');
  assert.equal(runOutcomePresentation('REJECTED').label, '被拒绝');
  assert.equal(runOutcomePresentation('FAILED').label, '失败');
  assert.equal(runStatusPresentation('RUNNING').label, '运行中');
  assert.equal(runStatusPresentation('SUCCESS').label, '成功');
  assert.equal(runStatusPresentation('FAILED').label, '失败');
});

// ---------- input_digest：显式说明，不得渲染成空白 ----------

test('input_digest 必须带隐私说明文案，null 显示「—」', () => {
  assert.equal(INPUT_DIGEST_EXPLANATION, '输入原文不留存，仅存摘要用于比对');
  assert.equal(digestLabel('sha256-digest-value'), 'sha256-digest-value');
  assert.equal(digestLabel(null), '—');
  assert.equal(digestLabel(undefined), '—');
});

// ---------- 空态 / 近 7 日 / 工具调用 / 评测分组 / 格式化 ----------

test('近 7 日：两者皆 0 显示「无运行」，失败数非零单独着色', () => {
  assert.deepEqual(sevenDayPresentation(0, 0), { total: null, failure: null });
  assert.deepEqual(sevenDayPresentation(7, 0), { total: '7 次运行', failure: null });
  assert.deepEqual(sevenDayPresentation(12, 3), { total: '12 次运行', failure: '3 次失败' });
});

test('工具调用按 sequence_no 升序排序（副本，不改原数组）', () => {
  const calls: RunToolCallItem[] = [
    { sequence_no: 3, tool_name: 'c', args_summary: null, result_summary: null, latency_ms: 1, status: 'SUCCESS' },
    { sequence_no: 1, tool_name: 'a', args_summary: null, result_summary: null, latency_ms: 1, status: 'SUCCESS' },
    { sequence_no: 2, tool_name: 'b', args_summary: null, result_summary: null, latency_ms: 1, status: 'FAILED' },
  ];
  const sorted = sortToolCalls(calls);
  assert.deepEqual(
    sorted.map((call) => call.sequence_no),
    [1, 2, 3],
  );
  assert.deepEqual(
    calls.map((call) => call.sequence_no),
    [3, 1, 2],
    '原数组不得被修改',
  );
});

test('评测用例按 metric_kind 分 INVARIANT / QUALITY 两组', () => {
  const cases: AgentEvalCaseItem[] = [
    caseItem(1, 'INVARIANT'),
    caseItem(2, 'QUALITY'),
    caseItem(3, 'INVARIANT'),
  ];
  const groups = evalCaseGroups(cases);
  assert.deepEqual(groups.invariant.map((item) => item.id), [1, 3]);
  assert.deepEqual(groups.quality.map((item) => item.id), [2]);
  assert.deepEqual(evalCaseGroups([]), { invariant: [], quality: [] });
});

test('评测用例状态文案：PENDING / CONFIRMED 独立', () => {
  assert.equal(EVAL_STATUS_PRESENTATION.PENDING.label, '待确认');
  assert.equal(EVAL_STATUS_PRESENTATION.CONFIRMED.label, '已确认');
});

test('JSON / 时间 / 耗时格式化：null 与非法值一律「—」', () => {
  assert.equal(formatJson(null), '—');
  assert.equal(formatJson({ a: 1 }), '{\n  "a": 1\n}');
  assert.equal(formatCompactJson(null), '—');
  assert.equal(formatCompactJson({ a: 1 }), '{"a":1}');
  assert.equal(formatLatency(null), '—');
  assert.equal(formatLatency(250), '250 ms');
  assert.equal(formatLatency(2500), '2.50 s');
  assert.equal(formatTime(null), '—');
  assert.equal(formatTime('not-a-date'), '—');
  assert.match(formatTime('2026-08-13T10:00:00+08:00'), /^2026-08-13 \d{2}:\d{2}:\d{2}$/);
});

// ---------- 运行记录 URL：筛选与分页进 query string，可分享可刷新 ----------

test('运行记录 URL：筛选与分页全部进 query string', () => {
  const url = runsLocation(
    {
      slug: 'procurement-price',
      outcome: 'FAILED',
      runMode: 'PREVIEW',
      businessEntityType: 'ORDER',
      businessEntityId: 'SO-1001',
      startedFrom: '2026-08-13T00:00:00+08:00',
      startedTo: '2026-08-14T00:00:00+08:00',
    },
    { limit: 50, offset: 100 },
  );
  assert.equal(
    url,
    '/agents/runs?slug=procurement-price&outcome=FAILED&run_mode=PREVIEW&business_entity_type=ORDER&business_entity_id=SO-1001&started_from=2026-08-13T00%3A00%3A00%2B08%3A00&started_to=2026-08-14T00%3A00%3A00%2B08%3A00&limit=50&offset=100',
  );
});

test('运行记录 URL 解析：非法值丢弃，合法值还原（往返一致）', () => {
  const params = new URLSearchParams(
    'slug=procurement-price&outcome=SUCCESS&run_mode=PREVIEW&business_entity_type=ORDER&limit=50&offset=100&state=RUNNING&outcome=BOGUS',
  );
  const filters = runsFiltersFromParams(params);
  assert.equal(filters.slug, 'procurement-price');
  assert.equal(filters.outcome, 'SUCCESS');
  assert.equal(filters.runMode, 'PREVIEW');
  assert.equal(filters.businessEntityType, 'ORDER');
  // outcome=BOGUS 与 state 等无关参数被丢弃
  assert.equal(new URLSearchParams(runsSearchParams(filters, { limit: 50, offset: 100 }).toString()).get('state'), null);
  assert.equal(runsSearchParams(filters, { limit: 50, offset: 100 }).get('outcome'), 'SUCCESS');
});

test('RangePicker 值 → ISO-8601 带时区参数（后端 OffsetDateTime 要求）', () => {
  const { startedFrom, startedTo } = rangeToStartedParams([
    null,
    null,
  ]);
  assert.equal(startedFrom, undefined);
  assert.equal(startedTo, undefined);
});

// ---------- Agent 列表筛选：进 URL，客户端过滤 ----------

test('Agent 列表 URL：state / 草稿 / 写权限筛选可分享', () => {
  const params = agentListSearchParams({ state: 'DISABLED', hasDraft: true, allowWrite: false });
  assert.equal(params.toString(), 'state=DISABLED&draft=yes&write=no');
  const parsed = agentListFiltersFromParams(new URLSearchParams('state=RUNNING&draft=no&write=yes'));
  assert.deepEqual(parsed, { state: 'RUNNING', hasDraft: false, allowWrite: true });
  // 非法值丢弃：不认识的 state 不会污染筛选
  assert.deepEqual(agentListFiltersFromParams(new URLSearchParams('state=BOGUS&draft=maybe')), {
    state: undefined,
    hasDraft: undefined,
    allowWrite: undefined,
  });
});

test('Agent 列表筛选：客户端过滤逻辑与 URL 解析一致', () => {
  const items: AgentListItem[] = [
    agentItem('a', { state: 'RUNNING', draftCount: 2, allowWrite: true }),
    agentItem('b', { state: 'DISABLED', draftCount: 0, allowWrite: true }),
    agentItem('c', { state: 'NO_ACTIVE_VERSION', draftCount: 0, allowWrite: false }),
  ];
  assert.deepEqual(
    filterAgentItems(items, {}).map((item) => item.slug),
    ['a', 'b', 'c'],
  );
  assert.deepEqual(
    filterAgentItems(items, { state: 'RUNNING' }).map((item) => item.slug),
    ['a'],
  );
  assert.deepEqual(
    filterAgentItems(items, { hasDraft: true }).map((item) => item.slug),
    ['a'],
  );
  assert.deepEqual(
    filterAgentItems(items, { allowWrite: false }).map((item) => item.slug),
    ['c'],
  );
  const parsed = agentListFiltersFromParams(new URLSearchParams('state=DISABLED&draft=no&write=yes'));
  assert.deepEqual(parsed, { state: 'DISABLED', hasDraft: false, allowWrite: true });
  assert.deepEqual(
    filterAgentItems(items, parsed).map((item) => item.slug),
    ['b'],
  );
});

function caseItem(id: number, metricKind: 'INVARIANT' | 'QUALITY'): AgentEvalCaseItem {
  return {
    id,
    agent_slug: 'demo-agent',
    agent_version: 1,
    metric_kind: metricKind,
    input: { text: 'in' },
    expected: { text: 'out' },
    status: 'PENDING',
    created_by: 'alice',
    confirmed_by: null,
    confirmed_at: null,
  };
}

function agentItem(
  slug: string,
  overrides: { state: AgentListItem['state']; draftCount: number; allowWrite: boolean },
): AgentListItem {
  return {
    slug,
    name: slug,
    state: overrides.state,
    enabled: overrides.state === 'RUNNING',
    current_version: overrides.state === 'NO_ACTIVE_VERSION' ? null : 1,
    draft_count: overrides.draftCount,
    seven_day_run_count: 0,
    seven_day_failure_count: 0,
    allow_write: overrides.allowWrite,
    model_ref: 'model-ref',
    prompt_version: 'pv1',
    tools: [],
  };
}
