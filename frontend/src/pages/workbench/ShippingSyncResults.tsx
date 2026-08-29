/**
 * 同步结果呈现（Issue #107 原样迁出，契约由 shippingWorkbenchRoute.test.ts 锁定）：
 * 结果按渠道独立呈现 OK/FAILED/SKIPPED，聚福宝「仅报告未入库」一等状态；
 * 渠道卡整卡可点击原地打开批次快照；不可逆的整批确认仍留在文件作业页。
 */

import { Alert, Button, Spin, Tag } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { ApiError, errorMessage } from '@/api/client';
import type { PlatformOrderRefreshResult } from '@/api/types';
import {
  failedRefreshChannels,
  presentShippingChannel,
  summarizeShippingResult,
  type ShippingChannelView,
} from './shippingPresentation';
import PlatformPullSnapshotModal from './PlatformPullSnapshotModal';

export type SyncState =
  | { phase: 'idle' }
  | { phase: 'loading' }
  | { phase: 'success'; result: PlatformOrderRefreshResult }
  | { phase: 'error'; error: unknown };

function statusTagColor(status: ShippingChannelView['status']): 'success' | 'error' | 'default' {
  if (status === 'OK') return 'success';
  if (status === 'FAILED' || status === 'CONTRACT_ERROR') return 'error';
  return 'default';
}

export default function ShippingSyncResults({ state, onRetry }: { state: SyncState; onRetry: () => void }) {
  const [selectedChannel, setSelectedChannel] = useState<ShippingChannelView | null>(null);

  // ADR 0005：闲置态不占版面——没有同步结果时这里什么都不渲染，第一屏留给数据。
  if (state.phase === 'idle') return null;

  if (state.phase === 'loading') {
    return (
      <div className="zs-sync-loading">
        <Spin size="small" />
        <span>正在同步三平台订单…</span>
      </div>
    );
  }

  if (state.phase === 'error') {
    const channels = state.error instanceof ApiError
      ? failedRefreshChannels(state.error)?.map(presentShippingChannel)
      : null;
    return (
      <div className="zs-sync-stack">
        <Alert
          type="error"
          showIcon
          message="订单同步失败"
          description={errorMessage(state.error)}
          action={<Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>}
        />
        {channels?.length ? (
          <div className="zs-sync-channels">
            {channels.map((channel) => (
              <ChannelRow key={channel.channel} channel={channel} onOpen={() => setSelectedChannel(channel)} />
            ))}
          </div>
        ) : null}
        {selectedChannel ? (
          <PlatformPullSnapshotModal channel={selectedChannel} onClose={() => setSelectedChannel(null)} />
        ) : null}
      </div>
    );
  }

  const summary = summarizeShippingResult(state.result);
  const channels = state.result.channels.map(presentShippingChannel);
  const showAllClear = !summary.hasNewOrders
    && summary.totalRows !== null
    && summary.reportedOrders !== null
    && summary.failedCount === 0
    && summary.skippedCount === 0
    && summary.contractErrorCount === 0;
  const showIncompleteAlert = summary.skippedCount > 0
    || (!summary.hasNewOrders && summary.failedCount > 0)
    || summary.totalRows === null
    || summary.reportedOrders === null;
  const incompleteDescription = [
    summary.failedCount > 0 ? `${summary.failedCount} 个渠道失败，请重试` : '',
    summary.skippedCount > 0 ? `${summary.skippedCount} 个渠道已跳过` : '',
    summary.totalRows === null ? '批次行数暂无汇总' : '',
    summary.reportedOrders === null ? '仅报告订单数暂不可用' : '',
  ].filter(Boolean).join(' · ');

  return (
    <div className="zs-sync-stack">
      {summary.contractErrorCount > 0 ? (
        <Alert
          type="error"
          showIcon
          message="同步结果格式异常"
          description="渠道响应格式异常，请联系管理员。"
        />
      ) : null}
      {summary.hasNewOrders ? (
        <div className="zs-sync-summary">
          本次同步：生成 {summary.batchCount} 个导入批次
          {summary.totalRows === null ? ' · 行数暂无汇总' : ` · 共 ${summary.totalRows} 行`}
          {summary.reportedOrders !== null && summary.reportedOrders > 0
            ? ` · 仅报告未入库 ${summary.reportedOrders} 单`
            : ''}
          {summary.failedCount > 0 ? ` · ${summary.failedCount} 个渠道失败` : ''}
          {summary.skippedCount > 0 ? ` · ${summary.skippedCount} 个渠道已跳过` : ''}
        </div>
      ) : null}
      {showIncompleteAlert ? (
        <Alert
          type="warning"
          showIcon
          message="同步未完成"
          description={incompleteDescription}
        />
      ) : showAllClear ? (
        <Alert
          type="info"
          showIcon
          message="没有新订单"
          description="三平台已同步完成，本次没有生成新的导入批次，也没有拉到新的待发货订单。"
        />
      ) : null}
      {channels.length > 0 ? (
        /*
         * 一张卡片、逐行纵向排列，不是三张并排的卡。
         *
         * 并排时每张卡固定 320px：内容多的（聚福宝带批次号和行数）撑得很高，内容少的
         * （只有一个「成功」标签）留一大片空白，三张高度不齐；而且渠道之间本来是要横向
         * 对照「谁成了谁没成、各拉了多少」的，卡片各占一格反而对不上。改成一行一个渠道，
         * 同类信息落在同一列。
         */
        <div className="zs-sync-channels">
          {channels.map((channel) => (
            <ChannelRow key={channel.channel} channel={channel} onOpen={() => setSelectedChannel(channel)} />
          ))}
        </div>
      ) : null}
      {selectedChannel ? (
        <PlatformPullSnapshotModal
          channel={selectedChannel}
          dateBegin={state.result.date_begin}
          dateEnd={state.result.date_end}
          onClose={() => setSelectedChannel(null)}
        />
      ) : null}
    </div>
  );
}

/**
 * 一个渠道一行。
 *
 * <p>左边固定放「渠道名 + 状态」，右边放这次拉到了什么。三行叠起来后，「谁成了谁没成」
 * 在同一列上下一扫就完，不用在三张并排卡片之间来回找。
 */
function ChannelRow({ channel, onOpen }: { channel: ShippingChannelView; onOpen: () => void }) {
  const details = [
    channel.batchNo ? `批次 ${channel.batchNo}` : null,
    channel.rowCounts
      ? `共 ${channel.rowCounts.total} 行 · 已接收 ${channel.rowCounts.accepted} · 待复核 ${channel.rowCounts.need_review} · 拒绝 ${channel.rowCounts.rejected}`
      : null,
    channel.message,
  ].filter(Boolean) as string[];

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen();
        }
      }}
      className="zs-sync-row"
    >
      <div className="zs-sync-row-name">
        <strong>{channel.label}</strong>
        <Tag color={statusTagColor(channel.status)}>{channel.statusText}</Tag>
        {channel.reportOnly ? <Tag color="warning">仅报告未入库</Tag> : null}
      </div>
      <div className="zs-sync-row-detail">
        {/*
          什么都没拉到时也要占住这一格，否则整行塌成半行、三行高度又不齐了。
          文案刻意<b>不用</b>「没有新订单」——那句是三平台全部同步完成后的全局结论
          （见上方 showAllClear 分支）。一个渠道这次没拉到，和「三平台都好、确实没单」
          是两回事；用同一句话说，就是这个仓一直在防的「一边丢单一边报成功」。
        */}
        {details.length === 0 && !channel.reportOnly ? (
          <span className="zs-muted">本次无新增</span>
        ) : null}
        {channel.reportOnly ? (
          <span>{channel.orderCount == null ? '拉取数量暂不可用' : `拉取 ${channel.orderCount} 单`}</span>
        ) : null}
        {details.map((text) => (
          <span key={text}>{text}</span>
        ))}
      </div>
    </div>
  );
}
