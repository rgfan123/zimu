/**
 * 今日发货工作台（Issue #107）：发货员从一次订单同步开始今天的工作，并如实呈现各渠道结果。
 * 「开始今日订单同步」调用 POST /api/v1/platform-orders/refresh，同步中禁用；
 * 结果按渠道独立呈现 OK/FAILED/SKIPPED，聚福宝「仅报告未入库」作为一等状态；
 * 有 batch_id 的渠道卡整卡可点击跳文件作业页（?import_batch=ID）。
 * 视觉只使用页面级 saasTheme / antd 语义 token，不新增 CSS 调色板或共享组件。
 */

import { useRef, useState } from 'react';
import { Alert, Button, Card, Space, Spin, Tag, Typography } from 'antd';
import { CloudSyncOutlined, FileExcelOutlined, ReloadOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import PageShell from '@/components/PageShell';
import { ApiError, errorMessage } from '@/api/client';
import { platformOrdersApi } from '@/api/endpoints';
import type { PlatformOrderRefreshResult } from '@/api/types';
import {
  failedRefreshChannels,
  presentShippingChannel,
  summarizeShippingResult,
  type ShippingChannelView,
} from './shippingPresentation';

const LEDE = '从一次订单同步开始今天的工作：拉取彩食鲜 / 聚福宝 / 飞象最新待发货订单，落为导入批次，并逐渠道如实显示结果。';

const HERO_DESCRIPTION = '一次动作拉取彩食鲜 / 聚福宝 / 飞象最新待发货订单并落为导入批次；聚福宝 JSON 直连缺收货人字段，只报告拉取数量、不生成导入批次。失败会停在这里等你重试，不会偷偷跳过。';

type SyncState =
  | { phase: 'idle' }
  | { phase: 'loading' }
  | { phase: 'success'; result: PlatformOrderRefreshResult }
  | { phase: 'error'; error: unknown };

function statusTagColor(status: ShippingChannelView['status']): 'success' | 'error' | 'default' {
  if (status === 'OK') return 'success';
  if (status === 'FAILED' || status === 'CONTRACT_ERROR') return 'error';
  return 'default';
}

export default function ShippingWorkbenchPage() {
  const [state, setState] = useState<SyncState>({ phase: 'idle' });
  const syncInFlight = useRef(false);
  const navigate = useNavigate();

  const sync = async () => {
    // 原生 disabled 落到 DOM 前仍可能收到同一事件循环内的第二次触发；ref 是最后一道前端重入门禁。
    if (syncInFlight.current) return;
    syncInFlight.current = true;
    setState({ phase: 'loading' });
    try {
      const result = await platformOrdersApi.refresh();
      setState({ phase: 'success', result });
    } catch (error) {
      setState({ phase: 'error', error });
    } finally {
      syncInFlight.current = false;
    }
  };

  const syncing = state.phase === 'loading';

  return (
    <PageShell title="今日发货工作台" description={LEDE} icon={<CloudSyncOutlined />}>
      <Card size="small">
        <Space align="start" size={16} wrap style={{ width: '100%' }}>
          <div style={{ flex: 1, minWidth: 240 }}>
            <Typography.Title level={5} style={{ margin: 0 }}>
              开始今日订单同步
            </Typography.Title>
            <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
              {HERO_DESCRIPTION}
            </Typography.Text>
          </div>
          <Space wrap>
            <Button
              type="primary"
              size="large"
              icon={<CloudSyncOutlined />}
              loading={syncing}
              disabled={syncing}
              onClick={sync}
            >
              开始今日订单同步
            </Button>
            {/* 单一 <a>（Button href），不 Link 包 Button：保留真实 href 与键盘可达性，点击经路由导航。 */}
            <Button
              size="large"
              icon={<FileExcelOutlined />}
              href="/fulfillment/sales-outbound"
              onClick={(event) => {
                event.preventDefault();
                navigate('/fulfillment/sales-outbound');
              }}
            >
              手动导入 Excel
            </Button>
          </Space>
        </Space>
      </Card>

      <SyncResults state={state} onRetry={sync} />
    </PageShell>
  );
}

function SyncResults({ state, onRetry }: { state: SyncState; onRetry: () => void }) {
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
