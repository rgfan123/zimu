import { Alert, Button, Modal, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fileOperationsApi, ordersApi, reviewCasesApi } from '@/api/endpoints';
import { reasonLabel } from '@/constants/reasonLabels';
import { presentImportRow, type ImportRowView } from '../fulfillment/fileOperations';
import { importRowStatusSemantic } from '../shared/semanticStatus';
import {
  missingFactLabel, presentSnapshotRowFacts, reviewReasonFor, snapshotOrderIdsToLoad,
  type SnapshotOrderSource, type SnapshotReviewCase, type SnapshotRowFacts,
} from './pullSnapshotRows';
import type { ShippingChannelView } from './shippingPresentation';

const SNAPSHOT_PAGE_SIZE = 20;
const PULL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
/** 整批复核事项一次拉回（用于「这一行为什么待复核」）；批次行数远小于此上限。 */
const REVIEW_CASE_FETCH_SIZE = 200;

interface SnapshotRow extends ImportRowView {
  sequence: number;
  /** 订单实体优先的事实（见 pullSnapshotRows 头注释）。 */
  facts: SnapshotRowFacts;
}

interface SnapshotPage {
  rows: ImportRowView[];
  totalElements: number;
}

/** 缺值统一走这里：没建单说「未建单」，建了单但这项空着说「未提供」——不给破折号。 */
function factText(value: string | null, facts: SnapshotRowFacts) {
  return value ?? <span className="muted">{missingFactLabel(facts)}</span>;
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
  // order_id → 订单实体。快照刻意不含 PII（它会进企微卡片），真值只能从订单实体读。
  const [orders, setOrders] = useState<Record<string, SnapshotOrderSource>>({});
  // 整批复核事项，用于回答「这一行为什么待复核」（一次请求，不是逐行）。
  const [reviewCases, setReviewCases] = useState<SnapshotReviewCase[]>([]);
  const windowText = pullWindow(dateBegin, dateEnd);

  useEffect(() => {
    const batchId = channel.batchId;
    if (!batchId) return undefined;

    let cancelled = false;
    setLoading(true);
    setError(false);
    setOrders({});
    setReviewCases([]);
    fileOperationsApi.getSourceRows(batchId, { page, size: SNAPSHOT_PAGE_SIZE })
      .then(async (next) => {
        if (cancelled) return;
        // Project at the API boundary so raw_cells never enter table state or rendering.
        const rows = next.items.map(presentImportRow);
        setResponse({ rows, totalElements: next.total_elements });
        setLoading(false);

        // 事实补齐是**渐进增强**：失败只让个别列退回「未提供」，不把整张表打成错误态。
        // 逐单拉详情（订单列表没有 import_batch_id 过滤，且列表口径不含商品行）；
        // 按 order_id 去重后，一单多商品只拉一次，且一页固定 20 行天然有上界。
        const loaded = await Promise.all(
          snapshotOrderIdsToLoad(rows).map(async (orderId) => {
            try {
              return [orderId, await ordersApi.detail(orderId) as SnapshotOrderSource] as const;
            } catch {
              return null;
            }
          }),
        );
        if (!cancelled) {
          setOrders(Object.fromEntries(loaded.filter((entry): entry is NonNullable<typeof entry> => entry !== null)));
        }

        const cases = await reviewCasesApi
          .list({ import_batch_id: batchId, page: 0, size: REVIEW_CASE_FETCH_SIZE })
          .catch(() => null);
        if (!cancelled && cases) setReviewCases(cases.items as SnapshotReviewCase[]);
      })
      .catch(() => {
        if (!cancelled) {
          setResponse(null);
          setError(true);
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [channel.batchId, page, retryKey]);

  const rows: SnapshotRow[] = (response?.rows ?? []).map((row, index) => ({
    ...row,
    sequence: page * SNAPSHOT_PAGE_SIZE + index + 1,
    facts: presentSnapshotRowFacts(
      row,
      row.orderId ? orders[row.orderId] ?? null : null,
      reviewReasonFor(row, reviewCases),
    ),
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
              // 快照不再是主数据源，但仍是「平台原始返回」的证据留档——收进折叠区，可查不碍眼。
              expandable={{
                expandedRowRender: (row: SnapshotRow) => (
                  <div className="zs-pull-modal-evidence">
                    <div>
                      <strong>平台原始返回（快照口径，收件人已脱敏）</strong>
                      <span className="muted">
                        {' '}第 {row.row} 行 · {row.sheet}
                        {row.sourceSkuRef !== '—' ? ` · 来源商品编码 ${row.sourceSkuRef}` : ''}
                      </span>
                    </div>
                    <div>
                      商品 {row.sourceProductName} · 件数 {row.quantity} · 规格 {row.specification}
                    </div>
                    <div>
                      {row.facts.orderId ? (
                        <Link to={`/orders/${row.facts.orderId}`}>
                          查看系统订单{row.facts.orderNo ? ` ${row.facts.orderNo}` : ''}
                        </Link>
                      ) : (
                        <span className="muted">该行未建单，无系统订单可查</span>
                      )}
                    </div>
                  </div>
                ),
              }}
              columns={[
                { title: '序号', dataIndex: 'sequence', width: 70 },
                { title: '渠道单号', dataIndex: 'sourceOrderRef', width: 160 },
                {
                  title: '收件人',
                  dataIndex: 'receiverName',
                  width: 110,
                  render: (_: unknown, row: SnapshotRow) => factText(row.facts.receiverName, row.facts),
                },
                {
                  title: '电话',
                  dataIndex: 'receiverPhone',
                  width: 130,
                  render: (_: unknown, row: SnapshotRow) => factText(row.facts.receiverPhone, row.facts),
                },
                {
                  title: '收货地址',
                  dataIndex: 'receiverAddress',
                  width: 260,
                  render: (_: unknown, row: SnapshotRow) => factText(row.facts.receiverAddress, row.facts),
                },
                {
                  title: '商品',
                  dataIndex: 'productName',
                  width: 240,
                  render: (_: unknown, row: SnapshotRow) => factText(row.facts.productName, row.facts),
                },
                {
                  title: '件数',
                  dataIndex: 'quantity',
                  width: 80,
                  render: (_: unknown, row: SnapshotRow) => factText(row.facts.quantity, row.facts),
                },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 100,
                  render: (status: ImportRowView['status'], row: SnapshotRow) => (
                    <>
                      <Tag color={importRowStatusSemantic(status)}>{rowStatusLabel(status)}</Tag>
                      {/* 只给状态计数说明不了问题——把这一行「为什么」待复核也摆出来。 */}
                      {row.facts.reviewReasonCode ? (
                        <div className="muted">{reasonLabel(row.facts.reviewReasonCode)}</div>
                      ) : null}
                    </>
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
