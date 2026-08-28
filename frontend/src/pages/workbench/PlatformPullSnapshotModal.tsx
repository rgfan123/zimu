import { Alert, Button, Modal, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fileOperationsApi } from '@/api/endpoints';
import { presentImportRow, type ImportRowView } from '../fulfillment/fileOperations';
import { importRowStatusSemantic } from '../shared/semanticStatus';
import type { ShippingChannelView } from './shippingPresentation';

const SNAPSHOT_PAGE_SIZE = 20;
const PULL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

interface SnapshotRow extends ImportRowView {
  sequence: number;
}

interface SnapshotPage {
  rows: ImportRowView[];
  totalElements: number;
}

interface PlatformPullSnapshotModalProps {
  channel: ShippingChannelView;
  dateBegin?: string;
  dateEnd?: string;
  onClose: () => void;
}

function pullWindow(dateBegin?: string, dateEnd?: string): string | null {
  const begin = dateBegin && PULL_DATE_PATTERN.test(dateBegin) ? dateBegin : null;
  const end = dateEnd && PULL_DATE_PATTERN.test(dateEnd) ? dateEnd : null;
  if (!begin && !end) return null;
  if (!begin) return end;
  if (!end || begin === end) return begin;
  return `${begin} 至 ${end}`;
}

function rowStatusLabel(status: ImportRowView['status']): string {
  if (status === 'REJECTED') return '已拒绝';
  if (status === 'NEED_REVIEW') return '待复核';
  return '已接收';
}

export default function PlatformPullSnapshotModal({
  channel,
  dateBegin,
  dateEnd,
  onClose,
}: PlatformPullSnapshotModalProps) {
  const [page, setPage] = useState(0);
  const [retryKey, setRetryKey] = useState(0);
  const [loading, setLoading] = useState(Boolean(channel.batchId));
  const [error, setError] = useState(false);
  const [response, setResponse] = useState<SnapshotPage | null>(null);
  const windowText = pullWindow(dateBegin, dateEnd);

  useEffect(() => {
    if (!channel.batchId) return undefined;

    let cancelled = false;
    setLoading(true);
    setError(false);
    fileOperationsApi.getSourceRows(channel.batchId, { page, size: SNAPSHOT_PAGE_SIZE })
      .then((next) => {
        if (!cancelled) {
          setResponse({
            // Project at the API boundary so raw_cells never enter table state or rendering.
            rows: next.items.map(presentImportRow),
            totalElements: next.total_elements,
          });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setResponse(null);
          setError(true);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [channel.batchId, page, retryKey]);

  const rows: SnapshotRow[] = (response?.rows ?? []).map((row, index) => ({
    ...row,
    sequence: page * SNAPSHOT_PAGE_SIZE + index + 1,
  }));

  const summary = channel.rowCounts
    ? `共 ${channel.rowCounts.total} 行 · 已接收 ${channel.rowCounts.accepted} · 待复核 ${channel.rowCounts.need_review} · 拒绝 ${channel.rowCounts.rejected}`
    : null;

  return (
    <Modal
      title={`${channel.label} · ${channel.batchId ? '批次快照' : '拉取快照'}`}
      open
      width={1100}
      onCancel={onClose}
      destroyOnHidden
      footer={(
        <div className="zs-pull-modal-actions">
          <Button onClick={onClose}>关闭</Button>
          {channel.destination ? (
            <Link to={channel.destination}>去文件作业页确认整批</Link>
          ) : null}
        </div>
      )}
    >
      <div className="zs-pull-modal-body">
        <div className="zs-pull-modal-summary">
          <strong>{channel.label}</strong>
          {channel.batchNo ? <span>批次 {channel.batchNo}</span> : null}
          {summary ? <span>{summary}</span> : null}
          {windowText ? <span className="muted">拉取窗口 {windowText}</span> : null}
        </div>

        {channel.reportOnly ? (
          <Alert
            type="warning"
            showIcon
            message={channel.orderCount == null
              ? 'JSON 直连拉取数量暂不可用'
              : `JSON 直连拉到 ${channel.orderCount} 单`}
            description={(
              <div className="zs-pull-modal-next">
                <span>来源缺少收货人字段，本次未生成导入批次。</span>
                <Link to="/fulfillment/sales-outbound">去文件作业页上传 Excel 补录</Link>
              </div>
            )}
          />
        ) : channel.batchId ? (
          loading ? (
            <p className="zs-pull-modal-loading">正在加载批次明细…</p>
          ) : error ? (
            <Alert
              type="error"
              showIcon
              message="批次明细加载失败"
              description="请重试加载当前页。"
              action={<Button size="small" onClick={() => setRetryKey((value) => value + 1)}>重试加载</Button>}
            />
          ) : (
            <Table<SnapshotRow>
              rowKey="id"
              size="small"
              dataSource={rows}
              scroll={{ x: 1420, y: 420 }}
              locale={{ emptyText: '该批次暂无明细行' }}
              pagination={{
                current: page + 1,
                pageSize: SNAPSHOT_PAGE_SIZE,
                total: response?.totalElements ?? 0,
                showSizeChanger: false,
                showTotal: (total) => `共 ${total} 行`,
                onChange: (nextPage) => setPage(nextPage - 1),
              }}
              columns={[
                { title: '序号', dataIndex: 'sequence', width: 70 },
                { title: '渠道单号', dataIndex: 'sourceOrderRef', width: 160 },
                {
                  title: '收件人',
                  dataIndex: 'receiverName',
                  width: 110,
                  render: (value: string) => value || '—',
                },
                {
                  title: '电话',
                  dataIndex: 'receiverPhone',
                  width: 130,
                  render: (value: string) => value || '—',
                },
                {
                  title: '收货地址',
                  dataIndex: 'receiverAddress',
                  width: 260,
                  render: (value: string) => value || '—',
                },
                {
                  title: '商品',
                  dataIndex: 'productName',
                  width: 240,
                  render: (value: string) => value || '—',
                },
                {
                  title: '件数',
                  dataIndex: 'quantity',
                  width: 80,
                  render: (value: string) => value || '—',
                },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 100,
                  render: (status: ImportRowView['status']) => (
                    <Tag color={importRowStatusSemantic(status)}>{rowStatusLabel(status)}</Tag>
                  ),
                },
                { title: '处理结果', dataIndex: 'reason', width: 250 },
              ]}
            />
          )
        ) : (
          <Alert
            type={channel.status === 'FAILED' || channel.status === 'CONTRACT_ERROR' ? 'error' : 'info'}
            showIcon
            message={channel.message ?? '本次未生成导入批次'}
            description="当前没有可展示的批次明细。"
          />
        )}
      </div>
    </Modal>
  );
}
