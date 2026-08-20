/**
 * 部门协同 · 采购工单（GET /api/v1/procurement-tickets + 详情）。
 * 缺货时向采购部门发起的协同工单；可连续接收多个部分回执（append-only），
 * 直到缺口补齐、人工取消剩余量或失败转人工（CONTEXT.md 采购工单 / 采购回执）。
 * 本页为列表 + 详情展示（含回执回填结果），写操作（retry / cancel-remaining）不在本票范围。
 */

import { useMemo, useState } from 'react';
import { App as AntApp, Alert, Button, Descriptions, Drawer, Input, Modal, Select, Space, Table, Timeline, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { procurementApi } from '@/api/endpoints';
import type { ProcurementStatus, ProcurementTicket } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { AdminEmpty, AdminFailureAlert, AdminLoading, AdminStatusTag } from '@/pages/shared/AdminVisualComponents';
import { adminPageState, adminStatusPresentation } from '@/pages/shared/adminVisual';
import '@/pages/shared/adminSurface.css';

const PROCUREMENT_STATUSES: ProcurementStatus[] = ['PENDING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED'];

function num(v: string | number | undefined | null): string {
  if (v === undefined || v === null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v);
}

export default function ProcurementTicketsPage() {
  const { message: messageApi } = AntApp.useApp();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [status, setStatus] = useState<ProcurementStatus | undefined>();
  const [selected, setSelected] = useState<ProcurementTicket | null>(null);
  const [action, setAction] = useState<'retry' | 'cancel' | null>(null);
  const [actionNote, setActionNote] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const list = useAsync(() => procurementApi.list({ page, size, status }), [page, size, status]);
  const detail = useAsync<ProcurementTicket | null>(
    () => (selected ? procurementApi.detail(selected.id) : Promise.resolve(null)),
    [selected?.id],
  );
  const detailData = detail.data;

  const submitAction = async () => {
    if (!detailData || !action || !actionNote.trim()) return;
    setSubmitting(true);
    try {
      if (action === 'retry') await procurementApi.retry(detailData.id, detailData.version, actionNote.trim());
      else await procurementApi.cancelRemaining(detailData.id, detailData.version, actionNote.trim());
      messageApi.success(action === 'retry' ? '已创建重试工单' : '已取消剩余缺口');
      setAction(null);
      setActionNote('');
      setSelected(null);
      list.reload();
    } catch (err) {
      messageApi.error(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<ProcurementTicket> = [
    { title: '工单号', dataIndex: 'ticket_no', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '履约单', dataIndex: 'fulfillment_id', width: 180, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '状态', dataIndex: 'status', width: 112, render: (v: ProcurementStatus) => <AdminStatusTag status={v} /> },
    { title: '申请数量', dataIndex: 'requested_quantity', width: 100, align: 'right', render: num },
    { title: '已到货', dataIndex: 'fulfilled_quantity', width: 100, align: 'right', render: num },
    { title: '剩余缺口', dataIndex: 'remaining_quantity', width: 100, align: 'right', render: num },
    {
      title: '回执数',
      key: 'receipts',
      width: 80,
      align: 'right',
      render: (_, r) => r.receipts?.length ?? 0,
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      width: 170,
      render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span>,
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right',
      render: (_, r) => <Typography.Link onClick={() => setSelected(r)}>详情</Typography.Link>,
    },
  ];

  const tickets = useMemo(() => list.data?.items ?? [], [list.data]);
  const listState = adminPageState(list.loading, list.error, tickets.length > 0);
  const detailState = adminPageState(detail.loading, detail.error, Boolean(detailData));

  if (listState === 'loading') {
    return <div className="admin-page"><AdminLoading description="正在加载采购工单…" /></div>;
  }

  if (listState === 'error') {
    return (
      <div className="admin-page">
        <AdminFailureAlert error={list.error} title="采购工单加载失败" onRetry={list.reload} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <PageShell
        title="采购协同"
        description="查看缺货补齐进度与不可变回执，仅对待处理缺口执行重试或取消。"
      >
        <FilterBar
          actions={<Button icon={<ReloadOutlined />} onClick={list.reload}>刷新</Button>}
        >
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>状态</Typography.Text>
          <Select style={{ width: 150 }} placeholder="全部" allowClear value={status} onChange={setStatus}
            options={PROCUREMENT_STATUSES.map((key) => ({ value: key, label: adminStatusPresentation(key).label }))} />
        </FilterBar>

        <div className="admin-surface">
          <DataTable<ProcurementTicket>
            rowKey="id"
            columns={columns}
            dataSource={tickets}
            size="middle"
            scroll={{ x: 1080 }}
            emptyText={<AdminEmpty description="暂无采购工单" />}
            pagination={{
              current: page + 1,
              pageSize: size,
              total: list.data?.total_elements ?? 0,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p, s) => {
                setPage(p - 1);
                setSize(s);
              },
            }}
          />
        </div>
      </PageShell>

      <Drawer
        title={`采购工单 ${selected?.ticket_no ?? ''}`}
        open={Boolean(selected)}
        onClose={() => setSelected(null)}
        width={640}
        styles={{ body: { padding: '16px 20px' } }}
      >
        {detailState === 'loading' ? (
          <AdminLoading description="正在加载采购工单详情…" />
        ) : detailState === 'error' ? (
          <AdminFailureAlert error={detail.error} title="采购工单详情加载失败" onRetry={detail.reload} />
        ) : detailData ? (
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <Descriptions
              size="small"
              column={2}
              items={[
                { key: 'f', label: '履约单', children: detailData.fulfillment_id },
                { key: 's', label: '状态', children: <AdminStatusTag status={detailData.status} /> },
                { key: 'r', label: '申请数量', children: num(detailData.requested_quantity) },
                { key: 'f2', label: '已到货', children: num(detailData.fulfilled_quantity) },
                { key: 'rm', label: '剩余缺口', children: num(detailData.remaining_quantity) },
                { key: 'v', label: '版本', children: detailData.version },
              ]}
            />
            {detailData.retry_of_ticket_id ? (
              <Alert type="info" showIcon message={`重试自工单 ${detailData.retry_of_ticket_id}`} />
            ) : null}

            <Space>
              {detailData.status === 'FAILED' ? (
                <Button type="primary" onClick={() => setAction('retry')}>重试采购</Button>
              ) : null}
              {Number(detailData.remaining_quantity) > 0 && ['PENDING', 'PARTIAL', 'FAILED'].includes(detailData.status) ? (
                <Button danger onClick={() => setAction('cancel')}>取消剩余缺口</Button>
              ) : null}
            </Space>

            <div className="admin-detail-section">
              <Typography.Text className="admin-detail-section__heading" strong>条目（{detailData.items?.length ?? 0}）</Typography.Text>
              <Table
                rowKey="id"
                size="small"
                style={{ marginTop: 8 }}
                pagination={false}
                columns={[
                  { title: 'SKU', dataIndex: 'sku_id', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
                  {
                    title: '礼包组件',
                    dataIndex: 'component_sku_id',
                    width: 170,
                    render: (v?: string) => (v ? <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> : '—'),
                  },
                  { title: '申请', dataIndex: 'requested_quantity', align: 'right', render: num },
                  { title: '已到货', dataIndex: 'fulfilled_quantity', align: 'right', render: num },
                  { title: '剩余', dataIndex: 'remaining_quantity', align: 'right', render: num },
                ]}
                dataSource={detailData.items ?? []}
                locale={{ emptyText: <AdminEmpty description="暂无条目" /> }}
              />
            </div>

            <div className="admin-detail-section">
              <Typography.Text className="admin-detail-section__heading" strong>结果回填 · 采购回执（{detailData.receipts?.length ?? 0}，不可变更）</Typography.Text>
              <Timeline
                style={{ marginTop: 14 }}
                items={(detailData.receipts ?? []).map((receipt) => ({
                  color: adminStatusPresentation(receipt.result).color,
                  children: (
                    <div>
                      <Space wrap style={{ marginBottom: 4 }}>
                        <Typography.Text strong style={{ fontSize: 13 }}>
                          {receipt.receipt_no}
                        </Typography.Text>
                        <AdminStatusTag status={receipt.result} />
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {receipt.received_by} · {receipt.received_at}
                        </Typography.Text>
                      </Space>
                      <div className="admin-receipt-copy">
                        {(receipt.items ?? []).map((it) => (
                          <div key={it.ticket_item_id} style={{ fontVariantNumeric: 'tabular-nums' }}>
                            条目 {it.ticket_item_id}：本次可用 {num(it.available_quantity)}
                          </div>
                        ))}
                        {receipt.expected_ship_time ? (
                          <div className="admin-receipt-meta">预计到货：{receipt.expected_ship_time}</div>
                        ) : null}
                        {receipt.source_ref ? (
                          <div className="admin-receipt-meta">来源引用：{receipt.source_ref}</div>
                        ) : null}
                        {receipt.remark ? <div className="admin-receipt-meta">备注：{receipt.remark}</div> : null}
                      </div>
                    </div>
                  ),
                }))}
              />
            </div>
          </Space>
        ) : null}
      </Drawer>

      <Modal
        title={action === 'retry' ? '重试采购工单' : '取消剩余缺口'}
        open={Boolean(action)}
        okText="确认提交"
        cancelText="返回"
        okButtonProps={{ danger: action === 'cancel', disabled: submitting || !actionNote.trim() }}
        cancelButtonProps={{ disabled: submitting }}
        confirmLoading={submitting}
        onOk={submitAction}
        closable={!submitting}
        maskClosable={!submitting}
        keyboard={!submitting}
        onCancel={() => {
          if (submitting) return;
          setAction(null);
          setActionNote('');
        }}
      >
        <Typography.Paragraph type="secondary">
          {action === 'retry' ? '系统会保留原工单并创建关联重试工单。' : '仅取消尚未补齐的数量，已经发生的到货和发货事实不会回滚。'}
        </Typography.Paragraph>
        <Input.TextArea
          rows={4}
          maxLength={1000}
          showCount
          value={actionNote}
          onChange={(event) => setActionNote(event.target.value)}
          placeholder="请输入处理依据"
        />
      </Modal>
    </div>
  );
}
