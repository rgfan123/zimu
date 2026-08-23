/**
 * 同步结果呈现（Issue #107 原样迁出，契约由 shippingWorkbenchRoute.test.ts 锁定）：
 * 结果按渠道独立呈现 OK/FAILED/SKIPPED，聚福宝「仅报告未入库」一等状态；
 * 有 batch_id 的渠道卡整卡可点击跳文件作业页（?import_batch=ID）。
 */

import { Alert, Button, Card, Space, Spin, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { ApiError, errorMessage } from '@/api/client';
import type { PlatformOrderRefreshResult } from '@/api/types';
import {
  failedRefreshChannels,
  presentShippingChannel,
  summarizeShippingResult,
  type ShippingChannelView,
} from './shippingPresentation';

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
  if (state.phase === 'idle') {
    return (
      <Typography.Text type="secondary">
        尚未同步，点击上方「开始今日订单同步」开始今天的工作。
      </Typography.Text>
    );
  }

  if (state.phase === 'loading') {
    return (
      <Card size="small">
        <Space>
          <Spin size="small" />
          <Typography.Text type="secondary">正在同步三平台订单…</Typography.Text>
        </Space>
      </Card>
    );
  }

  if (state.phase === 'error') {
    const channels = state.error instanceof ApiError
      ? failedRefreshChannels(state.error)?.map(presentShippingChannel)
      : null;
    return (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          type="error"
          showIcon
          message="订单同步失败"
          description={errorMessage(state.error)}
          action={<Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>}
        />
        {channels?.length ? (
          <Space wrap size={12} align="start">
            {channels.map((channel) => <ChannelCard key={channel.channel} channel={channel} />)}
          </Space>
        ) : null}
      </Space>
    );
  }

  const summary = summarizeShippingResult(state.result);
  const channels = state.result.channels.map(presentShippingChannel);
  const showAllClear = !summary.hasNewOrders
    && summary.failedCount === 0
    && summary.skippedCount === 0
    && summary.contractErrorCount === 0;
  const showIncompleteAlert = summary.skippedCount > 0
    || (!summary.hasNewOrders && summary.failedCount > 0);
  const incompleteDescription = [
    summary.failedCount > 0 ? `${summary.failedCount} 个渠道失败，请重试` : '',
    summary.skippedCount > 0 ? `${summary.skippedCount} 个渠道已跳过` : '',
  ].filter(Boolean).join(' · ');

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {summary.contractErrorCount > 0 ? (
        <Alert
          type="error"
          showIcon
          message="同步结果格式异常"
          description="渠道响应格式异常，请联系管理员。"
        />
      ) : null}
      {summary.hasNewOrders ? (
        <Typography.Text>
          本次同步：生成 {summary.batchCount} 个导入批次 · 共 {summary.totalRows} 行
          {summary.reportedOrders > 0 ? ` · 仅报告未入库 ${summary.reportedOrders} 单` : ''}
          {summary.failedCount > 0 ? ` · ${summary.failedCount} 个渠道失败` : ''}
          {summary.skippedCount > 0 ? ` · ${summary.skippedCount} 个渠道已跳过` : ''}
        </Typography.Text>
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
        <Space wrap size={12} align="start">
          {channels.map((channel) => <ChannelCard key={channel.channel} channel={channel} />)}
        </Space>
      ) : null}
    </Space>
  );
}

function ChannelCard({ channel }: { channel: ShippingChannelView }) {
  const card = (
    <Card size="small" hoverable={Boolean(channel.destination)} style={{ width: 320 }}>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Typography.Text strong>{channel.label}</Typography.Text>
          <Tag color={statusTagColor(channel.status)}>{channel.statusText}</Tag>
        </Space>
        {channel.batchNo ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            批次 {channel.batchNo}
          </Typography.Text>
        ) : null}
        {channel.reportOnly ? (
          <Space>
            <Tag color="warning">仅报告未入库</Tag>
            <Typography.Text>拉取 {channel.orderCount} 单</Typography.Text>
          </Space>
        ) : null}
        {channel.rowCounts ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            共 {channel.rowCounts.total} 行 · 已接收 {channel.rowCounts.accepted} · 待复核 {channel.rowCounts.need_review} · 拒绝 {channel.rowCounts.rejected}
          </Typography.Text>
        ) : null}
        {channel.message ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{channel.message}</Typography.Text>
        ) : null}
      </Space>
    </Card>
  );

  if (channel.destination) {
    return (
      <Link to={channel.destination} style={{ display: 'block' }}>
        {card}
      </Link>
    );
  }
  return card;
}
