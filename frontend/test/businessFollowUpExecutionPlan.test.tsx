import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App as AntApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import BusinessFollowUpsPage from '@/pages/workbench/BusinessFollowUpsPage';

const submissionId = '9007199254740995';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function summary(businessKind: 'CUSTOMER' | 'SAMPLE' | 'FORMAL' = 'CUSTOMER') {
  return {
    id: '9007199254740993',
    followup_no: 'BF-0000000001',
    message_submission_id: submissionId,
    source_message_id: '9007199254740997',
    source_revision: 1,
    business_kind: businessKind,
    stage: 'PENDING_ORGANIZATION',
    processing_status: 'NOT_STARTED',
    created_by: 'manager-zhang',
    designated_reviewer: null,
    agent_slug: null,
    agent_version: null,
    task_status: null,
    task_attempts: null,
    task_failure_code: null,
    created_at: '2026-08-26T00:00:00Z',
    updated_at: '2026-08-26T00:00:00Z',
  };
}

function installFetch(handler?: (
  url: string,
  init?: RequestInit,
) => Response | Promise<Response> | undefined) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const handled = handler ? await handler(url, init) : undefined;
    if (handled) return handled;
    if (url.startsWith('/api/v1/business-followups?')) {
      return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/agents') return jsonResponse({ items: [] });
    throw new Error(`unexpected request ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderPage(entry = `/workbench/business-followups?submission_id=${submissionId}`) {
  return render(
    <AntApp>
      <MemoryRouter
        initialEntries={[entry]}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <BusinessFollowUpsPage />
      </MemoryRouter>
    </AntApp>,
  );
}

async function selectBusinessKind(label: '普通跟进' | '样品请求' | '正式订单') {
  const user = userEvent.setup();
  await user.click(screen.getByLabelText('业务类型'));
  await user.click(await screen.findByText(label, { selector: '.ant-select-item-option-content' }));
}

describe('客户跟进结构化执行计划', () => {
  test('业务类型切换只展示对应表单，正式订单商品明细可增删', async () => {
    installFetch();
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByLabelText('业务类型')).toBeInTheDocument();
    expect(screen.getByText('普通跟进')).toBeInTheDocument();
    expect(screen.queryByLabelText('样品名称')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('订单名称')).not.toBeInTheDocument();

    await selectBusinessKind('样品请求');
    expect(await screen.findByLabelText('样品名称')).toBeInTheDocument();
    expect(screen.queryByLabelText('订单名称')).not.toBeInTheDocument();

    await selectBusinessKind('正式订单');
    expect(await screen.findByLabelText('订单名称')).toBeInTheDocument();
    expect(screen.queryByLabelText('样品名称')).not.toBeInTheDocument();
    expect(screen.getByText('商品明细 1')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /添加商品明细/ }));
    expect(await screen.findByText('商品明细 2')).toBeInTheDocument();
    const removeButtons = screen.getAllByRole('button', { name: /删除商品明细/ });
    await user.click(removeButtons[1]);
    expect(screen.queryByText('商品明细 2')).not.toBeInTheDocument();
    expect(screen.getByText('商品明细 1')).toBeInTheDocument();
  });

  test('样品请求缺必填字段时不会发请求，填写合法后只发送白名单字段', async () => {
    const fetchMock = installFetch((url, init) => {
      if (url === '/api/v1/business-followups' && init?.method === 'POST') {
        return jsonResponse(summary('SAMPLE'), 201);
      }
      return undefined;
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByLabelText('业务类型');

    await user.type(screen.getByLabelText('员工大体草稿'), '客户希望先寄样确认');
    await selectBusinessKind('样品请求');
    await user.click(screen.getByRole('button', { name: '先建档，不运行模型' }));
    expect(await screen.findByText('请输入样品名称')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);

    await user.type(screen.getByLabelText('样品名称'), '牛肩切片样品');
    await user.type(screen.getByLabelText('商品名称'), '牛肩切片');
    await user.type(screen.getByLabelText('每单位数量'), '0.5');
    await user.type(screen.getByLabelText('数量单位'), 'kg');
    await user.type(screen.getByLabelText('单位份数'), '4');
    fireEvent.change(screen.getByLabelText('需求日期'), { target: { value: '2026-09-01' } });
    await user.click(screen.getByRole('button', { name: '先建档，不运行模型' }));

    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true);
    });
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({
      message_submission_id: submissionId,
      employee_draft: '客户希望先寄样确认',
      business_kind: 'SAMPLE',
      execution_plan: {
        sample_name: '牛肩切片样品',
        product_name: '牛肩切片',
        quantity_per_unit: 0.5,
        quantity_unit: 'kg',
        unit_count: 4,
        requested_date: '2026-09-01',
      },
    });
  });

  test('详情用中文展示业务类型与只读正式订单计划', async () => {
    const formal = {
      ...summary('FORMAL'),
      employee_draft: '已确认正式订单',
      execution_plan: {
        order_type: 'formal',
        name: '九月月度供货',
        delivery_date: '2026-09-10',
        delivery_address: '上海市浦东新区已授权地址',
        items: [{
          product_name: '牛肩切片',
          quantity_per_unit: 10,
          quantity_unit: 'kg',
          unit_count: 5,
        }],
      },
      latest_draft: null,
      draft_versions: [],
      approvals: [],
      assignments: [],
    };
    installFetch((url) => {
      if (url.startsWith('/api/v1/business-followups?')) {
        return jsonResponse({ items: [formal], page: 0, size: 20, total_elements: 1, total_pages: 1 });
      }
      if (url === '/api/v1/business-followups/9007199254740993') return jsonResponse(formal);
      return undefined;
    });
    const user = userEvent.setup();
    renderPage('/workbench/business-followups');

    await user.click(await screen.findByRole('button', { name: '详情' }));
    await screen.findByText('正式订单执行计划（只读）');
    const drawer = document.querySelector<HTMLElement>('.ant-drawer-content');
    expect(drawer).not.toBeNull();
    expect(within(drawer as HTMLElement).getByText('正式订单')).toBeInTheDocument();
    expect(within(drawer as HTMLElement).getByText('九月月度供货')).toBeInTheDocument();
    expect(within(drawer as HTMLElement).getByText(/商品明细 1 · 牛肩切片/)).toBeInTheDocument();
    expect(within(drawer as HTMLElement).getByText('上海市浦东新区已授权地址')).toBeInTheDocument();
  });

  test('正式订单提交固定 order_type 并发送至少一行结构化商品', async () => {
    const fetchMock = installFetch((url, init) => {
      if (url === '/api/v1/business-followups' && init?.method === 'POST') {
        return jsonResponse(summary('FORMAL'), 201);
      }
      return undefined;
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByLabelText('业务类型');

    await user.type(screen.getByLabelText('员工大体草稿'), '客户确认九月正式订单');
    await selectBusinessKind('正式订单');
    await user.type(screen.getByLabelText('订单名称'), '九月供货');
    fireEvent.change(screen.getByLabelText('交付日期'), { target: { value: '2026-09-10' } });
    await user.type(screen.getByLabelText('交付地址'), '上海市浦东新区已授权地址');
    await user.type(screen.getByLabelText('商品名称'), '牛肩切片');
    await user.type(screen.getByLabelText('每单位数量'), '10');
    await user.type(screen.getByLabelText('数量单位'), 'kg');
    await user.type(screen.getByLabelText('单位份数'), '5');
    await user.click(screen.getByRole('button', { name: '先建档，不运行模型' }));

    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true);
    });
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({
      message_submission_id: submissionId,
      employee_draft: '客户确认九月正式订单',
      business_kind: 'FORMAL',
      execution_plan: {
        order_type: 'formal',
        name: '九月供货',
        delivery_date: '2026-09-10',
        delivery_address: '上海市浦东新区已授权地址',
        items: [{
          product_name: '牛肩切片',
          quantity_per_unit: 10,
          quantity_unit: 'kg',
          unit_count: 5,
        }],
      },
    });
  });

  test('普通跟进详情明确保持空执行计划', async () => {
    const customer = {
      ...summary('CUSTOMER'),
      employee_draft: '只做普通跟进',
      execution_plan: null,
      latest_draft: null,
      draft_versions: [],
      approvals: [],
      assignments: [],
    };
    installFetch((url) => {
      if (url.startsWith('/api/v1/business-followups?')) {
        return jsonResponse({ items: [customer], page: 0, size: 20, total_elements: 1, total_pages: 1 });
      }
      if (url === '/api/v1/business-followups/9007199254740993') return jsonResponse(customer);
      return undefined;
    });
    const user = userEvent.setup();
    renderPage('/workbench/business-followups');

    await user.click(await screen.findByRole('button', { name: '详情' }));
    expect(await screen.findByText('普通跟进不生成结构化执行计划')).toBeInTheDocument();
  });
});
