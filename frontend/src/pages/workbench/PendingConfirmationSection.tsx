/**
 * 待确认批次区（确认发货入口）。
 *
 * 为什么在这里：此前批次只能按 id 打开——上传完当场拿到，或手工拼
 * `?import_batch=` 链接。昨天没确认完的批次在界面上找不到，确认发货实际只能靠
 * 企业微信卡片触发，工作台是每天第一眼看的页面，待办就该出现在这。
 *
 * 明细仍在文件作业页：这里只做「看到 + 确认」，逐行核对点批次号过去。
 */

import { useState } from 'react';
import { Button, Popconfirm, Tooltip, message } from 'antd';
import { Link } from 'react-router-dom';
import { fileOperationsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAsync } from '@/hooks/useAsync';
import { formatDateTime } from '@/format/dateTime';
import type { ConfirmBlockedRow, PendingConfirmationBatch } from '@/api/types';
import { confirmScopeHint, groupBlockedRows } from '../fulfillment/fileOperations';
import { fileJobUrlForBatch } from '../shared/batchUrl';

/** 确认后的结果提示：发了多少、跳过多少，跳过的怎么补。 */
function confirmedMessage(skipped: ConfirmBlockedRow[]): string {
  if (skipped.length === 0) return '本批次已确认，履约文件已生成';
  return `本批次已确认；${skipped.length} 行因待处理被跳过，仍留在批次里，处理完后可再次确认补做`;
}

function BatchRow({ batch, onConfirmed }: { batch: PendingConfirmationBatch; onConfirmed: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const [expanded, setExpanded] = useState(false);

  // 阻断原因只在展开时取：清单接口只带计数，原因在批次详情的 confirm_readiness 里。
  const detail = useAsync(
    async () => (expanded ? fileOperationsApi.getSourceBatch(batch.id) : null),
    [expanded, batch.id],
  );
  const blockers = detail.data?.confirm_readiness?.blockers ?? [];

  const reconfirm = Boolean(batch.confirmed_at);
  const scopeHint = confirmScopeHint({
    ready_rows: batch.ready_rows,
    pending_rows: batch.pending_rows,
    blocked_rows: batch.blocked_rows,
    benign_skipped_rows: batch.benign_skipped_rows,
    confirmable: batch.confirmable,
    partial: batch.partial,
    blockers: [],
  });

  const confirm = async () => {
    setConfirming(true);
    try {
      const confirmed = reconfirm
        ? await fileOperationsApi.reconfirmSourceBatch(batch.id)
        : await fileOperationsApi.confirmSourceBatch(batch.id);
      message.success(confirmedMessage(confirmed.skipped_rows ?? []));
      onConfirmed();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setConfirming(false);
    }
  };

  const label = reconfirm ? `补做确认（${batch.pending_rows} 行）` : `确认发货（${batch.pending_rows} 行）`;
  const disabledReason = batch.confirmable
    ? ''
    : batch.blocked_rows > 0
      ? `${batch.blocked_rows} 行待处理，且没有可发货的行`
      : '批次没有可发货的已接收行';

  return (
    <div className="zs-rqi">
      <div className="zs-w">
        <div className="zs-l1">
          <Link to={fileJobUrlForBatch(batch.id)}>{batch.batch_no}</Link>
          {batch.source_channel_display_name ? (
            <span className="zs-tag">{batch.source_channel_display_name}</span>
          ) : null}
          {batch.blocked_rows > 0 ? <span className="zs-tag err">{batch.blocked_rows} 行待处理</span> : null}
          {reconfirm ? <span className="zs-tag">已确认过</span> : null}
        </div>
        <div className="zs-l2">
          共 {batch.total_rows} 行 · 已就绪 {batch.ready_rows} 行 · 待发货 {batch.pending_rows} 行
          {batch.benign_skipped_rows > 0 ? ` · 重复跳过 ${batch.benign_skipped_rows} 行` : ''}
          {' · '}
          {formatDateTime(batch.received_at)}
          {batch.original_file_name ? ` · ${batch.original_file_name}` : ''}
        </div>
        {batch.blocked_rows > 0 ? (
          <div className="zs-l2">
            <button type="button" className="zs-lnk" onClick={() => setExpanded((open) => !open)}>
              {expanded ? '收起待处理原因' : '看待处理原因'}
            </button>
            {expanded && detail.loading ? <span className="zs-muted"> 正在加载…</span> : null}
            {expanded && detail.error ? (
              <span className="zs-muted"> 加载失败：{errorMessage(detail.error)}</span>
            ) : null}
            {expanded && !detail.loading && !detail.error ? (
              <div className="zs-muted">
                {groupBlockedRows(blockers).map((group) => (
                  <div key={group.reason}>
                    {group.reason} · {group.count} 行
                    {group.sampleRefs.length > 0 ? `（${group.sampleRefs.join('、')}…）` : ''}
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
      <div className="zs-a">
        <Tooltip title={disabledReason || undefined}>
          <span>
            <Popconfirm
              title={reconfirm ? '补做确认' : '确认发货'}
              description={scopeHint}
              okText={label}
              cancelText="取消"
              disabled={!batch.confirmable || confirming}
              onConfirm={confirm}
            >
              <Button type="primary" size="small" loading={confirming} disabled={!batch.confirmable}>
                {label}
              </Button>
            </Popconfirm>
          </span>
        </Tooltip>
      </div>
    </div>
  );
}

export default function PendingConfirmationSection() {
  const pending = useAsync(() => fileOperationsApi.pendingConfirmationBatches(), []);
  const batches = pending.data?.items ?? [];

  // ADR 0005 密度优先：没有待确认批次就整块不出现，不留空态卡占第一屏。
  if (!pending.loading && !pending.error && batches.length === 0) return null;

  return (
    <section className="zs-sec" id="zs-pending-confirm">
      <div className="zs-card">
        <div className="zs-hd">
          <h3>待确认发货</h3>
          {batches.length > 0 ? <span className="zs-tag">{batches.length} 批</span> : null}
          <div className="zs-r">
            <Link to="/fulfillment/sales-outbound">去文件作业页</Link>
          </div>
        </div>
        <div className="zs-bd zs-flush">
          {pending.loading ? (
            <div className="zs-hint" style={{ padding: '12px 16px' }}>正在加载待确认批次…</div>
          ) : pending.error ? (
            <div className="zs-hint" style={{ padding: '12px 16px' }}>
              待确认批次加载失败：{errorMessage(pending.error)}
            </div>
          ) : (
            <div className="zs-rq" style={{ padding: 12 }}>
              {batches.map((batch) => (
                <BatchRow key={batch.id} batch={batch} onConfirmed={() => pending.reload()} />
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
