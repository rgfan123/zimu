import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { channelMessagesApi, messageSubmissionsApi } from '@/api/endpoints';
import type { ChannelMessageSummary, MessageSubmissionDetail } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import {
  currentChannelMessageDetail,
  intentDisplay,
  interpretationVersionRows,
  safeChannelMessageRows,
  safeSubmissionSummary,
} from './channelMessageView';

export default function ChannelMessagesPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<ChannelMessageSummary | null>(null);
  const [reinterpreting, setReinterpreting] = useState(false);
  const [reinterpretError, setReinterpretError] = useState<Error | null>(null);
  const list = useAsync(() => channelMessagesApi.list({ page, size }), [page, size]);
  const detail = useAsync(
    () => (selected ? channelMessagesApi.detail(selected.id) : Promise.resolve(null)),
    [selected?.id],
  );
  const currentDetail = currentChannelMessageDetail(detail.data, selected?.id);
  const submissionId = currentDetail?.submission_id ?? null;
  const submission = useAsync(
    () => (submissionId ? messageSubmissionsApi.detail(submissionId) : Promise.resolve(null)),
    [submissionId],
  );

  const runReinterpret = async () => {
    if (!submissionId) return;
    setReinterpreting(true);
    setReinterpretError(null);
    try {
      await messageSubmissionsApi.reinterpret(submissionId);
      submission.reload();
    } catch (err) {
      setReinterpretError(err instanceof Error ? err : new Error(String(err)));
    } finally {
      setReinterpreting(false);
    }
  };

  const columns: ColumnsType<ChannelMessageSummary> = [
    { title: '接收时间', dataIndex: 'received_at', width: 190 },
    { title: '发送人', dataIndex: 'sender_user_id', width: 170 },
    { title: '业务群', dataIndex: 'chat_id', width: 190 },
    {
      title: '类型',
      dataIndex: 'message_type',
      width: 90,
      render: (value: string) => <Tag color="blue">{value === 'text' ? '文字' : value}</Tag>,
    },
    { title: '消息内容', dataIndex: 'content_preview', ellipsis: true },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, row) => <Typography.Link onClick={() => setSelected(row)}>详情</Typography.Link>,
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {list.error ? (
        <Alert
          type="error"
          showIcon
          message="消息记录加载失败"
          description={errorMessage(list.error)}
          action={<Button size="small" icon={<ReloadOutlined />} onClick={list.reload}>重试</Button>}
        />
      ) : null}
      <Card
        size="small"
        title="企业微信消息"
        extra={<Button icon={<ReloadOutlined />} onClick={list.reload}>刷新</Button>}
        styles={{ body: { padding: '4px 8px' } }}
      >
        <Table<ChannelMessageSummary>
          rowKey="id"
          columns={columns}
          dataSource={list.data?.items ?? []}
          loading={list.loading}
          scroll={{ x: 980 }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: list.data?.total_elements ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (nextPage, nextSize) => {
              setPage(nextPage - 1);
              setSize(nextSize);
            },
          }}
        />
      </Card>

      <Drawer title="消息证据详情" open={Boolean(selected)} onClose={() => setSelected(null)} width={720}>
        {detail.loading || (selected && !currentDetail && !detail.error) ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="加载中…" />
        ) : currentDetail ? (
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <Descriptions
              size="small"
              column={1}
              items={safeChannelMessageRows(currentDetail).map((row) => ({
                key: row.label,
                label: row.label,
                children: row.value,
              }))}
            />
            <div>
              <Typography.Text strong>消息原文</Typography.Text>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>
                {currentDetail.content}
              </Typography.Paragraph>
            </div>
            {currentDetail.quote_content ? (
              <div>
                <Typography.Text strong>引用内容</Typography.Text>
                <Typography.Paragraph type="secondary" style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>
                  {currentDetail.quote_content}
                </Typography.Paragraph>
              </div>
            ) : null}

            <SubmissionSection
              submission={submission.data}
              loading={submission.loading}
              error={submission.error ?? reinterpretError}
              reinterpreting={reinterpreting}
              onReinterpret={runReinterpret}
            />
          </Space>
        ) : detail.error ? (
          <Alert type="error" showIcon message="消息详情加载失败" description={errorMessage(detail.error)} />
        ) : null}
      </Drawer>
    </Space>
  );
}

const versionColumns: ColumnsType<ReturnType<typeof interpretationVersionRows>[number]> = [
  { title: '版本', dataIndex: 'version', width: 64 },
  { title: '意图', dataIndex: 'intent', width: 110 },
  { title: '模型', dataIndex: 'model', width: 130 },
  { title: '提示词版本', dataIndex: 'promptVersion', width: 130 },
  { title: '时间', dataIndex: 'createdAt', width: 190 },
  {
    title: '错误',
    dataIndex: 'error',
    render: (value: string | null) => (value ? <Tag color="red">{value}</Tag> : null),
  },
];

function SubmissionSection({
  submission,
  loading,
  error,
  reinterpreting,
  onReinterpret,
}: {
  submission: MessageSubmissionDetail | null;
  loading: boolean;
  error: unknown;
  reinterpreting: boolean;
  onReinterpret: () => void;
}) {
  if (loading) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="解释状态加载中…" />;
  }
  if (error) {
    return (
      <Alert type="error" showIcon message="解释状态加载失败" description={errorMessage(error)} />
    );
  }
  if (!submission) {
    return null;
  }
  const display = intentDisplay(submission.current_intent);
  const versions = interpretationVersionRows(submission);
  return (
    <Card
      size="small"
      title={
        <Space>
          <span>消息解释</span>
          <Tag color={display.intentColor}>{display.intentLabel}</Tag>
        </Space>
      }
      extra={
        <Button size="small" icon={<SyncOutlined />} loading={reinterpreting} onClick={onReinterpret}>
          重新解释
        </Button>
      }
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Descriptions
          size="small"
          column={2}
          items={safeSubmissionSummary(submission).map((row) => ({
            key: row.label,
            label: row.label,
            children: row.value,
          }))}
        />
        {versions.length > 0 ? (
          <Table
            rowKey="version"
            size="small"
            columns={versionColumns}
            dataSource={versions}
            pagination={false}
            scroll={{ x: 760 }}
          />
        ) : (
          <Typography.Text type="secondary">尚无解释版本（任务可能仍在排队）</Typography.Text>
        )}
      </Space>
    </Card>
  );
}
