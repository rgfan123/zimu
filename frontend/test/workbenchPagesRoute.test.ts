/**
 * 工作台页面（Issue #97 批次 B 迁移 + Issue #64 路由拆分后）的用户可观察契约：
 * - ManualReviewPage（/workbench/reviews）：PageShell 页头 + FilterBar 筛选 + DataTable 队列表；
 *   空态 / 错误态统一走 DataTable 默认行为，文案与迁移前逐字一致。
 * - AlertsQueuePage（/workbench/alerts）：运营提醒独立路由页，同样采用共享队列承载结构；
 *   空态 / 错误态文案与拆分前提醒视图逐字一致。
 * - ChannelMessagesPage：PageShell 页头（刷新动作）+ DataTable 列表（错误态带重试），
 *   抽屉中的消息证据与解释历史保持可读。
 *
 * 迁移/拆分只换承载结构，本文件用 public-route 行为固定可见结果（页头文案、空态、错误条、
 * 重试、详情抽屉），避免未来重构把文案/空态/重试悄悄改掉。
 * #95/#96/#64 的 URL 契约（import_batch fail-closed、status/reason/team 唯一事实源、
 * 旧 view=alerts 重定向）已由 importBatchReviewRoute / manualReviewQueueRoute /
 * dashboardDispatchRoute / alertsQueueRoute 固定，本文件不重复。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  apiErrorResponse,
  control,
  createRouteHarness,
  jsonResponse,
  page,
  reviewCaseFixture,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/reviews');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

/** 复核队列页 mock：队列 + 运营提醒两条数据源（提醒由独立路由页拉取）。 */
function reviewPagesFetch(overrides: {
  queue?: () => Response;
  alerts?: () => Response;
} = {}) {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith('/api/v1/review-cases?')) {
      return overrides.queue
        ? overrides.queue()
        : jsonResponse(page([reviewCaseFixture('1')]));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') {
      return overrides.alerts ? overrides.alerts() : jsonResponse(page([]));
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('manual review page renders the shared page shell header and the queue table', async () => {
  globalThis.fetch = reviewPagesFetch();

  await harness.mount(['/workbench/reviews']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  assert.match(harness.bodyText(), /人工作业中心/, '页头标题必须保留');
  assert.match(harness.bodyText(), /阻断复核需要明确解决；运营提醒只记录知晓，不推进业务状态/, '页头说明必须保留');
  assert.match(harness.bodyText(), /待处理/, '队列行状态标签必须保留');
  assert.match(harness.bodyText(), /SKU 映射待确认/, '事项类型列必须保留');
  assert.ok(control('刷新'), 'FilterBar 必须保留刷新动作');
  assert.ok(document.querySelector('#review-status-filter'), '状态筛选控件必须保留（#96 测试依赖的 id）');
});

test('manual review queue failure uses the shared table banner and keeps the empty table', async () => {
  globalThis.fetch = reviewPagesFetch({
    queue: () => apiErrorResponse(500, 'INTERNAL', 'queue exploded'),
  });

  await harness.mount(['/workbench/reviews']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /复核队列加载失败/));
  assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/);
  assert.match(harness.bodyText(), /当前没有复核事项/, '错误条下方表格仍渲染空态（DataTable 默认行为）');
});

test('alerts page keeps its filter, empty state and failure banner on its own route', async () => {
  globalThis.fetch = reviewPagesFetch({
    alerts: () => apiErrorResponse(500, 'INTERNAL', 'alerts exploded'),
  });

  await harness.mount(['/workbench/alerts']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /运营提醒加载失败/));
  assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/);
  assert.match(harness.bodyText(), /当前没有运营提醒/, '提醒空态文案必须保留');
  assert.match(harness.bodyText(), /运营提醒只记录知晓，不推进业务状态/, '提醒页页头说明必须保留');
  assert.ok(control('刷新'));
});

function channelMessageSummary(id: string) {
  return {
    id,
    corp_id: 'corp',
    connection_id: 'business-relay',
    bot_id: 'bot',
    message_id: `msg-${id}`,
    chat_id: 'group-1',
    chat_type: 'group',
    sender_user_id: 'customer-1',
    message_type: 'text',
    content_preview: '张三收，羊小腿两盒',
    received_at: '2026-08-20T02:00:00Z',
  };
}

function channelMessageDetail(id: string) {
  return {
    ...channelMessageSummary(id),
    content: '张三收，上海测试路1号，羊小腿两盒，月底结算',
    quote_content: null,
    raw_payload_ref: 'channel-message:1',
    submission_id: '18',
  };
}

function submissionFixture() {
  return {
    id: '18',
    submission_no: 'SUB-18',
    status: 'DRAFTED',
    source_message_id: 'msg-1',
    current_intent: 'CUSTOMER_ORDER',
    latest_error: null,
    interpretations: [{
      version: 1,
      intent: 'CUSTOMER_ORDER',
      provider: 'mock-provider',
      model: 'mock-model',
      prompt_version: 'p1',
      error: null,
      created_at: '2026-08-20T02:05:00Z',
    }],
    latest_task: {
      id: 't1',
      task_type: 'INTERPRET',
      status: 'SUCCEEDED',
      attempts: 1,
      max_attempts: 3,
      last_error: null,
      created_at: '2026-08-20T02:05:00Z',
    },
    created_at: '2026-08-20T02:00:00Z',
  };
}

test('channel messages page renders the shared page shell header and the list', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/channel-messages?page=0&size=20') {
      return jsonResponse(page([channelMessageSummary('1')]));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/channel-messages']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /customer-1/));
  assert.match(harness.bodyText(), /企业微信消息/, '页头标题必须保留（原表格 Card 标题）');
  assert.match(harness.bodyText(), /张三收，羊小腿两盒/, '消息内容预览列必须保留');
  assert.match(harness.bodyText(), /共 1 条/, '分页统计必须保留');
  assert.ok(control('刷新'), '页头必须保留刷新动作');
});

test('channel messages failure banner keeps the retry action and refetches', async () => {
  let calls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/channel-messages')) {
      calls += 1;
      return apiErrorResponse(500, 'INTERNAL', 'message list exploded');
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/channel-messages']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /消息记录加载失败/));
  assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/);
  await harness.waitFor(() => assert.ok(control('重试'), 'DataTable 错误条必须保留重试动作'));
  assert.equal(calls, 1, '首次加载只应发起一次列表请求');
  await control('重试').click();
  await harness.waitFor(() => assert.equal(calls, 2, '点击重试必须重新拉取列表'));
});

test('channel messages drawer keeps evidence detail and interpretation history readable', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/channel-messages?page=0&size=20') {
      return jsonResponse(page([channelMessageSummary('1')]));
    }
    if (url === '/api/v1/channel-messages/1') {
      return jsonResponse(channelMessageDetail('1'));
    }
    if (url === '/api/v1/message-submissions/18') {
      return jsonResponse(submissionFixture());
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/channel-messages']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /customer-1/));
  await control('详情').click();
  await harness.waitFor(() => assert.match(harness.bodyText(), /消息证据详情/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /消息原文/));
  assert.match(harness.bodyText(), /张三收，上海测试路1号，羊小腿两盒，月底结算/, '消息原文必须保留');
  assert.match(harness.bodyText(), /原始证据引用/, '证据白名单字段必须保留');
  assert.match(harness.bodyText(), /消息解释/, '解释卡片必须保留');
  assert.match(harness.bodyText(), /SUB-18/, '提交编号必须保留');
  assert.match(harness.bodyText(), /客户订单/, '意图白名单文案必须保留');
  assert.match(harness.bodyText(), /mock-model/, '解释版本历史（模型列）必须保留');
  assert.ok(control('重新解释'), '重新解释动作必须保留');
});
