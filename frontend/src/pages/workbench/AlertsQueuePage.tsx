/**
 * 运营提醒页（/workbench/alerts，Issue #64 独立路由）。
 * 提醒状态筛选以 URL 为唯一事实源（参数名 status，与复核队列同模式），
 * 列表承载结构复用 QueueTable；确认已知晓（ACK）表单收在 AlertDrawer。
 * 本页是 #98 准入规则下的隐藏可路由叶子：不占作业中心可见菜单位，
 * 由复核页与本页互为上下文切换入口。
 */

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Empty, Select, Tag, Typography } from 'antd';
import { CheckSquareOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import type { OperationalAlert, OperationalAlertStatus } from '@/api/types';
import { operationalAlertsApi } from '@/api/endpoints';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import { operationalAlertStatusSemantic, severitySemantic } from '@/pages/shared/semanticStatus';
import { ALERTS_STATUS_PARAM, reviewsQueueUrl } from '@/pages/shared/reviewQueueUrl';
import QueueTable from './queueTable';
import AlertDrawer from './AlertDrawer';
import { useQueuePagination } from './queuePagination';
import { ALERT_STATUS_LABELS } from './queuePresentation';

export default function AlertsQueuePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const statusParam = searchParams.get(ALERTS_STATUS_PARAM);
  const alertStatus: OperationalAlertStatus = statusParam === 'ACKNOWLEDGED' || statusParam === 'RESOLVED'
    ? statusParam
    : 'OPEN';
  const { page, size, setPage, onPageChange } = useQueuePagination(alertStatus);
  const [selectedAlert, setSelectedAlert] = useState<OperationalAlert | null>(null);

  const alerts = useAsync(
    () => operationalAlertsApi.list({ page, size, status: alertStatus }),
    [page, size, alertStatus],
  );
  const alertItems = alerts.data?.items ?? [];

  /** 状态筛选变更：写回 URL（可分享/刷新恢复）并回到第一页。 */
  function updateAlertStatus(next: OperationalAlertStatus) {
    const params = new URLSearchParams(searchParams);
    params.set(ALERTS_STATUS_PARAM, next);
    setPage(0);
    setSearchParams(params);
  }

  const alertColumns: ColumnsType<OperationalAlert> = [
    { title: '提醒单号', dataIndex: 'alert_no', width: 150 },
    { title: '提醒类型', dataIndex: 'alert_type', width: 160 },
    { title: '内容', dataIndex: 'message' },
    {
      title: '等级', dataIndex: 'severity', width: 80,
      render: (value: OperationalAlert['severity']) => <Tag color={severitySemantic(value)}>{value === 'RED' ? '红色' : '黄色'}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (value: OperationalAlertStatus) => <Tag color={operationalAlertStatusSemantic(value)}>{ALERT_STATUS_LABELS[value]}</Tag>,
    },
    { title: '创建时间', dataIndex: 'created_at', width: 150, render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm') },
    { title: '操作', key: 'action', width: 90, fixed: 'right', render: (_, item) => <Typography.Link onClick={() => setSelectedAlert(item)}>查看确认</Typography.Link> },
  ];

  return (
    <PageShell
      title="运营提醒"
      description="运营提醒只记录知晓，不推进业务状态。"
      icon={<CheckSquareOutlined />}
      actions={<Link to={reviewsQueueUrl()}>阻断复核</Link>}
    >
      <QueueTable<OperationalAlert>
        rowKey="id"
        columns={alertColumns}
        items={alertItems}
        loading={alerts.loading}
        error={alerts.error}
        errorTitle="运营提醒加载失败"
        emptyText={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有运营提醒" />}
        total={alerts.data?.total_elements ?? 0}
        page={page}
        pageSize={size}
        onPageChange={onPageChange}
        onReload={alerts.reload}
        filterControls={
          <>
            <Typography.Text type="secondary">状态</Typography.Text>
            <Select<OperationalAlertStatus>
              id="alert-status-filter"
              value={alertStatus} style={{ width: 130 }}
              onChange={updateAlertStatus}
              options={Object.entries(ALERT_STATUS_LABELS).map(([value, label]) => ({ value: value as OperationalAlertStatus, label }))}
            />
          </>
        }
      />

      <AlertDrawer
        selected={selectedAlert}
        onClose={() => setSelectedAlert(null)}
        onQueueReload={alerts.reload}
      />
    </PageShell>
  );
}
