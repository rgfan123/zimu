/**
 * 会话回复策略：同一个机器人在不同业务场景要有不同的嘴。
 *
 * 客户群 = 静默收单：机器人不插「已接收」、不在群里追问缺失信息；
 * 文件回执、回填文件、发货清单等业务投递不受影响（那正是群要的东西）。
 * 个人助手 = 自由回复：应答、追问、提醒缺什么，全开。
 * 缺省即自由回复——配置永远是收紧，不会静默你没点过的会话。
 */

import { useState } from 'react';
import { App as AntApp, Alert, Switch, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { wecomChatsApi } from '@/api/endpoints';
import type { KnownWecomChat } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import { PageState } from '@/pages/shared/PageState';

export default function ReplyPolicyPage() {
  const { message: messageApi } = AntApp.useApp();
  const { data, loading, error, reload } = useAsync(() => wecomChatsApi.list(), []);
  const [saving, setSaving] = useState<string | null>(null);

  const toggle = async (chat: KnownWecomChat, silent: boolean) => {
    setSaving(chat.chat_id);
    try {
      await wecomChatsApi.setReplyPolicy(chat.chat_id, silent ? 'RECEIPTS_ONLY' : 'FULL');
      messageApi.success(
        silent ? `已静默：${chat.chat_id} 只发回执与业务文件` : `已恢复自由回复：${chat.chat_id}`,
      );
      reload();
    } catch (toggleError) {
      if (toggleError instanceof Error) messageApi.error(errorMessage(toggleError));
    } finally {
      setSaving(null);
    }
  };

  const columns: ColumnsType<KnownWecomChat> = [
    {
      title: '会话',
      dataIndex: 'chat_id',
      render: (value: string, record) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {value}
          {record.label ? <Typography.Text type="secondary">（{record.label}）</Typography.Text> : null}
        </span>
      ),
    },
    {
      title: '类型',
      dataIndex: 'chat_type',
      width: 100,
      render: (value: KnownWecomChat['chat_type']) =>
        value === 'group' ? <Tag color="blue">群聊</Tag> : <Tag>单聊</Tag>,
    },
    {
      title: '最近活跃',
      dataIndex: 'last_seen_at',
      width: 160,
      render: (value: string | null) => (value ? value.slice(0, 10) : '—'),
    },
    {
      title: '静默收单',
      dataIndex: 'reply_mode',
      width: 180,
      render: (_, record) => (
        <Switch
          checked={record.reply_mode === 'RECEIPTS_ONLY'}
          loading={saving === record.chat_id}
          checkedChildren="静默"
          unCheckedChildren="自由回复"
          onChange={(checked) => void toggle(record, checked)}
        />
      ),
    },
  ];

  if (loading) return <PageState state="loading" description="正在加载会话目录…" />;
  if (error) {
    return <PageState state="error" message="会话目录加载失败" description={errorMessage(error)} onRetry={reload} />;
  }

  return (
    <PageShell
      title="会话回复策略"
      description="按会话控制机器人的嘴：客户群静默收单（只发回执与业务文件），个人助手自由应答并追问缺失信息。"
    >
      <Alert
        showIcon
        type="info"
        message="静默只关「对话性」回复：泛回执「已接收」与群内追问卡不再出现；文件回执、回填文件、发货清单、确认卡照发。缺省全部自由回复。"
      />
      <Table<KnownWecomChat>
        rowKey="chat_id"
        columns={columns}
        dataSource={data?.chats ?? []}
        size="middle"
        pagination={false}
        locale={{ emptyText: '机器人还没见过任何会话；把它拉进群并发一条消息后刷新' }}
      />
    </PageShell>
  );
}
