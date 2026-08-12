import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Empty, Space, Table, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { channelMessagesApi } from '@/api/endpoints';
import type { ChannelMessageSummary } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { currentChannelMessageDetail, safeChannelMessageRows } from './channelMessageView';

export default function ChannelMessagesPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<ChannelMessageSummary | null>(null);
  const list = useAsync(() => channelMessagesApi.list({ page, size }), [page, size]);
  const detail = useAsync(
    () => (selected ? channelMessagesApi.detail(selected.id) : Promise.resolve(null)),
    [selected?.id],
  );
  const currentDetail = currentChannelMessageDetail(detail.data, selected?.id);

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

      <Drawer title="消息证据详情" open={Boolean(selected)} onClose={() => setSelected(null)} width={640}>
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
          </Space>
        ) : detail.error ? (
          <Alert type="error" showIcon message="消息详情加载失败" description={errorMessage(detail.error)} />
        ) : null}
      </Drawer>
    </Space>
  );
}
