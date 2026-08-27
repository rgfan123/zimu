/**
 * 系统管理 · 机器人管理：登记会出现在企业微信通讯录里的 aibot 实例（需要配置密钥那种）。
 *
 * 管理界面先行，运行时多机器人接线未启用——生产当前唯一在跑的长连接凭据仍来自部署配置
 * （app.wecom.* / WECOM_BOT_ID / WECOM_SECRET 环境变量），本页登记的实例暂不影响任何
 * 在跑连接，见页内 Alert。
 */

import { useState } from 'react';
import {
  Alert,
  App as AntApp,
  Button,
  Drawer,
  Form,
  Input,
  Space,
  Switch,
  Typography,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { wecomBotsApi } from '@/api/endpoints';
import type { WecomBot } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import { AdminEmpty, AdminStatusTag } from '@/pages/shared/AdminVisualComponents';
import { adminFailurePresentation, adminPageState } from '@/pages/shared/adminVisual';
import { PageState } from '@/pages/shared/PageState';
import '@/pages/shared/adminSurface.css';

/** bot_id 前端校验：企微后台颁发的业务标识，只做「非空、不过长、无空白控制字符」的保守校验。 */
const validateBotId = (_rule: unknown, value: string | undefined) => {
  if (typeof value !== 'string' || value.trim().length === 0) {
    return Promise.reject(new Error('请填写 bot_id'));
  }
  const trimmed = value.trim();
  if (trimmed.length > 128) {
    return Promise.reject(new Error('bot_id 最长 128 个字符'));
  }
  if (/[^\x21-\x7E]/.test(trimmed)) {
    return Promise.reject(new Error('bot_id 只能包含可见 ASCII 字符（不含空白与控制字符）'));
  }
  return Promise.resolve();
};

export default function WecomBotsPage() {
  const { message: messageApi } = AntApp.useApp();
  const { data, loading, error, reload } = useAsync(() => wecomBotsApi.list(), []);
  const [editing, setEditing] = useState<WecomBot | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const rows = data?.bots ?? [];

  const openCreate = () => {
    setEditing(null);
    setCreating(true);
    form.resetFields();
    form.setFieldsValue({ enabled: true });
  };

  const openEdit = (record: WecomBot) => {
    form.setFieldsValue({
      bot_id: record.bot_id,
      name: record.name,
      enabled: record.enabled,
      note: record.note ?? '',
    });
    setEditing(record);
    setCreating(false);
  };

  const closeEditor = () => {
    if (submitting) return;
    setEditing(null);
    setCreating(false);
    form.resetFields();
  };

  const submit = async () => {
    try {
      const values = await form.validateFields();
      const botId = editing ? editing.bot_id : String(values.bot_id).trim();
      const name = String(values.name).trim();
      const secretInput = typeof values.secret === 'string' ? values.secret.trim() : '';
      const note = typeof values.note === 'string' ? values.note.trim() : '';
      const enabled = typeof values.enabled === 'boolean' ? values.enabled : true;

      setSubmitting(true);
      await wecomBotsApi.upsert(botId, {
        name,
        // 留空 = 保持现值（新建时留空 = 暂不配置密钥），与京东 pin 的编辑交互先例一致
        secret: secretInput.length > 0 ? secretInput : undefined,
        enabled,
        note,
      });
      messageApi.success(creating ? `已登记机器人 ${botId}` : `已保存机器人 ${botId}`);
      setEditing(null);
      setCreating(false);
      form.resetFields();
      reload();
    } catch (submitError) {
      if (submitError instanceof Error) messageApi.error(errorMessage(submitError));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<WecomBot> = [
    {
      title: 'bot_id',
      dataIndex: 'bot_id',
      width: 220,
      ellipsis: true,
      render: (value: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</span>,
    },
    { title: '名称', dataIndex: 'name', width: 180 },
    {
      title: '密钥状态',
      dataIndex: 'secret_configured',
      width: 110,
      render: (value: boolean) => <AdminStatusTag status={value ? 'CONFIGURED' : 'UNCONFIGURED'} />,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 90,
      render: (value: boolean) => <AdminStatusTag status={value ? 'ACTIVE' : 'INACTIVE'} />,
    },
    {
      title: '备注',
      dataIndex: 'note',
      ellipsis: true,
      render: (value: string | null) => value || '—',
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right',
      render: (_, record) => <Typography.Link onClick={() => openEdit(record)}>编辑</Typography.Link>,
    },
  ];

  const viewState = adminPageState(loading, error, rows.length > 0);

  if (viewState === 'loading') {
    return (
      <div className="admin-page">
        <PageState state="loading" description="正在加载机器人登记簿…" />
      </div>
    );
  }

  if (viewState === 'error') {
    const presentation = adminFailurePresentation(error, '机器人登记簿加载失败');
    return (
      <div className="admin-page">
        <PageState state="error" message={presentation.title} description={presentation.description} onRetry={reload} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <PageShell
        title="机器人管理"
        description="登记会出现在企业微信通讯录里的 aibot 实例（bot_id / 密钥 / 启用状态）。"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建</Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="运行中的长连接凭据目前仍取自部署配置；此处登记的实例将随多机器人接线启用"
          description="当前生产实际在跑的机器人连接仍由 app.wecom.*（WECOM_BOT_ID / WECOM_SECRET 等环境变量）驱动，本页只做存储与登记，尚未接入运行时热切换。"
        />
        <div className="admin-surface">
          <DataTable<WecomBot>
            rowKey="bot_id"
            columns={columns}
            dataSource={rows}
            size="middle"
            scroll={{ x: 900 }}
            pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
            emptyText={<AdminEmpty description="暂无机器人登记" />}
          />
        </div>
      </PageShell>

      <Drawer
        title={creating ? '新建机器人' : `编辑机器人 · ${editing?.name ?? ''}`}
        open={creating || Boolean(editing)}
        onClose={closeEditor}
        width={420}
        destroyOnHidden
        extra={
          <Button type="primary" loading={submitting} onClick={() => void submit()}>
            保存
          </Button>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="bot_id"
            label="bot_id"
            extra="企微后台颁发的机器人标识，登记后不可更改"
            rules={[{ validator: validateBotId }]}
          >
            <Input placeholder="如 aibGvIuJDxrkgF-xxxxxxxx" disabled={!creating} />
          </Form.Item>
          <Form.Item
            name="name"
            label="名称"
            rules={[
              { required: true, message: '请填写名称' },
              { max: 128, message: '名称最长 128 个字符' },
              { whitespace: true, message: '名称不能只含空白' },
            ]}
          >
            <Input placeholder="便于识别的机器人名称" maxLength={128} />
          </Form.Item>
          <Form.Item
            name="secret"
            label="密钥 secret"
            extra="留空表示保持现有值；保存后只显示是否已配置，永不回显明文"
          >
            <Input.Password
              placeholder={
                creating
                  ? '未配置（可留空，稍后再补）'
                  : editing?.secret_configured
                    ? '已配置（不显示明文）'
                    : '未配置'
              }
            />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="note" label="备注" rules={[{ max: 500, message: '备注最长 500 个字符' }]}>
            <Input.TextArea placeholder="可选，如用途说明" maxLength={500} rows={3} />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
}
