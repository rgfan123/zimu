/**
 * 票 04：「客户跟进」入口受客户中心接通状态控制——端到端（外壳 + 侧栏 + 页面）。
 *
 * businessObjectNavigation.test.ts 断言的是导航树这一层的两态结果；这里断言的是**接线**：
 * 外壳真的把 `GET /api/v1/business-modules` 的答案用到了侧栏，页面真的照同一份答案说话。
 * 两层分开测是必要的——纯函数各自正确不等于它们被接上了（票 03 已为此把 navigation 参数化）。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { createRouteHarness, jsonResponse, page, type RouteHarness } from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/business-followups');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const CLOSED_NOTICE = /客户中心未接通，本页只能查看已有档案/;

/** 客户中心接通与否只改这一个应答；其余接口两态完全一致，确保差异只来自清单本身。 */
function followUpFetch(openModules: string[]) {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-modules')) return jsonResponse({ modules: openModules });
    if (url.startsWith('/api/v1/business-followups?')) return jsonResponse(page([followUpSummary()]));
    if (url === '/api/v1/agents') return jsonResponse(page([]));
    throw new Error(`unexpected request ${url}`);
  };
}

function followUpSummary() {
  return {
    id: '9007199254740993',
    followup_no: 'BF-0000000001',
    business_kind: 'CUSTOMER',
    stage: 'PENDING_ORGANIZATION',
    processing_status: 'NOT_STARTED',
    message_submission_id: '9007199254740995',
    evidence_version: 1,
    draft_version: 0,
    created_at: '2026-08-30T02:00:00Z',
    updated_at: '2026-08-30T02:00:00Z',
  };
}

/** 侧栏某个分组当前渲染出的可见入口名称（按 DOM 顺序）。 */
function railItems(groupTitle: string): string[] {
  const toggle = [...document.querySelectorAll<HTMLButtonElement>('.zs-nav .zs-grp-toggle')]
    .find((button) => button.textContent?.includes(groupTitle));
  if (!toggle?.parentElement) throw new Error(`missing rail group: ${groupTitle}`);
  return [...toggle.parentElement.querySelectorAll('a')]
    .map((link) => link.querySelector('.nm')?.textContent?.trim() ?? '');
}

test('客户中心未接通：侧边栏没有客户跟进，但直达仍渲染页面并说明现在能做什么', async () => {
  globalThis.fetch = followUpFetch([]);
  await harness.mount(['/workbench/business-followups']);

  await harness.waitFor(() => assert.match(harness.bodyText(), CLOSED_NOTICE));
  assert.deepEqual(
    railItems('我的工作台'),
    ['今日发货工作台', '复核收件箱', '采购', '对账工作台', '调度台'],
    '未接通时「客户跟进」不出现在侧边栏',
  );
  assert.equal(
    document.querySelector('.zs-nav a[href="/workbench/business-followups"]'),
    null,
  );

  // 直达可达且页面正常渲染：列表照常出数，不是错误页也不是空壳。
  assert.equal(harness.location(), '/workbench/business-followups');
  assert.match(harness.bodyText(), /BF-0000000001/);
  assert.match(harness.bodyText(), /KEHUZX_NOT_CONFIGURED/, '提示要说清失败会以哪个稳定错误码出现');
});

test('客户中心已接通：客户跟进回到原位置，页面不再出现未接通提示', async () => {
  globalThis.fetch = followUpFetch(['customer-center']);
  await harness.mount(['/workbench/business-followups']);

  await harness.waitFor(() => {
    assert.deepEqual(
      railItems('我的工作台'),
      ['今日发货工作台', '复核收件箱', '客户跟进', '采购', '对账工作台', '调度台'],
      '接通后「客户跟进」回到复核收件箱之后、采购之前的原有位置',
    );
  });
  const link = document.querySelector<HTMLAnchorElement>('.zs-nav a[href="/workbench/business-followups"]');
  assert.equal(link?.querySelector('.nm')?.textContent?.trim(), '客户跟进', 'label 不变');

  assert.equal(harness.location(), '/workbench/business-followups');
  assert.match(harness.bodyText(), /BF-0000000001/);
  assert.doesNotMatch(harness.bodyText(), CLOSED_NOTICE);
});

test('清单读不到时按未接通处置：菜单与页面口径一致，不各说各话', async () => {
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-modules')) throw new Error('network down');
    if (url.startsWith('/api/v1/business-followups?')) return jsonResponse(page([followUpSummary()]));
    if (url === '/api/v1/agents') return jsonResponse(page([]));
    throw new Error(`unexpected request ${url}`);
  };
  await harness.mount(['/workbench/business-followups']);

  await harness.waitFor(() => assert.match(harness.bodyText(), CLOSED_NOTICE));
  assert.equal(
    document.querySelector('.zs-nav a[href="/workbench/business-followups"]'),
    null,
    '读不到清单时保守：菜单不放出入口，页面也不假称已接通',
  );
  assert.match(harness.bodyText(), /BF-0000000001/, '外壳读不到清单不影响页面本身可用');
});
