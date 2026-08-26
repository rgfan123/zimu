/**
 * 系统 · Connector（GET /api/v1/connectors，PATCH /api/v1/connectors/{source_channel}，
 * POST /api/v1/connectors/{source_channel}/test-connection）。
 *
 * 两条互不替代的轴（契约 §4.6）：client_mode=MOCK|REAL 控制是否调用真实外部 Client；
 * transport_mode=EXCEL|API 控制文件接入或在线接口接入。当前三平台为 EXCEL + MOCK，
 * 隔离 Demo 也只用 Mock Adapter。凭据只存密文，不提供读取（credential_configured 只作标志）。
 */

import { useMemo, useState } from 'react';
import { App as AntApp, Alert, Button, Descriptions, Form, Input, Modal, Select, Space, Switch, Typography } from 'antd';
import { ApiOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { connectorsApi } from '@/api/endpoints';
import { CHANNEL_LABELS } from '@/constants/labels';
import type { ConnectorConfig, SourceChannel } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import { AdminCategoryTag, AdminEmpty, AdminStatusTag } from '@/pages/shared/AdminVisualComponents';
import { adminFailurePresentation, adminPageState } from '@/pages/shared/adminVisual';
import { PageState } from '@/pages/shared/PageState';
import '@/pages/shared/adminSurface.css';

const MODE_LABELS = { MOCK: '仿真模式', REAL: '生产模式' } as const;
const TRANSPORT_LABELS = { EXCEL: '文件接入', API: '在线接入' } as const;

export default function ConnectorsPage() {
  const { message: messageApi } = AntApp.useApp();
  const { data, loading, error, reload } = useAsync(() => connectorsApi.list(), []);
  const [editing, setEditing] = useState<ConnectorConfig | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [testing, setTesting] = useState<SourceChannel | null>(null);
  const [testResult, setTestResult] = useState<{ channel: SourceChannel; success: boolean; message?: string | null; latency_ms?: number } | null>(null);
  const [form] = Form.useForm();

  const rows = useMemo(() => data ?? [], [data]);

  const openEdit = (record: ConnectorConfig) => {
    form.setFieldsValue({
      transport_mode: record.transport_mode,
      enabled: record.enabled,
      endpoint: record.endpoint ?? undefined,
    });
    setEditing(record);
  };

  const handleSubmit = async () => {
    if (!editing) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await connectorsApi.update(editing.source_channel, {
        expected_version: editing.version,
        transport_mode: typeof values.transport_mode === 'string' ? values.transport_mode : undefined,
        enabled: typeof values.enabled === 'boolean' ? values.enabled : undefined,
        endpoint: typeof values.endpoint === 'string' && values.endpoint ? values.endpoint : undefined,
      });
      messageApi.success('已保存');
      setEditing(null);
      reload();
    } catch (e) {
      if (e instanceof Error) messageApi.error(errorMessage(e));
    } finally {
      setSubmitting(false);
    }
  };

  const handleTest = async (ch: SourceChannel) => {
    setTesting(ch);
    setTestResult(null);
    try {
      const res = await connectorsApi.test(ch);
      setTestResult({ channel: ch, success: res.success, message: res.message, latency_ms: res.latency_ms });
    } catch (e) {
      setTestResult({ channel: ch, success: false, message: errorMessage(e) });
    } finally {
      setTesting(null);
    }
  };

  const columns: ColumnsType<ConnectorConfig> = [
    {
      title: '来源渠道',
      dataIndex: 'source_channel',
      width: 120,
      render: (v: SourceChannel) => <AdminCategoryTag category={v}>{CHANNEL_LABELS[v]}</AdminCategoryTag>,
    },
    {
      title: '接入方式',
      dataIndex: 'transport_mode',
      width: 170,
      render: (v: ConnectorConfig['transport_mode']) => TRANSPORT_LABELS[v],
    },
    {
      title: '客户端模式',
      dataIndex: 'client_mode',
      width: 110,
      render: (v: ConnectorConfig['client_mode']) => <AdminCategoryTag category={v}>{MODE_LABELS[v]}</AdminCategoryTag>,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (v: boolean) => <AdminStatusTag status={v ? 'ACTIVE' : 'INACTIVE'} />,
    },
    { title: '端点', dataIndex: 'endpoint', ellipsis: true, render: (v?: string | null) => v ?? '—' },
    {
      title: '凭据',
      dataIndex: 'credential_configured',
      width: 100,
      render: (v?: boolean) => <AdminStatusTag status={v ? 'CONFIGURED' : 'UNCONFIGURED'} />,
    },
    { title: '版本', dataIndex: 'version', width: 70, align: 'right' },
    {
      title: '操作',
      key: 'action',
      width: 170,
      fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Typography.Link onClick={() => openEdit(r)}>编辑</Typography.Link>
          <Button
            size="small"
            type="link"
            icon={<ApiOutlined />}
            loading={testing === r.source_channel}
            onClick={() => handleTest(r.source_channel)}
          >
            测试连接
          </Button>
        </Space>
      ),
    },
  ];

  const viewState = adminPageState(loading, error, rows.length > 0);

  if (viewState === 'loading') {
    return (
      <div className="admin-page">
        <PageState state="loading" description="正在加载渠道连接器…" />
      </div>
    );
  }

  if (viewState === 'error') {
    const presentation = adminFailurePresentation(error, '渠道连接器加载失败');
    return (
      <div className="admin-page">
        <PageState state="error" message={presentation.title} description={presentation.description} onRetry={reload} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <PageShell
        title="渠道接入"
        description="统一维护四个来源渠道的接入方式。彩食鲜、聚福宝和飞象当前采用文件接入；只有完成平台授权与生产连通性验收后，才可切换为在线接入。"
        actions={<Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>}
      >
        {testResult ? (
          <Alert
            type={testResult.success ? 'success' : 'error'}
            showIcon
            closable
            onClose={() => setTestResult(null)}
            message={`${CHANNEL_LABELS[testResult.channel]} 连通性测试：${testResult.success ? '通过' : '失败'}`}
            description={testResult.message ?? (testResult.latency_ms != null ? `延迟 ${testResult.latency_ms} ms` : undefined)}
          />
        ) : null}

        <div className="admin-surface">
          <DataTable<ConnectorConfig>
            rowKey="source_channel"
            columns={columns}
            dataSource={rows}
            size="middle"
            scroll={{ x: 1000 }}
            pagination={false}
            emptyText={<AdminEmpty description="暂无渠道连接器配置" />}
          />
        </div>
      </PageShell>

      <Modal
        title={`编辑渠道连接器 ${editing ? CHANNEL_LABELS[editing.source_channel] : ''}`}
        open={Boolean(editing)}
        onCancel={() => setEditing(null)}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okButtonProps={{ disabled: submitting }}
        cancelButtonProps={{ disabled: submitting }}
        closable={!submitting}
        maskClosable={!submitting}
        keyboard={!submitting}
        width={460}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="transport_mode" label="接入方式" rules={[{ required: true, message: '请选择接入方式' }]}>
            <Select options={(Object.keys(TRANSPORT_LABELS) as (keyof typeof TRANSPORT_LABELS)[]).map((k) => ({ value: k, label: TRANSPORT_LABELS[k] }))} />
          </Form.Item>
          <Form.Item name="endpoint" label="服务地址（在线接入）" extra="此处只维护服务地址；访问凭据单独加密保存，页面不提供读取">
            <Input placeholder="https://…" />
          </Form.Item>
          <Form.Item
            name="enabled"
            label="启用"
            valuePropName="checked"
            extra={
              editing?.source_channel === 'WECOM'
                ? '企业微信长连接由连接就绪度诊断独立判定，此开关不影响其消息接收；已导入批次与既有事实不受影响'
                : '停用后，该渠道的连通性测试将判定为失败；文件导入、已导入批次与既有事实不受影响'
            }
          >
            <Switch />
          </Form.Item>
        </Form>
        {editing ? (
          <Descriptions
            className="admin-form-note"
            size="small"
            column={1}
            items={[
              { key: 'cm', label: '客户端模式', children: <AdminCategoryTag category={editing.client_mode}>{MODE_LABELS[editing.client_mode]}</AdminCategoryTag> },
              { key: 'cc', label: '凭据', children: <AdminStatusTag status={editing.credential_configured ? 'CONFIGURED' : 'UNCONFIGURED'} /> },
            ]}
          />
        ) : null}
      </Modal>
    </div>
  );
}
