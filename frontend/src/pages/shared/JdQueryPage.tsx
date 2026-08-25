/**
 * JdQueryPage —— 京东只读查询页配置驱动骨架（issue #40「京东只读查询页骨架收敛」）。
 *
 * 5 个京东查询页（基础信息 / 库存 / 序列号 / 专业单据 / 退货退供）结构高度同构：
 * 配置数组 + Select 选查询 + Form 参数 + 白名单结果展示 + 「业务码 2001 权限未开通」分支
 * + message 反馈。本组件把这一骨架收敛为声明式配置：
 *
 *   <JdQueryPage config={{ title, subtitle, icon, options, result, feedback, ... }} />
 *
 * 配置 schema 见 jdQueryCore.ts（JdQueryField / JdQueryOption / JdQueryPageConfig）。
 * 页面只声明：查询名、参数字段、白名单、执行函数、结果展示、权限/成功/失败反馈；
 * 渲染、状态管理、参数构建、白名单收集、PageState 加载态均在骨架内完成。
 *
 * 页面加载态复用 issue #36 的 PageState：status 插槽加载中渲染 loading 态，
 * 失败渲染 error 态（带重试），替代各页手写的 Alert + 重试按钮。
 */

import { forwardRef, useImperativeHandle, useState } from 'react';
import type { ForwardedRef, ReactNode } from 'react';
import {
  App as AntApp,
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import { apiRequest, errorMessage } from '@/api/client';
import type { QueryValue } from '@/api/client';
import type { JdQueryResult } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { saasVisualTokens } from '@/theme/saasTheme';
import { PageState } from './PageState';
import {
  buildJdParams,
  collectWhitelisted,
  isJdPermissionDenied,
  normalizeKey,
} from './jdQueryCore';
import type {
  JdCollectConfig,
  JdQueryField,
  JdQueryOption,
  JdQueryPageConfig,
  JdResultRow,
  JdStatusCtx,
  JdStatusData,
  JdSuccessCtx,
} from './jdQueryCore';

export type {
  JdCollectConfig,
  JdFeedbackConfig,
  JdFieldKind,
  JdQueryField,
  JdQueryOption,
  JdQueryPageConfig,
  JdResultConfig,
  JdResultRow,
  JdStatusCtx,
  JdStatusData,
  JdStatusSlot,
  JdSuccessCtx,
} from './jdQueryCore';
export {
  buildJdParams,
  collectWhitelisted,
  isJdBusinessCodeDenied,
  isJdPermissionDenied,
  normalizeKey,
  scalarString,
} from './jdQueryCore';

/** 骨架对外命令式句柄：退货页列表行「系统退货入库单号」点击直达详情。 */
export interface JdQueryPageHandle {
  /** 切换到指定查询；values 可选地回填字段；autoRun 为 true 时立即执行（跳过表单校验，与原详情直达行为一致）。 */
  switchTo: (key: string, values?: Record<string, unknown>, autoRun?: boolean) => void;
}

/** 未提供 run 时按 path 走统一 apiRequest。 */
function resolveRun(opt: JdQueryOption): (values: Record<string, unknown>) => Promise<JdQueryResult> {
  if (opt.run) return opt.run;
  const path = opt.path;
  if (!path) throw new Error(`JdQueryPage: 查询「${opt.key}」需要提供 run 或 path`);
  return (values) => apiRequest<JdQueryResult>(path, { params: values as Record<string, QueryValue> });
}

/** 参数字段渲染：text / number / list 走 Input / InputNumber，flag 走选项 Select。 */
function renderField(field: JdQueryField, layout: 'inline' | 'vertical'): ReactNode {
  if (field.kind === 'flag') {
    return (
      <Form.Item key={field.name} name={field.name} label={field.label} style={{ marginBottom: 0 }} tooltip={field.tip}>
        <Select style={{ width: field.width ?? 120 }} options={field.options} allowClear placeholder="默认" />
      </Form.Item>
    );
  }
  const isNumber = field.kind === 'number';
  const control = isNumber ? (
    <InputNumber
      min={field.min ?? 1}
      style={{ width: field.width ?? (layout === 'vertical' ? '100%' : 140) }}
      placeholder={field.placeholder ?? (layout === 'vertical' ? '整数' : '')}
    />
  ) : (
    <Input
      style={{ width: field.width ?? (layout === 'vertical' ? undefined : 180) }}
      placeholder={field.placeholder ?? (layout === 'vertical' ? `请输入${field.label}` : '')}
      allowClear
    />
  );
  return (
    <Form.Item
      key={field.name}
      name={field.name}
      label={field.label}
      style={{ marginBottom: layout === 'vertical' ? 8 : 0 }}
      rules={field.required ? [{ required: true, message: field.requiredMessage ?? `请填写${field.label}` }] : undefined}
    >
      {control}
    </Form.Item>
  );
}

/** 表格列：按数据键顺序取白名单内字段（退货页列表口径）。 */
function tableColumnsFrom(data: Record<string, unknown>[], whitelist: Record<string, string>): { key: string; title: string }[] {
  const seen = new Set<string>();
  const columns: { key: string; title: string }[] = [];
  for (const item of data) {
    for (const key of Object.keys(item)) {
      const normalized = normalizeKey(key);
      if (whitelist[normalized] && !seen.has(normalized)) {
        seen.add(normalized);
        columns.push({ key, title: whitelist[normalized] });
      }
    }
  }
  return columns;
}

interface JdQueryPageProps {
  config: JdQueryPageConfig;
}

function JdQueryPageInner({ config }: JdQueryPageProps, ref: ForwardedRef<JdQueryPageHandle>) {
  const { message: messageApi } = AntApp.useApp();
  const [searchParams] = useSearchParams();
  const prefill = config.prefill ? config.prefill(searchParams) : null;
  const [kind, setKind] = useState<string>(() => prefill?.kind ?? config.options[0].key);
  const [form] = Form.useForm<Record<string, unknown>>();
  const [result, setResult] = useState<JdQueryResult | null>(null);
  const [loading, setLoading] = useState(false);

  const status = useAsync<JdStatusData | undefined>(
    () => (config.status ? config.status.load() : Promise.resolve(undefined)),
    [],
  );

  // 配置默认值（每次渲染重建成本极低；config 由页面模块级常量或 useMemo 提供，身份稳定）。
  const formLayout = config.formLayout ?? 'inline';
  const queryBar = config.queryBar ?? 'row';
  const buildParams = config.buildParams ?? buildJdParams;
  const isPermissionDenied = config.isPermissionDenied ?? isJdPermissionDenied;
  const isMock = config.mock ?? ((ctx: { result: JdQueryResult }) => ctx.result.business_code === 'MOCK_SUCCESS');
  const collect: JdCollectConfig = {
    maxRows: 40,
    arrayJoin: null,
    dedupe: false,
    prefixNested: false,
    skipUnlisted: false,
    indexArrays: false,
    includeNull: false,
    ...config.collect,
  };
  const resultCfg = {
    container: 'alert' as const,
    requestId: 'none' as const,
    emptyText: '本次结果没有可展示的业务字段。',
    bordered: false,
    tablePageSize: 10,
    ...config.result,
  };
  const feedback = { enabled: true, clearResultOnError: true, ...config.feedback };

  const option = config.options.find((o) => o.key === kind) ?? config.options[0];

  const execute = async (opt: JdQueryOption, values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const params = buildParams(opt.fields, values);
      const outcome = await resolveRun(opt)(params);
      setResult(outcome);
      if (feedback.enabled !== false) {
        if (outcome.success) {
          messageApi.success(feedback.success ? feedback.success(opt.label, outcome) : `${opt.label}完成`);
        } else if (isPermissionDenied(outcome)) {
          messageApi.warning(feedback.denied ? feedback.denied(opt.label, outcome) : '权限未开通');
        } else {
          messageApi.warning(feedback.failed ? feedback.failed(opt.label, outcome) : '查询未完成');
        }
      }
    } catch (err) {
      if (feedback.clearResultOnError !== false) setResult(null);
      messageApi.error(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const runQuery = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    await execute(option, values);
  };

  const switchTo = (key: string, values?: Record<string, unknown>, autoRun?: boolean) => {
    setKind(key);
    if (!(config.remountForm ?? false)) form.resetFields();
    if (values) {
      for (const [name, value] of Object.entries(values)) form.setFieldValue(name, value);
    }
    setResult(null);
    if (autoRun && values) {
      const next = config.options.find((o) => o.key === key) ?? config.options[0];
      void execute(next, values);
    }
  };

  useImperativeHandle(ref, () => ({ switchTo }));

  const statusCtx: JdStatusCtx = {
    data: status.data ?? null,
    loading: status.loading,
    error: status.error,
    reload: status.reload,
  };
  const mode = status.data?.client_mode;
  const queryDisabled = config.status?.disableQueryUntilReady ? !status.data : false;

  const rows: JdResultRow[] = [];
  if (result?.success && option.view !== 'table') {
    collectWhitelisted(result.data, option.whitelist, rows, collect);
  }

  const renderSuccessBody = (ctx: JdSuccessCtx): ReactNode => {
    const data = ctx.result.data;
    if (data === null || data === undefined) {
      return <Typography.Text type="secondary">{resultCfg.emptyText}</Typography.Text>;
    }
    if (option.view === 'table' && Array.isArray(data)) {
      const tableRows = data as Record<string, unknown>[];
      const columns = tableColumnsFrom(tableRows, option.whitelist).map((col) => ({
        title: col.title,
        dataIndex: col.key,
        key: col.key,
        render: (value: unknown) => {
          if (value === null || value === undefined || typeof value === 'object') return '—';
          return option.renderCell ? option.renderCell(col.key, value) : String(value);
        },
      }));
      return (
        <Table<Record<string, unknown>>
          rowKey={(_record, index) => `${index}`}
          columns={columns}
          dataSource={tableRows}
          loading={loading}
          size="middle"
          pagination={{ pageSize: resultCfg.tablePageSize ?? 10, showTotal: (total) => `共 ${total} 条` }}
        />
      );
    }
    if (typeof data === 'object' && rows.length) {
      return (
        <Descriptions
          size="small"
          column={{ xs: 1, sm: 2 }}
          bordered={resultCfg.bordered}
          items={rows.map((row, index) => ({
            key: `${row.label}-${index}`,
            label: row.label,
            children: row.value,
          }))}
        />
      );
    }
    return <Typography.Text type="secondary">{resultCfg.emptyText}</Typography.Text>;
  };

  const renderResult = (): ReactNode => {
    if (!result) return null;
    if (result.success) {
      const ctx: JdSuccessCtx = { label: option.label, result, mock: isMock({ result, mode }), mode };
      const title = resultCfg.successTitle ? resultCfg.successTitle(ctx) : `${option.label}完成`;
      const body = renderSuccessBody(ctx);
      if (resultCfg.container === 'card') {
        return (
          <Card size="small" title={title} extra={resultCfg.cardExtra ? resultCfg.cardExtra(result) : null}>
            {body}
          </Card>
        );
      }
      return (
        <Alert
          type="success"
          showIcon
          message={title}
          description={
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              {resultCfg.requestId === 'top' ? (
                <Typography.Text type="secondary">请求 ID：{result.request_id ?? '—'}</Typography.Text>
              ) : null}
              {body}
              {resultCfg.requestId === 'bottom' ? (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  请求标识：{result.request_id ?? '—'}　|　业务码：{result.business_code ?? '—'}
                </Typography.Text>
              ) : null}
            </Space>
          }
        />
      );
    }
    if (isPermissionDenied(result)) {
      return (
        <Alert
          type="warning"
          showIcon
          message={resultCfg.denied?.title ?? '权限未开通'}
          description={resultCfg.denied?.description ? resultCfg.denied.description(result) : undefined}
        />
      );
    }
    return (
      <Alert
        type={resultCfg.failed?.alertType ?? 'error'}
        showIcon
        message={resultCfg.failed?.title ? resultCfg.failed.title(option.label, result) : '查询未完成'}
        description={resultCfg.failed?.description ? resultCfg.failed.description(result) : undefined}
      />
    );
  };

  const selectEl = (
    <Select
      style={{ width: config.selectWidth ?? 180 }}
      value={kind}
      onChange={(key) => switchTo(String(key))}
      options={config.options.map((o) => ({ value: o.key, label: o.label }))}
    />
  );

  const fieldsEl = (
    <>
      {queryBar === 'form' ? (
        <Form.Item label={config.selectLabel ?? '接口'} style={{ marginBottom: 0 }}>
          {selectEl}
        </Form.Item>
      ) : null}
      {option.fields.map((field) => renderField(field, formLayout))}
      {queryBar !== 'compact' ? (
        <Form.Item style={{ marginBottom: 0 }}>
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading} disabled={queryDisabled}>
            查询
          </Button>
        </Form.Item>
      ) : null}
    </>
  );

  const formEl = (
    <Form
      form={form}
      key={config.remountForm ? kind : undefined}
      layout={formLayout}
      initialValues={formInitialValues()}
      onFinish={queryBar === 'compact' ? undefined : runQuery}
      style={formLayout === 'vertical' ? { maxWidth: 720 } : { rowGap: 12 }}
    >
      {fieldsEl}
    </Form>
  );

  function formInitialValues(): Record<string, unknown> {
    const base = { ...(config.defaults?.[kind] ?? {}) };
    if (config.prefill && prefill && kind === prefill.kind) {
      return { ...base, ...prefill.values };
    }
    return base;
  }

  const footerContent = typeof config.footer === 'function' ? config.footer({ status: statusCtx }) : config.footer;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" styles={{ body: { padding: '16px 18px' } }}>
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Space align="start" size={12} style={{ width: '100%' }}>
            <span style={{ color: saasVisualTokens.brand.primary, fontSize: 20, marginTop: 3 }}>{config.icon}</span>
            <div>
              <Typography.Title level={5} style={{ margin: 0 }}>{config.title}</Typography.Title>
              <Typography.Text type="secondary">{config.subtitle}</Typography.Text>
            </div>
            <div style={{ flex: 1 }} />
            {config.status ? config.status.renderTag(status.data ?? null) : config.headerTags}
          </Space>

          {config.status?.usePageState && status.loading ? (
            <PageState state="loading" description="正在确认连接状态…" />
          ) : null}
          {config.status?.usePageState && status.error ? (
            <PageState
              state="error"
              message={config.status.errorTitle}
              description={errorMessage(status.error)}
              onRetry={status.reload}
            />
          ) : null}
          {status.data && config.status?.warning ? config.status.warning(status.data) : null}

          {queryBar === 'compact' ? (
            <Space.Compact style={{ width: '100%', maxWidth: 720 }}>
              {selectEl}
              <Button
                type="primary"
                icon={<SearchOutlined />}
                loading={loading}
                onClick={runQuery}
                style={{ borderTopLeftRadius: 0, borderBottomLeftRadius: 0 }}
              >
                查询
              </Button>
            </Space.Compact>
          ) : null}

          {queryBar === 'row' ? (
            <Space size={12} wrap>
              {selectEl}
              {option.description ? (
                <Typography.Text type="secondary">{option.description}</Typography.Text>
              ) : null}
            </Space>
          ) : null}

          {queryBar === 'inline' ? (
            <Space align="start" size={12} style={{ width: '100%' }} wrap>
              {selectEl}
              {formEl}
            </Space>
          ) : null}

          {queryBar !== 'inline' ? formEl : null}

          {config.hint}

          {queryBar === 'inline' && option.description ? (
            <Typography.Text type="secondary">{option.description}</Typography.Text>
          ) : null}

          {renderResult()}
        </Space>
      </Card>
      {footerContent}
    </Space>
  );
}

const JdQueryPage = forwardRef<JdQueryPageHandle, JdQueryPageProps>(JdQueryPageInner);

export default JdQueryPage;
