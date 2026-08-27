/**
 * 会话管理：机器人所在的每个会话（群聊/私聊）一行——
 * 起备注名（企微协议不下发群名，帧里只有 chatid，名字只能人起）、
 * 绑定服务该会话的 Agent、控制回复权限。
 *
 * 回复权限两档：
 * - 自由回复（缺省）：应答、追问、提醒缺失信息；
 * - 仅业务消息：只发文件回执、回填文件、发货清单与确认卡，不闲聊、不追问——客户群用这档。
 */

import { useMemo, useState } from 'react';
import {
  App as AntApp,
  Alert,
  Button,
  Drawer,
  Form,
  Input,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { agentsApi, wecomChatsApi } from '@/api/endpoints';
import type { KnownWecomChat } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import { PageState } from '@/pages/shared/PageState';

const MODE_LABELS: Record<KnownWecomChat['reply_mode'], string> = {
  FULL: '自由回复',
  RECEIPTS_ONLY: '仅业务消息',
};

export default function ReplyPolicyPage() {
  const { message: messageApi } = AntApp.useApp();
  const { data, loading, error, reload } = useAsync(() => wecomChatsApi.list(), []);
  // Agent 目录加载失败不挡会话管理：绑定选择降级为暂不可选
  const { data: agentList } = useAsync(
    () => agentsApi.list().catch(() => null),
    [],
  );
  const [editing, setEditing] = useState<KnownWecomChat | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const agentOptions = useMemo(
    () =>
      (agentList?.items ?? []).map((agent) => ({
        value: agent.slug,
        label: `${agent.name}（${agent.slug}）`,
      })),
    [agentList],
  );

  const chatName = (chat: KnownWecomChat) => chat.display_name ?? chat.label ?? chat.chat_id;

  const openEditor = (chat: KnownWecomChat) => {
    form.setFieldsValue({
      display_name: chat.display_name ?? '',
      agent_slug: chat.agent_slug ?? undefined,
      reply_mode: chat.reply_mode,
    });
    setEditing(chat);
  };

  const closeEditor = () => {
    if (saving) return;
    setEditing(null);
    form.resetFields();
  };

  const submit = async () => {
    if (!editing) return;
    try {
      const values = await form.validateFields();
      setSaving(true);
      await wecomChatsApi.setProfile(editing.chat_id, {
        reply_mode: values.reply_mode,
        // 空串 = 清除备注/解绑 Agent（后端契约：null 不动、空串清除）
        display_name: typeof values.display_name === 'string' ? values.display_name.trim() : '',
        agent_slug: typeof values.agent_slug === 'string' ? values.agent_slug : '',
      });
      messageApi.success(`已保存：${values.display_name?.trim() || editing.chat_id}`);
      setEditing(null);
      form.resetFields();
      reload();
    } catch (submitError) {
      if (submitError instanceof Error) messageApi.error(errorMessage(submitError));
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<KnownWecomChat> = [
    {
      title: '会话',
      dataIndex: 'chat_id',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{chatName(record)}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>
            {record.chat_id}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'chat_type',
      width: 90,
      render: (value: KnownWecomChat['chat_type']) =>
        value === 'group' ? <Tag color="blue">群聊</Tag> : <Tag>私聊</Tag>,
    },
    {
      title: '最近活跃',
      dataIndex: 'last_seen_at',
      width: 120,
      render: (value: string | null) => (value ? value.slice(0, 10) : '—'),
    },
    {
      title: '服务 Agent',
      dataIndex: 'agent_slug',
      width: 180,
      render: (value: string | null) => (value ? <Tag color="purple">{value}</Tag> : '—'),
    },
    {
      title: '回复权限',
      dataIndex: 'reply_mode',
      width: 130,
      render: (value: KnownWecomChat['reply_mode']) =>
        value === 'RECEIPTS_ONLY' ? (
          <Tag color="orange">{MODE_LABELS[value]}</Tag>
        ) : (
          <Tag color="green">{MODE_LABELS[value]}</Tag>
        ),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => <Typography.Link onClick={() => openEditor(record)}>设置</Typography.Link>,
    },
  ];

  if (loading) return <PageState state="loading" description="正在加载会话目录…" />;
  if (error) {
    return <PageState state="error" message="会话目录加载失败" description={errorMessage(error)} onRetry={reload} />;
  }

  return (
    <PageShell
      title="会话管理"
      description="机器人所在的每个会话：起名、绑定服务 Agent、控制回复权限。把机器人拉进新群并发一条消息，刷新即出现在这里。"
    >
      <Alert
        showIcon
        type="info"
        message="企微协议不下发群名（只有 chatid），会话名称需要在这里起一次备注名。「仅业务消息」只关掉闲聊应答与群内追问；文件回执、回填文件、发货清单、确认卡照发。"
      />
      <Table<KnownWecomChat>
        rowKey="chat_id"
        columns={columns}
        dataSource={data?.chats ?? []}
        size="middle"
        pagination={false}
        locale={{ emptyText: '机器人还没见过任何会话；把它拉进群并发一条消息后刷新' }}
      />

      <Drawer
        title={`设置会话 · ${editing ? chatName(editing) : ''}`}
        open={Boolean(editing)}
        onClose={closeEditor}
        width={420}
        destroyOnHidden
        extra={
          <Button type="primary" loading={saving} onClick={() => void submit()}>
            保存
          </Button>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="display_name"
            label="会话备注名"
            extra="给这个会话起个人能看懂的名字，如「中汇客户群」「老板私聊」；留空保存即清除"
            rules={[{ max: 128, message: '备注名最长 128 字' }]}
          >
            <Input placeholder={editing?.label ? `默认显示：${editing.label}` : '如：中汇客户群'} allowClear />
          </Form.Item>
          <Form.Item
            name="agent_slug"
            label="服务 Agent"
            extra="该会话由哪个 Agent 人格服务；分流路由随 Agent 平台推进接线，先在这里定归属"
          >
            <Select
              options={agentOptions}
              allowClear
              placeholder={agentOptions.length > 0 ? '选择 Agent（可留空）' : '暂无可选 Agent'}
              disabled={agentOptions.length === 0}
            />
          </Form.Item>
          <Form.Item name="reply_mode" label="回复权限" rules={[{ required: true }]}>
            <Radio.Group>
              <Space direction="vertical" size={8}>
                <Radio value="FULL">
                  <Typography.Text strong>自由回复</Typography.Text>
                  <br />
                  <Typography.Text type="secondary">应答、追问、提醒还缺什么信息——个人助手用这档</Typography.Text>
                </Radio>
                <Radio value="RECEIPTS_ONLY">
                  <Typography.Text strong>仅业务消息</Typography.Text>
                  <br />
                  <Typography.Text type="secondary">
                    只发文件回执、回填文件、发货清单与确认卡；不闲聊、不在群里追问——客户群用这档
                  </Typography.Text>
                </Radio>
              </Space>
            </Radio.Group>
          </Form.Item>
        </Form>
      </Drawer>
    </PageShell>
  );
}
